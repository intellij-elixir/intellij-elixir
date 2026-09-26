package org.elixir_lang.psi

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.isAncestor
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.EEx
import org.elixir_lang.Name
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.TRY
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.hasDoBlockOrKeyword
import org.elixir_lang.psi.impl.call.macroChildCallSequence
import org.elixir_lang.psi.impl.call.whileInStabBodyChildExpressions
import org.elixir_lang.psi.mix.Generator
import org.elixir_lang.psi.scope.CallDefinitionClause.Companion.containsCompileTimeAncestorOrSelf
import org.elixir_lang.psi.scope.NameMatch
import org.elixir_lang.psi.stub.type.call.Stub.isModular
import org.elixir_lang.structure_view.element.Timed

/**
 * Every [CallableDeclaration.Form.CLAUSE]/[CallableDeclaration.Form.CALLBACK]/
 * [CallableDeclaration.Form.DELEGATION]/[CallableDeclaration.Form.EXCEPTION] declaration reachable from a
 * module's own body - transparently through `for`, `if`/`unless`, `import`, `quote`, `use` and `try`, never
 * crossing into a nested module or `Case`-style block - built once per `modular` and invalidated on any PSI
 * change, so the walk [#4123](https://github.com/intellij-elixir/intellij-elixir/issues/4123) reports runs
 * once per module rather than once per resolved call.
 *
 * A `defmacro`/`defmacrop` entry doubles as the answer to "does this module declare something a call site
 * elsewhere invokes as a library DSL" ([Entry.isMacro], [Entry.declaringModuleName], [definersIn]) - the
 * question [org.elixir_lang.psi.ModuleWalker] and [org.elixir_lang.resolvesToModularName] answer for Ecto's
 * `Schema`/`Query` and ExUnit's `Case`/`Assertions` by resolving the *candidate* call's own reference. That
 * self-reference is a real reentrancy hazard once the enclosing module's own table build reaches back into
 * itself through it - see [ofOrNull].
 *
 * [CallableDeclaration.Form.EEX_FUNCTION_FROM]/`GENERATOR_EMBED` and an otherwise-unrecognized macro call
 * with a `do` block or keyword body still decide by resolving the candidate call's own reference
 * ([org.elixir_lang.resolvesToModularName]/[org.elixir_lang.psi.impl.call.CallImpl.hasDoBlockOrKeyword]'s
 * unknown-macro fallback) - unsafe to run while this table is mid-build, and already cheaply pre-filtered by
 * function name or ancestry everywhere else in the plugin, so they were never the cost this table exists to
 * remove. [liveCandidates] carries them unclassified, to be walked exactly as before this table existed.
 */
