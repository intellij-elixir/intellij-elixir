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
import org.elixir_lang.psi.scope.CallDefinitionClause.Companion.containsCompileTimeAncestorOrSelf
import org.elixir_lang.psi.stub.type.call.Stub.isModular

/**
 * Every [CallableDeclaration.Form.CLAUSE]/[CallableDeclaration.Form.CALLBACK]/
 * [CallableDeclaration.Form.DELEGATION]/[CallableDeclaration.Form.EXCEPTION] declaration reachable from a
 * module's own body - transparently through `for`, `if`/`unless`, `import`, `quote`, `use` and `try`, never
 * crossing into a nested module or `Case`-style block - built once per `modular` and invalidated on any PSI
 * change, so the walk [#4123](https://github.com/intellij-elixir/intellij-elixir/issues/4123) reports runs
 * once per module rather than once per resolved call.
 *
 * [CallableDeclaration.Form.EEX_FUNCTION_FROM]/`GENERATOR_EMBED`, Ecto's `Schema`/`Query`/`Query.API`/
 * `WindowAPI` DSLs, ExUnit's `describe`/`test`, and an otherwise-unrecognized macro call with a `do` block or
 * keyword body, all decide by resolving the candidate call's own reference
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
     * [wrappers] is every `import`/`use`/`if`/`unless` call crossed collecting this entry, in outside-in
     * order. `Import`/`Use.treeWalkUp` refuse to descend back into themselves when the entrance is the
     * alias token written in the statement itself, and an `if`/`unless` skips its branches when the
     * entrance is compile-time-reachable from one of them (both live checks, not build-time ones, since
     * there is no real entrance while this table is being built) - [reachableFrom] replays both against the
     * entrance a caller actually has.
     */
    data class Entry(
        val call: Call,
        val form: CallableDeclaration.Form,
        val visitedElements: Set<PsiElement>,
        val wrappers: List<Call>
    ) {
        fun reachableFrom(entrance: PsiElement?): Boolean = wrappers.reachableFrom(entrance)
    }

    data class Candidate(val call: Call, val visitedElements: Set<PsiElement>, val wrappers: List<Call>) {
        fun reachableFrom(entrance: PsiElement?): Boolean = wrappers.reachableFrom(entrance)
    }

    data class BeamCandidate(
        val callDefinition: BeamCallDefinition,
        val visitedElements: Set<PsiElement>,
        val wrappers: List<Call>
    ) {
        fun reachableFrom(entrance: PsiElement?): Boolean = wrappers.reachableFrom(entrance)
    }

    val entries: List<Entry> by lazy { byName.values.flatten() }

    fun declaring(name: Name): List<Entry> = byName[name] ?: emptyList()

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

        @RequiresReadLock
        private fun build(modular: Call): CallableTable {
            val byName = linkedMapOf<Name, MutableList<Entry>>()
            val liveCandidates = mutableListOf<Candidate>()
            val beamCallDefinitions = mutableListOf<BeamCandidate>()

            fun collect(element: PsiElement, state: ResolveState, wrappers: List<Call>) {
                ProgressManager.checkCanceled()

                when (element) {
                    is BeamCallDefinition ->
                        beamCallDefinitions.add(BeamCandidate(element, state.visitedElementSet(), wrappers))
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
                liveCandidates.add(Candidate(call, state.visitedElementSet(), wrappers))
                return
            }

            val form = CallableDeclaration.deterministicFormOf(call)

            if (form != null) {
                for ((name, _) in CallableDeclaration.declarations(call, form, ResolveState.initial())) {
                    byName.getOrPut(name) { mutableListOf() }
                        .add(Entry(call, form, state.visitedElementSet(), wrappers))
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
                hasDoBlockOrKeyword(call) -> liveCandidates.add(Candidate(call, state.visitedElementSet(), wrappers))
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
         * (Ecto/ExUnit's DSLs, EEx, Mix.Generator) - checked before any other classification so this table's
         * build never has to.
         */
        private val LIVE_FUNCTION_NAMES = setOf(
            "function_from_file", "function_from_string",
            "embed_template", "embed_text",
            "embedded_schema", "schema",
            "distinct", "dynamic", "from", "group_by", "having", "join", "lock", "order_by", "preload",
            "select", "select_merge", "update", "where", "windows", "with_cte",
            "describe", "test"
        )
    }
}