class CallableTable private constructor(
    private val byName: Map<Name, List<Entry>>,
    val liveCandidates: List<Candidate>,
    val beamCallDefinitions: List<BeamCandidate>
) {
    /**
     * [wrappers] is every `import`/`use`/`if`/`unless` call crossed collecting the entry this belongs to, in
     * outside-in order. `Import`/`Use.treeWalkUp` refuse to descend back into themselves when the entrance
     * is the alias token written in the statement itself, and an `if`/`unless` skips its branches when the
     * entrance is compile-time-reachable from one of them (both live checks, not build-time ones, since
     * there is no real entrance while this table is being built) - [reachableFrom] replays both against the
     * entrance a caller actually has. Shared by [Entry]/[Candidate]/[BeamCandidate], which otherwise carry
     * nothing else in common.
     */
    data class Path(val visitedElements: Set<PsiElement>, val wrappers: List<Call>) {
        fun reachableFrom(entrance: PsiElement?): Boolean = wrappers.reachableFrom(entrance)
    }

    /** [Entry]/[Candidate]/[BeamCandidate] otherwise carry nothing else in common - each just pairs its own
     *  payload with the [Path] the walk that found it crossed. */
    interface Reachable {
        val path: Path
        fun reachableFrom(entrance: PsiElement?): Boolean = path.reachableFrom(entrance)
    }

    /** [arityInterval] is unadjusted (no `Kernel.SpecialForms`/`Ecto.Query.(Window)API` override, see
     *  [CallableDeclaration]'s own doc) - fine for [definersIn], since neither adjustment ever applies to a
     *  library macro a call site invokes as a DSL. */
    data class Entry(
        val call: Call,
        val form: CallableDeclaration.Form,
        val arityInterval: ArityInterval?,
        override val path: Path
    ) : Reachable {
        val isMacro: Boolean by lazy { CallDefinitionClause.isMacro(call) }
        val declaringModuleName: String? by lazy { CallDefinitionClause.enclosingModularMacroCall(call)?.name }
    }

    data class Candidate(val call: Call, override val path: Path) : Reachable

    data class BeamCandidate(val callDefinition: BeamCallDefinition, override val path: Path) : Reachable

    val entries: List<Entry> by lazy { byName.values.flatten() }

    fun declaring(name: Name): List<Entry> = byName[name] ?: emptyList()

    /**
     * Every source-declared macro clause named [name] at an arity containing [arity], declared by the module
     * named [modularName], excluding [candidate] itself - reproducing
     * [org.elixir_lang.resolvesToModularName]'s "don't treat the signature as a call of the macro" guard without resolving
     * [candidate]'s own reference. A compiled (`.beam`) dependency's macro is a decompiled stub with no
     * walkable body ([Using]'s own `BeamCallDefinition -> TODO()`), so it is deliberately absent here - see
     * [matchesCompiledIn] for recognizing one without a [Call] to hand back.
     */
    fun definersIn(name: Name, arity: Int, modularName: String, candidate: Call): List<Call> =
        declaring(name)
            .filter {
                it.isMacro && it.arityInterval?.contains(arity) == true && it.declaringModuleName == modularName &&
                    !it.call.isAncestor(candidate)
            }
            .map { it.call }

    /**
     * Whether the module named [modularName] declares a compiled macro named [name] at an arity containing
     * [arity] - membership only, unlike [definersIn]: a `.beam` macro has no decompiled body for a caller
     * like [org.elixir_lang.ecto.Schema.walkChild] to walk into, so this only ever answers a yes/no
     * DSL-recognition question (`isChild`/`matches`-style), never hands back something to resolve into.
     */
    fun matchesCompiledIn(name: Name, arity: Int, modularName: String): Boolean =
        beamCallDefinitions.any {
            val definition = it.callDefinition

            definition.time == Timed.Time.COMPILE &&
                definition.nameArityInterval.name == name &&
                definition.nameArityInterval.arityInterval.contains(arity) &&
                definition.parent.name == modularName
        }

    companion object {
        @RequiresReadLock
        fun of(modular: Call): CallableTable =
            if (DumbService.isDumb(modular.project)) {
                // `Import`/`Use` resolve their target module by name through the stub index, which is
                // unavailable while dumb; matching `reference/Callable.kt`'s `variableUseScope`, skip the
                // cache entirely rather than risk caching a dumb-mode answer.
                build(modular)
            } else {
                CachedValuesManager.getCachedValue(modular) {
                    // Project-wide `PsiModificationTracker.MODIFICATION_COUNT`, not a per-file modification
                    // stamp: `buildUnguarded` resolves `Import`/`Use.treeWalkUp`'s target module by name and
                    // walks into it, so a table's `Entry`/`Candidate` set can span other files besides
                    // `modular`'s own - a narrower, per-file stamp would miss an edit to any of those other
                    // files, serving a stale table whose cached `Call`s can even be invalid PSI by then.
                    CachedValueProvider.Result(build(modular), PsiModificationTracker.MODIFICATION_COUNT)
                }
            }

        /**
         * [of], but `null` while [modular]'s own table is already being built on this thread. Ecto's
         * `Schema`/`Query` and ExUnit's `Case`/`Assertions` (via [org.elixir_lang.psi.ModuleWalker]) decide
         * membership by consulting an *enclosing* module's table from inside a candidate call that this
         * table's own build walked into via `use`/`import` - reaching [modular]'s own build back through
         * itself before it returns. The thread-local set here answers `null` before that happens, so the
         * caller falls back live instead of asking [CachedValuesManager] to compute a value it is already
         * computing.
         */
        @RequiresReadLock
        fun ofOrNull(modular: Call): CallableTable? =
            if (modular in buildingOnThisThread.get()) null else of(modular)

        // Not `RecursionManager.doPreventingRecursion`: a prevented computation marks the whole call stack
        // `mayCacheNow() == false`, which would stop the enclosing `CachedValuesManager.getCachedValue` in
        // `of` from caching at all - the exact cache-poisoning hazard this hand-rolled guard exists to
        // avoid, by answering `null` (see `ofOrNull`) before `of` ever asks `CachedValuesManager` for
        // anything.
        private val buildingOnThisThread: ThreadLocal<MutableSet<Call>> = ThreadLocal.withInitial { mutableSetOf() }

        @RequiresReadLock
        private fun build(modular: Call): CallableTable {
            val building = buildingOnThisThread.get()
            building.add(modular)

            try {
                return buildUnguarded(modular)
            } finally {
                building.remove(modular)
            }
        }

        @RequiresReadLock
        private fun buildUnguarded(modular: Call): CallableTable {
            val byName = linkedMapOf<Name, MutableList<Entry>>()
            val liveCandidates = mutableListOf<Candidate>()
            val beamCallDefinitions = mutableListOf<BeamCandidate>()

            fun collect(element: PsiElement, state: ResolveState, wrappers: List<Call>) {
                ProgressManager.checkCanceled()

                when (element) {
                    is BeamCallDefinition ->
                        beamCallDefinitions.add(BeamCandidate(element, Path(state.visitedElementSet(), wrappers)))
                    is Call ->
                        collectCall(element, state, wrappers, byName, liveCandidates, beamCallDefinitions, ::collect)
                    else -> Unit
                }
            }

            // `Import`/`Use.treeWalkUp` compare `ENTRANCE` with `isAncestor`, which rejects a null second
            // argument - seed the containing file, never a descendant of any wrapper call in the module, so
            // that comparison is always false (there is no real entrance to protect during a build).
            val buildState = ResolveState.initial().put(ENTRANCE, modular.containingFile)

            for (childCall in modular.macroChildCallSequence()) {
                ProgressManager.checkCanceled()

                collectCall(childCall, buildState, emptyList(), byName, liveCandidates, beamCallDefinitions, ::collect)
            }

            return CallableTable(byName, liveCandidates, beamCallDefinitions)
        }

        /**
         * The function-name check runs first, before [isModular]/`Case.isChild`-style scope detection or
         * [CallableDeclaration.deterministicFormOf] - every one of those live forms decides by resolving
         * [call]'s own reference, which this table's build must never do (see the class doc).
         */
        private fun collectCall(
            call: Call,
            state: ResolveState,
            wrappers: List<Call>,
            byName: MutableMap<Name, MutableList<Entry>>,
            liveCandidates: MutableList<Candidate>,
            beamCallDefinitions: MutableList<BeamCandidate>,
            recurse: (PsiElement, ResolveState, List<Call>) -> Unit
        ) {
            if (call.functionName() in LIVE_FUNCTION_NAMES) {
                liveCandidates.add(Candidate(call, Path(state.visitedElementSet(), wrappers)))
                return
            }

            val form = CallableDeclaration.deterministicFormOf(call)

            if (form != null) {
                val path = Path(state.visitedElementSet(), wrappers)

                for ((name, arityInterval) in CallableDeclaration.declarations(call, form, ResolveState.initial())) {
                    // keyed as a lookup's normalized query finds it, so two spellings of one identifier meet
                    byName.getOrPut(NameMatch.key(name, call)) { mutableListOf() }
                        .add(Entry(call, form, arityInterval, path))
                }

                return
            }

            when {
                isModular(call) -> Unit
                For.`is`(call) ->
                    For.treeWalkDown(call, state) { element, accState -> recurse(element, accState, wrappers); true }
                If.`is`(call) || Unless.`is`(call) -> {
                    val branches = Branches(call)
                    val childWrappers = wrappers + call

                    (branches.primaryChildExpressions.filterIsInstance<Call>() +
                            branches.alternativeChildExpressions.filterIsInstance<Call>())
                        .forEach { recurse(it, state, childWrappers) }
                }
                Import.`is`(call) -> {
                    try {
                        Import.treeWalkUp(call, state) { element, accState -> recurse(element, accState, wrappers + call); true }
                    } catch (stackOverflowError: StackOverflowError) {
                        Logger.error(
                            CallableTable::class.java,
                            "StackOverflowError while collecting an import",
                            call,
                            stackOverflowError
                        )
                    }
                }
                QuoteMacro.`is`(call) -> if (!state.hasBeenVisited(call)) {
                    QuoteMacro.treeWalkUp(call, state) { element, accState -> recurse(element, accState, wrappers); true }
                }
                Use.`is`(call) ->
                    Use.treeWalkUp(call, state) { element, accState -> recurse(element, accState, wrappers + call); true }
                call.isCalling(KERNEL, TRY) ->
                    call.whileInStabBodyChildExpressions { recurse(it, state, wrappers); true }
                hasDoBlockOrKeyword(call) -> liveCandidates.add(Candidate(call, Path(state.visitedElementSet(), wrappers)))
                else -> Unit
            }
        }

        /**
         * `import`/`use` refuse to re-descend into themselves when the entrance is the alias token written
         * in the statement, and an `if`/`unless` skips its branches when the entrance is compile-time
         * reachable from one of them - see [org.elixir_lang.psi.scope.CallDefinitionClause]'s
         * `containsCompileTimeEntranceAncestorOrSelf` for the live version of the second check this
         * reproduces. `quote`/`for`/`try` have no entrance-relative gate (only a visited-set cycle guard,
         * already reproduced by threading [ResolveState] through collection), so they are never recorded as
         * wrappers.
         */
        private fun List<Call>.reachableFrom(entrance: PsiElement?): Boolean =
            entrance == null || all { wrapper ->
                if (If.`is`(wrapper) || Unless.`is`(wrapper)) {
                    val branches = Branches(wrapper)

                    !containsCompileTimeAncestorOrSelf(
                        branches.primaryChildExpressions.filterIsInstance<Call>() +
                            branches.alternativeChildExpressions.filterIsInstance<Call>(),
                        entrance
                    )
                } else {
                    !wrapper.isAncestor(entrance)
                }
            }

        /**
         * Function names that only ever decide whether they declare anything by resolving the call itself
         * (EEx, Mix.Generator) - checked before any other classification so this table's build never has to.
         * Ecto's `Schema`/`Query` and ExUnit's `Case`/`Assertions` no longer need this: `schema`/
         * `embedded_schema`/`describe`/`test` all carry a `do` block, so [collectCall]'s generic
         * `hasDoBlockOrKeyword` fallback already routes them to [liveCandidates] without naming them, and
         * membership for those is now answered structurally by [definersIn] instead of resolving anything.
         */
        private val LIVE_FUNCTION_NAMES =
            setOf(EEx.FUNCTION_FROM_FILE_ARITY_RANGE.name, EEx.FUNCTION_FROM_STRING_ARITY_RANGE.name) + Generator.NAMES
    }
}
