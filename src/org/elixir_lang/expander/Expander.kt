package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangPid
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.expander.interpreter.Binding
import org.elixir_lang.expander.interpreter.Given
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFMODULE_FAST_PATH
import org.elixir_lang.language_level.ElixirLanguageFeature.FAILED_COMPILE_PURGES_MODULE
import org.elixir_lang.language_level.ElixirLanguageFeature.FAST_PATH_ADDS_CONTEXT_MODULE
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Elixir's expander (`elixir_expand`), ported clause for clause, over [ElixirAst]. It holds no PSI and takes no lock.
 */
object Expander {
    /**
     * [ast] expanded from [state] and [env] as Elixir at [level] expands it, with [exports] standing for the modules
     * Elixir would load and [structs] for their structs, up to the first error Elixir raises or the first node that
     * isn't ported. [observer] is told of each node reached, and [counters] gives each macro expansion its hygiene
     * counter. An error Elixir reports and carries on after isn't returned, and a definition or module [ast] defines is
     * never compiled; [expandFile] does both.
     */
    internal fun expand(
        ast: ElixirAst,
        state: ExState,
        env: Env,
        level: ElixirLanguageLevel,
        exports: Exports,
        structs: Structs,
        observer: ExpansionObserver = ExpansionObserver.NONE,
        counters: Counters = Counters(),
    ): Expansion = expand(ast, state, env, Run(level, observer, exports, structs, counters))

    internal fun expand(ast: ElixirAst, state: ExState, env: Env, run: Run): Expansion {
        val from = run.pending.size

        return observed(ast, state, env, run) {
            Clause.entries
                .firstOrNull { it.matches(ast, state, env, run.level) }
                ?.expand(ast, state, env, run)
                ?: Expansion.Unported(ast)
        }.also { if (it is Expansion.Expanded) queueStatement(ast, it, from, state, env, run) }
    }

    /**
     * [node], expanded to [expansion], queued for the walk after what it queued since [Run.pending] had [from] entries,
     * where it is a statement its body runs where it is: a `=`, which the walk runs, or one whose value nothing takes,
     * which no unit queued and no macro's output replaced. One whose value is known, as it expanded or as the evaluator
     * takes it, or that a port built, raises nowhere, so it is inert instead, unless a body it runs holds units, which
     * run only where it reaches them.
     */
    private fun queueStatement(node: ElixirAst, expansion: Expansion.Expanded, from: Int, state: ExState, env: Env, run: Run) {
        val statements = run.statementsAt(env)
            ?.takeIf { env.function == null && env.context == Env.Context.NONE && (it.contains(node) || it.follows(node)) }
            ?: return
        // A unit of a body the walk follows runs where the evaluator reaches it as the statement runs; one of a body it
        // doesn't runs before the statement, from the body's own bindings.
        val queued = run.pending.subList(from, run.pending.size).toList()
        val (inner, before) = queued.partition { statements.follows(it.site) }
        val statement = statements.contains(node)

        when {
            isCall(node, "=", 2) -> run.replacePending(from, before + Pending.Match(node, state, env, statement, inner))
            !statements.isWhole(node) || statements.isRewritten(node) -> Unit
            queued.any { it.site === node } -> Unit
            inner.isNotEmpty() -> run.replacePending(from, before + Pending.Statement(node, state, env, statement, inner))
            expansion.value == VARIABLE_NODE || isKnown(expansion.value) -> statements.inert(node)
            (run.dispatchesAt(env)?.of(node) as? ModuleClauses.Dispatched.Value)?.value?.let(::isKnown) == true ->
                statements.inert(node)
            run.isPorted(node) -> statements.inert(node)
            else -> run.replacePending(from, before + Pending.Statement(node, state, env, statement, inner))
        }
    }

    private fun isKnown(term: Term): Boolean = AttributeValue.of(term) is AttributeValue.Known

    /**
     * A file's top-level [forms] expanded from [env] as Elixir at [level] compiles them: the forms, then each module
     * they define, in turn, with [counters] giving each macro expansion its hygiene counter. [file] is the file's path,
     * and [directories] gives the directory a file was compiled in. [virtualFile] is the file, where [ProjectModules]
     * holds it. A file of only modules compiles each directly from [DEFMODULE_FAST_PATH]. Where the forms expanded, the
     * file's top is how their run ended, if it did: the error a statement raised, or else where it first stopped.
     */
    internal fun expandFile(
        forms: ElixirAst,
        env: Env,
        level: ElixirLanguageLevel,
        exports: Exports,
        structs: Structs,
        observer: ExpansionObserver = ExpansionObserver.NONE,
        counters: Counters = Counters(),
        file: String? = null,
        directories: CompileDirectories = CompileDirectories.UNKNOWN,
        clauses: ModuleClauses.Lookup = ModuleClauses.Lookup.NONE,
        virtualFile: VirtualFile? = null,
    ): FileExpansion =
        expandFile(forms, env, Run(level, observer, exports, structs, counters, file, directories, clauses, virtualFile))

    /** A file's top-level [forms] expanded from [env] in [run], as the other `expandFile` expands them. */
    internal fun expandFile(forms: ElixirAst, env: Env, run: Run): FileExpansion {
        val level = run.level
        val state = ExState.empty(level)

        val expansion =
            if (DEFMODULE_FAST_PATH.isSufficient(level) && env.module == null && onlyDefmodule(forms)) {
                fastCompile(forms, state, env, run)
            } else {
                slowCompile(forms, state, env, run)
            }
        run.finish()

        return expansion
    }

    /** The whole file expanded, then the modules it queued compiled and its top level run, in turn. */
    private fun slowCompile(forms: ElixirAst, state: ExState, env: Env, run: Run): FileExpansion {
        run.fileStatements.add(forms)

        val (expanded, assumed) = run.assuming { unlessTooDeep({ Expansion.Unported(forms) }) { expand(forms, state, env, run) } }
        run.fileValues.expanded(expanded, assumed)

        val top = expanded.certain(run.fileValues.known)

        if (expanded is Expansion.Error) return FileExpansion(top, emptyList(), endsRun = true)

        val walked = compilePending(run)

        return FileExpansion(if (top is Expansion.Expanded) walked.ended ?: top else top, walked.modules, walked.endsRun)
    }

    /**
     * `elixir_compiler:fast_compile/2` for each of [forms]: each module compiled directly, with no module variables,
     * up to the first whose name isn't ported, where the file stops, unless a module before it ended the file's run.
     */
    private fun fastCompile(forms: ElixirAst, state: ExState, env: Env, run: Run): FileExpansion {
        run.fileStatements.add(forms)

        for (form in defmodules(forms)) {
            val (nameNode, options) = form.arguments!!
            val (name, isAtom) = fastName(nameNode, env, run.level) ?: run {
                // The file stops at the name where its run reaches the `defmodule`.
                run.pending += Pending.Check(form, ordered = true) { Expansion.Unported(nameNode) }
                break
            }
            val contextModules =
                if (FAST_PATH_ADDS_CONTEXT_MODULE.isSufficient(run.level)) listOf(name) + env.contextModules
                else env.contextModules
            val body = ((options as ElixirAst.ListNode).elements.single() as ElixirAst.Tuple).elements[1]
            val moduleEnv = env.copy(module = name, contextModules = contextModules)

            run.queue(Pending.Module(form, name, isAtom, body, moduleEnv, state))
        }

        val walked = compilePending(run)

        return FileExpansion(
            walked.ended ?: Expansion.Expanded(state, env, NODE, ExpandedAst.NotAsked),
            walked.modules,
            walked.endsRun,
        )
    }

    /** `expand_defmodule/2`'s walk: each `defmodule` in [forms], into nested blocks. */
    private fun defmodules(forms: ElixirAst): List<ElixirAst.Call> =
        if (forms is ElixirAst.Block) forms.expressions.flatMap(::defmodules) else listOf(forms as ElixirAst.Call)

    /**
     * `expand_defmodule/2`'s name: an alias as `expand_or_concat` gives it, otherwise `Macro.expand/2`'s, which
     * [env] gives `__MODULE__` alone or leading an alias. With whether it is an atom, or `null` where it isn't ported.
     */
    private fun fastName(nameNode: ElixirAst, env: Env, level: ElixirLanguageLevel): Pair<String, Boolean>? {
        val module = env.module ?: "nil"

        return when {
            nameNode is ElixirAst.Literal.Atom -> nameNode.name to true
            nameNode is ElixirAst.Alias -> {
                val head = nameNode.segments.first()
                val tail = nameNode.segments.drop(1).map { (it as? ElixirAst.Literal.Atom)?.name ?: return null }

                aliasesModule(nameNode, env, level)?.let { it to true }
                    ?: if (isModuleVariable(head)) concat(listOf(module) + tail)?.let { it to true } else null
            }
            isModuleVariable(nameNode) -> module to true
            nameNode is ElixirAst.Literal.Integer -> inspected(Term.Integer(nameNode.value))?.let { it to false }
            nameNode is ElixirAst.Literal.Binary -> inspected(Term.Binary(nameNode.bytes))?.let { it to false }
            else -> null
        }
    }

    private fun isModuleVariable(node: ElixirAst): Boolean =
        isVariable(node) && ((node as ElixirAst.Call).callee as ElixirAst.Literal.Atom).name == "__MODULE__"

    /** [ast] expanded by [body] in place of its clause, with the observer told of it as of any node. */
    internal inline fun observed(ast: ElixirAst, state: ExState, env: Env, run: Run, body: () -> Expansion): Expansion {
        ProgressManager.checkCanceled()
        // A built node shares its source node's origin, so an observer would take it for that node.
        val observed = !ast.meta.built
        if (observed) run.observer.entering(ast, state, env) else run.observer.builtIn(ast, env)

        val expansion = run.supplied(ast, body())

        if (observed) run.observer.left(ast, expansion)

        run.watched(ast, expansion)

        return expansion
    }
}

/**
 * A node of the output [expandQuoted] expands whose value the caller reads after: [value] is the term it expanded to, or
 * `null` where the expansion never reached it or stopped at it, and [expanded] the output's copy of it that was expanded.
 */
internal class Watched(val node: ElixirAst) {
    var value: Term? = null
    var expanded: ElixirAst? = null
}

/**
 * Where a node of a macro's output is written: [origin] in the macro's [file], its source file or the `.beam` whose
 * decompiled mirror holds it, as [ProjectModules] chose it, or in the file the run expands where [file] is `null`.
 */
internal class Place(val file: VirtualFile?, val origin: TextRange)

/**
 * Whether [node], in the expansion of [call]'s macro, is one the expansion built rather than one of [call]'s arguments
 * it passed through: every node a macro's output builds has its call's origin.
 */
internal fun builtBy(node: ElixirAst, call: ElixirAst): Boolean = node.meta.origin == call.meta.origin

/** [block]'s result, and whether code it expanded or ran took a raise not to happen ([Run.assumptions]). */
internal inline fun <T> Run.assuming(block: () -> T): Pair<T, Boolean> {
    val from = assumptions
    val result = block()

    return result to (assumptions > from)
}

/** What a unit the evaluator reached gives the code around it: its code's [value], and whether that code [declined]. */
internal class UnitRun(val value: OtpErlangObject, val declined: Boolean)

/** What every recursive expansion of one [Expander.expand] call shares. */
internal class Run(
    val level: ElixirLanguageLevel,
    val observer: ExpansionObserver,
    exports: Exports,
    val structs: Structs,
    val counters: Counters = Counters(),
    /** The path of the file the run expands, where it is known. */
    val file: String? = null,
    val directories: CompileDirectories = CompileDirectories.UNKNOWN,
    private val clauses: ModuleClauses.Lookup = ModuleClauses.Lookup.NONE,
    /** The file the run expands, where [ProjectModules] holds it. */
    val virtualFile: VirtualFile? = null,
) {
    /** The modules whose exports the run read, in the order it read them. */
    val consulted = mutableListOf<String>()

    /** The references `make_ref/0` has made in the run's evaluations, so each is new. */
    private var refs = 0

    /**
     * How many raises whose inputs aren't known the run's evaluations took not to happen, in a native or a macro's
     * output: Elixir may have raised at each.
     */
    var assumptions = 0

    /**
     * The modules being compiled that calls declined in the module-body code or macro being run were given, any of
     * which those calls may have written, so reads after them there don't know them; `null` where none is being run. A
     * function that earlier code made reads them too, wherever it was made.
     */
    var declinedGiven: Given? = null

    /**
     * How many bodies of a `try` with `catch` or `rescue` are being evaluated around the unit the evaluator runs where it
     * reaches it ([reached]), any of which may catch a raise in the unit's code.
     */
    var catching = 0

    /** The number of a reference no evaluation in the run has made before. */
    fun nextRef(): Int = ++refs

    /** How many expansions in progress asked for the expanded AST of every node below them. */
    var asking = 0

    /** [body], with every node it expands building its expanded AST. */
    inline fun <T> askingAst(body: () -> T): T {
        asking++
        try {
            return body()
        } finally {
            asking--
        }
    }

    /** The nodes being expanded whose value a summary reads, by identity, until they are read. */
    val watching = IdentityHashMap<ElixirAst, Watched>()

    /** [node] was expanded to [expansion], which the watch of it reads. */
    fun watched(node: ElixirAst, expansion: Expansion) {
        watching.remove(node)?.let {
            it.value = (expansion as? Expansion.Expanded)?.value
            it.expanded = node
        }
    }

    /**
     * The nodes being expanded whose value the macro that made them knows, by identity, until they are expanded: a
     * variable it bound to a value only Elixir's evaluation of the output computes.
     */
    val supplying = IdentityHashMap<ElixirAst, Term>()

    /**
     * Each node of an evaluated macro's output whose tuple the macro's clauses built, by identity, to the place of the
     * clauses' node that built it.
     */
    val placedAt = IdentityHashMap<ElixirAst, Place>()

    /**
     * Each argument that stands for an env, which a macro is given as a term rather than as the argument's AST, by
     * identity: the env `eval_callbacks/5` gives a `@before_compile` callback (`Mo:494–495`).
     */
    val givenEnvs = IdentityHashMap<ElixirAst, Env>()

    /**
     * Each node a port built in output whose raises it ports itself, by identity. The evaluator declining code of only
     * such nodes doesn't mean Elixir may have raised there: the port says whether it does.
     */
    val ported: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    /**
     * [copy], a node rebuilt from [original], stands where the original stands: a port built it where one built the
     * original ([ported]), and it has the original's place ([placedAt]).
     */
    fun inherits(original: ElixirAst, copy: ElixirAst) {
        if (original in ported) ported += copy
        placedAt[original]?.let { placedAt[copy] = it }
    }

    /**
     * Each call of a module body whose effect on its module the walk applies itself, by identity. The evaluator
     * declining one doesn't mean it wrote anything else: the effect says what it writes.
     */
    val effects: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    /** Whether every node of [node] is [ported], but a literal, which the evaluator never declines. */
    fun isPorted(node: ElixirAst): Boolean {
        val pending = ArrayDeque(listOf(node))

        while (pending.isNotEmpty()) {
            ProgressManager.checkCanceled()

            val next = pending.removeLast()

            if (next !in ported && next !is ElixirAst.Literal) return false

            pending.addAll(children(next))
        }

        return true
    }

    var injecting: ElixirAst.Call? = null
        private set

    /**
     * [expand] of [call]'s evaluated output, which injects what it defines: [call] is [injecting], unless the output
     * being expanded built it, whose call stays outermost.
     */
    fun <T> expandInjected(call: ElixirAst.Call, expand: () -> T): T {
        val outer = injecting
        if (outer == null || !builtBy(call, outer)) injecting = call

        return try {
            expand()
        } finally {
            injecting = outer
        }
    }

    /** Whether the macro that made [node] knows its value, which it hasn't been expanded to yet. */
    fun isSupplied(node: ElixirAst): Boolean = node in supplying

    /** A macro's output is copied into [copy], which takes the value supplied for [original]. */
    fun carrySupplied(original: ElixirAst, copy: ElixirAst) {
        supplying.remove(original)?.let { supplying[copy] = it }
    }

    /** [expansion] of [node], with its value the one the macro supplied for it, if it did. */
    fun supplied(node: ElixirAst, expansion: Expansion): Expansion {
        val value = supplying.remove(node)

        return if (value != null) expansion.withValue(value) else expansion
    }

    /** What each module the run compiled exports, which Elixir loads once `elixir_module:compile` returns. */
    private val compiled = HashMap<String, ModuleExports>()

    /**
     * What each module exports: one the run compiled, what it defined; one it is compiling, nothing, as Elixir hasn't
     * loaded it; any other, what [exports] gives.
     */
    val exports = Exports { module ->
        compiled[module] ?: if (module in compiling) {
            ModuleExports.Absent
        } else {
            consulted += module
            exports.of(module)
        }
    }

    /**
     * `Module.open?/1` of [module]: whether it is being compiled, or `null` for one the run didn't compile, which another
     * file may be compiling.
     */
    fun isOpen(module: String): Boolean? =
        when (module) {
            in compiling -> true
            in compiled -> false
            else -> null
        }

    /** The struct of each module the run compiled, which Elixir loads once `elixir_module:compile` returns. */
    private val compiledStructs = HashMap<String, ModuleStruct>()

    /**
     * [module]'s struct: one the run is compiling, the record its `defstruct` made, or none before it, and unreadable
     * where the module defines `__struct__/1` itself, which Elixir calls; one it compiled, what it left; any other,
     * what [structs] gives.
     */
    fun structOf(module: String): ModuleStruct {
        val inProgress = compiling[module] ?: return compiledStructs[module] ?: structs.of(module)

        return LoadedModules.recorded(inProgress.struct, inProgress.table)
    }

    /** The clauses of each module the run compiled and loaded. */
    private val loadedClauses = HashMap<String, ModuleClauses>()

    /**
     * [module]'s clauses: for a [local] macro's call in a module it is compiling, those it has so far, as
     * `elixir_def:local_for/5` reads them; one the run loaded, those it left; one it is compiling, none otherwise, as
     * Elixir hasn't loaded it; any other, what [clauses] gives.
     */
    fun clausesOf(module: String, local: Boolean = false): ModuleClauses? {
        if (local) compiling[module]?.let { return ModuleClauses.of(module, it, virtualFile) }
        loadedClauses[module]?.let { return it }
        if (module in compiling) return null

        return if (module in compiled) null else clauses.of(module)
    }

    /**
     * [result]'s module loaded, as Elixir loads a module once it compiles, with its [clauses]. A module whose compile
     * raised loads nothing, and the name answers as it would without it: where its `defmodule` isn't [placed] where
     * Elixir runs it, so that where the project compiles it never ran, or before `elixir_module:compile/6` purges such a
     * module, the one the run loaded under the name before stays; otherwise the name has no module of this run's, and
     * [exports] answers it from the code path.
     */
    fun load(result: ExpansionResult, placed: Boolean, clauses: () -> ModuleClauses) {
        val loadedExports = LoadedModules.exports(result)

        if (loadedExports == ModuleExports.Absent) {
            if (result.module in compiled && (!placed || !FAILED_COMPILE_PURGES_MODULE.isSufficient(level))) return

            compiled.remove(result.module)
            compiledStructs.remove(result.module)
            loadedClauses.remove(result.module)
            observer.loaded(result, loadedExports, ModuleStruct.Absent)
            observer.clausesLoaded(result.module, null)

            return
        }

        val loadedStruct = LoadedModules.struct(result)
        compiledStructs[result.module] = loadedStruct
        compiled[result.module] = loadedExports
        observer.loaded(result, loadedExports, loadedStruct)
        val loaded = if (loadedExports is ModuleExports.Present) clauses() else null

        if (loaded == null) loadedClauses.remove(result.module) else loadedClauses[result.module] = loaded
        observer.clausesLoaded(result.module, loaded)
    }

    /** What each bitstring expanded builds, by node, for the bitstring that nests it. */
    val bitstrings = IdentityHashMap<ElixirAst, BitstringParts>()

    /** The errors Elixir reported and carried on after, in its order. No state restore takes them back. */
    val errors = mutableListOf<Reported>()

    /** The warnings Elixir reported, in its order. No state restore takes them back. */
    val warnings = mutableListOf<Warning>()

    /** How Elixir's own code raised after a reported error, if it did. */
    var crash: Crash? = null

    /** Each module whose body or definitions are being expanded, by name. */
    val compiling = HashMap<String, Compiling>()

    /** The definitions and modules the body being expanded defines, in the order it reached them. */
    var pending = mutableListOf<Pending>()

    /** Each module queued and not yet compiled, by its `defmodule` call's identity, to its body. */
    private val uncompiled = IdentityHashMap<ElixirAst, ElixirAst>()

    /** [module] queued onto [pending]. */
    fun queue(module: Pending.Module) {
        pending += module
        uncompiled[module.node] = module.body
    }

    /** [module]'s body is being compiled. */
    fun startedCompiling(module: Pending.Module) {
        uncompiled.remove(module.node)
    }

    /** The run is over: the observer is told of each module it queued and never compiled. */
    fun finish() {
        for ((defmodule, body) in uncompiled) {
            // A built node shares its source node's origin, so an observer would take it for that node.
            if (!defmodule.meta.built) observer.uncompiled(defmodule, body)
        }
        uncompiled.clear()
    }

    /**
     * Each unit of a statement being run, by its node, to what runs it where the evaluator reaches the node, from the
     * bindings the evaluator has there.
     */
    val reached = IdentityHashMap<ElixirAst, (HashMap<Binding, OtpErlangObject>) -> UnitRun>()

    /**
     * Each `fn` of a module body or the file's top level whose closure the evaluator has called in the run of the
     * statement being reached, by identity: the units its body holds ran where it did, so one the evaluator didn't
     * reach Elixir didn't run either. Each run of a statement has its own, which starts with the `fn`s [running] when it
     * does and gives what it entered to the run around it.
     */
    var entered: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

    /** The `fn`s whose closures the evaluator is running, innermost last. */
    val running = ArrayDeque<ElixirAst>()

    /** How many statement runs are reaching units, one in another: a unit's callback stays until the outermost ends. */
    var reaching = 0

    /**
     * How many declines the evaluator has gone on after, in a loop whose later iterations still run: Elixir may have
     * raised in the iteration that declined, so a unit reached after one may never run.
     */
    var continuedDeclines = 0

    /**
     * What each call, bitstring segment and variable of a module body or the file's top level expanded as, which the
     * walk evaluates as it reaches their statements. A definition's records are its module's, which load with it.
     */
    val bodyDispatches = ModuleClauses.Dispatches()

    /** The escaped block of each `quote` of a module body or the file's top level. */
    val bodyQuotes = ModuleClauses.Quotes()

    /** The file's top-level statements. */
    val fileStatements = Statements(level)

    /** What the file's top-level statements bound so far, and how far what ran is Elixir's. */
    val fileValues = BodyValues(fileStatements)

    /** Where [env]'s code records what it expanded as: a definition's module, or the run for a body. */
    fun dispatchesAt(env: Env): ModuleClauses.Dispatches? =
        if (env.function == null) bodyDispatches else compiling[env.module]?.dispatches

    fun quotesAt(env: Env): ModuleClauses.Quotes? =
        if (env.function == null) bodyQuotes else compiling[env.module]?.quotes

    /** The statements of the body [env] is in: its module's, or the file's. */
    fun statementsAt(env: Env): Statements? =
        if (env.module == null) fileStatements else compiling[env.module]?.statements

    /** A module body's code, or the file's where [module] is `null`, as the clauses the evaluator reads it through. */
    fun bodyClauses(module: String?): ModuleClauses =
        ModuleClauses(module ?: "nil", emptyMap(), ModuleClauses.Source.SOURCE, bodyQuotes, bodyDispatches, virtualFile)

    /**
     * For each local call whose arguments are being expanded, or default argument, innermost last, the local calls in
     * it.
     */
    val callArguments = ArrayDeque<MutableList<LocalCall<ElixirAst>>>()
}

internal inline fun Expansion.then(next: (ExState, Env) -> Expansion): Expansion =
    thenValue { state, env, _ -> next(state, env) }

internal inline fun Expansion.thenValue(next: (ExState, Env, Term) -> Expansion): Expansion =
    thenAst { state, env, value, _ -> next(state, env, value) }

internal inline fun Expansion.thenAst(next: (ExState, Env, Term, ExpandedAst) -> Expansion): Expansion =
    when (this) {
        is Expansion.Expanded -> next(state, env, value, ast)
        is Expansion.Error, is Expansion.Unported, is Expansion.Opaque -> this
    }

/**
 * The stop for a reader that decides by what [value] is, where [value] is an unknown attribute's read: Elixir's answer
 * depends on a value that isn't known here, so the form stops at [at]. `null` where [value] is known.
 */
internal fun unknownStop(value: Term?, at: ElixirAst): Expansion.Unported? =
    if (value == Term.Unexpanded) Expansion.Unported(at) else null

/** [build]'s node where [this] run asks for expanded ASTs. */
internal inline fun Run.ast(build: () -> ElixirAst): ExpandedAst =
    if (asking > 0) ExpandedAst.Built(build()) else ExpandedAst.NotAsked

/** The node of an [ExpandedAst] a run that asks for them built. */
internal val ExpandedAst.node: ElixirAst
    get() = (this as? ExpandedAst.Built)?.node ?: error("an expanded AST the run didn't build")

/** [transform] of the node, where it was built. */
internal inline fun ExpandedAst.map(transform: (ElixirAst) -> ElixirAst): ExpandedAst =
    when (this) {
        is ExpandedAst.Built -> ExpandedAst.Built(transform(node))
        ExpandedAst.NotAsked -> this
    }

/** A list's meta: a list has none of its own. */
internal val LIST_META = Meta(TextRange.EMPTY_RANGE, Meta.Position(0, 0), Meta.Position(0, 0), built = true)

/** [node] with [arguments], built where [this] run asks, in place of its own arguments. */
internal fun Run.call(node: ElixirAst.Call, vararg arguments: ExpandedAst): ExpandedAst =
    ast { ElixirAst.Call(node.meta, node.callee, arguments.map { it.node }, node.context) }

/** The variable [node] read or written at [version]: Elixir adds `version` ahead of its metadata. */
internal fun Run.versioned(node: ElixirAst, version: Int): ExpandedAst =
    ast {
        val call = node as ElixirAst.Call

        ElixirAst.Call(call.meta.ahead(versionEntry(version)), call.callee, null, call.context)
    }

/** `{version, Version}`. */
internal fun versionEntry(version: Int): Meta.Key.Entry = Meta.Key.Entry("version", Meta.Value.Integer(version.toLong()))

/** This metadata with [key] ahead of its keys. */
internal fun Meta.ahead(key: Meta.Key): Meta = Meta(origin, start, end, listOf(key) + keys, built)

/** The node, or [source] where the run didn't build one. */
internal fun ExpandedAst.or(source: ElixirAst): ElixirAst = (this as? ExpandedAst.Built)?.node ?: source

/** The elements of a list node an expansion built. */
internal val ExpandedAst.elements: List<ElixirAst> get() = (node as ElixirAst.ListNode).elements

/** `Macro.escape/1` of [term] at [at]'s position, or `null` where the port can't build it. */
internal fun escaped(term: Term, at: ElixirAst, run: Run): ElixirAst? =
    QuoteEscape(ImportTermEscapeView, AstEscapeBuilder(Synthetic(at.meta)), { literalOf(at, it) }, run.level, run.exports)
        .escape(term)
        .let { (it as? EscapeResult.Built)?.value }

/** This expansion with [value] in place of its own, if it expanded. */
internal fun Expansion.withValue(value: Term): Expansion =
    if (this is Expansion.Expanded) copy(value = value, variable = null) else this

/** This expansion with [ast] in place of its own, if it expanded. */
internal fun Expansion.withAst(ast: ExpandedAst): Expansion = if (this is Expansion.Expanded) copy(ast = ast) else this

/** `?line(Meta)`: the `line` in [meta], or 0. */
internal fun line(meta: Meta): Int = location(meta)?.line ?: 0

/** The line and column in [meta], if it has them. */
internal fun location(meta: Meta): Meta.Position? =
    meta.keys.firstNotNullOfOrNull { (it as? Meta.Key.Location)?.position }

/** The value of a node that expands to an AST node other than a variable or a pin. */
internal val NODE: Term = Term.Node(Term.Node.Kind.OTHER)

/** The value of a node that expands to a variable, which `^` and `rescue ... in` accept. */
internal val VARIABLE_NODE: Term = Term.Node(Term.Node.Kind.VARIABLE)

/** The value of a node other than a boolean that `elixir_utils:returns_boolean/1` holds of. */
internal val BOOLEAN_NODE: Term = Term.Node(Term.Node.Kind.BOOLEAN)

/** The value of a node that expands to `{'<-', _, [_, _]}`, which `for` takes as a generator. */
internal val GENERATOR_NODE: Term = Term.Node(Term.Node.Kind.GENERATOR)

internal val NIL = Term.Atom("nil")

internal val TRUE = Term.Atom("true")

internal val FALSE = Term.Atom("false")

/** `mapfold/4`: [nodes] in order, each from the state and env the one before it left; the value is their values. */
internal inline fun mapfold(
    nodes: List<ElixirAst>,
    state: ExState,
    env: Env,
    run: Run,
    expand: (ElixirAst, ExState, Env) -> Expansion,
): Expansion {
    var accState = state
    var accEnv = env
    val values = ArrayList<Term>(nodes.size)
    var asts: ArrayList<ElixirAst>? = null
    var built = true

    for (node in nodes) {
        ProgressManager.checkCanceled()

        when (val expansion = expand(node, accState, accEnv)) {
            is Expansion.Expanded -> {
                accState = expansion.state
                accEnv = expansion.env
                values.add(expansion.value)

                val ast = expansion.ast

                if (built && ast is ExpandedAst.Built) {
                    (asts ?: ArrayList<ElixirAst>(nodes.size).also { asts = it }).add(ast.node)
                } else {
                    built = false
                }
            }
            is Expansion.Error, is Expansion.Unported, is Expansion.Opaque -> return expansion
        }
    }

    val ast = when {
        !built -> ExpandedAst.NotAsked
        asts == null -> run.ast { ElixirAst.ListNode(LIST_META, emptyList()) }
        else -> ExpandedAst.Built(ElixirAst.ListNode(LIST_META, asts))
    }

    return Expansion.Expanded(accState, accEnv, Term.List(values), ast)
}

/**
 * `elixir_expand:expand_args/3`: outside a pattern, what an argument binds is readable only after the last one. The
 * value is the list of the arguments' values.
 */
internal fun expandArgs(args: List<ElixirAst>, state: ExState, env: Env, run: Run): Expansion =
    expandArgs(args, state, env, run) { arg, s, e -> Expander.expand(arg, s, e, run) }

/** `elixir_expand:expand_args/3`, with [expand] for its `expand/3`. */
internal inline fun expandArgs(
    args: List<ElixirAst>,
    state: ExState,
    env: Env,
    run: Run,
    expand: (ElixirAst, ExState, Env) -> Expansion,
): Expansion =
    when {
        args.size == 1 -> expand(args.single(), state, env).thenAst { s, e, v, a ->
            Expansion.Expanded(s, e, Term.List(listOf(v)), a.map { ElixirAst.ListNode(LIST_META, listOf(it)) })
        }
        env.context == Env.Context.MATCH -> mapfold(args, state, env, run, expand)
        else ->
            argumentScope(state) { scope ->
                mapfold(args, scope, env, run) { arg, s, e -> expandArg(arg, s, state, e, run, expand) }
            }
    }

/**
 * `elixir_expand:expand_list/5`, which takes a `|` as the last element apart. The value is the list, with the `|`'s
 * right side as its tail.
 *
 * @param expand `expand/3` in a pattern, `expand_arg/3` otherwise
 */
internal inline fun expandList(
    elements: List<ElixirAst>,
    state: ExState,
    env: Env,
    run: Run,
    crossinline expand: (ElixirAst, ExState, Env) -> Expansion,
): Expansion =
    mapfold(elements.dropLast(1), state, env, run) { element, s, e -> expand(element, s, e) }.thenAst { s, e, init, initAst ->
        val values = (init as Term.List).elements
        val last = elements.lastOrNull()
        val appended = { lastAst: ExpandedAst ->
            if (initAst is ExpandedAst.Built && lastAst is ExpandedAst.Built) {
                ExpandedAst.Built(ElixirAst.ListNode(LIST_META, initAst.elements + lastAst.node))
            } else {
                ExpandedAst.NotAsked
            }
        }

        when {
            last == null -> Expansion.Expanded(s, e, init, initAst)
            isCall(last, "|", 2) ->
                mapfold((last as ElixirAst.Call).arguments!!, s, e, run) { arg, ss, ee -> expand(arg, ss, ee) }
                    .thenAst { ss, ee, pair, pairAst ->
                        val (head, tail) = (pair as Term.List).elements
                        val cons = pairAst.map { ElixirAst.Call(last.meta, last.callee, (it as ElixirAst.ListNode).elements) }

                        Expansion.Expanded(ss, ee, Term.List(values + head, tail), appended(cons))
                    }
            else -> expand(last, s, e).thenAst { ss, ee, v, a -> Expansion.Expanded(ss, ee, Term.List(values + v), appended(a)) }
        }
    }

/**
 * Expands [body] between `elixir_env:prepare_write/1` and `close_write/2`, so its bindings become readable only after
 * it.
 */
internal inline fun argumentScope(state: ExState, body: (ExState) -> Expansion): Expansion =
    body(state.prepareWrite()).thenAst { s, e, v, a -> Expansion.Expanded(s.closeWrite(state), e, v, a) }

/**
 * `elixir_expand:expand_arg/3`: an argument reads only what [start], the scope's start, could. A literal isn't expanded,
 * so [Expander.observed] never reports it to the watch, and this does.
 */
internal fun expandArg(arg: ElixirAst, acc: ExState, start: ExState, env: Env, run: Run): Expansion =
    expandArg(arg, acc, start, env, run) { a, s, e -> Expander.expand(a, s, e, run) }
        .also { if (arg is ElixirAst.Literal) run.watched(arg, it) }

/** `elixir_expand:expand_arg/3`, with [expand] for its `expand/3`; a pid in a macro's result isn't expanded either. */
internal inline fun expandArg(
    arg: ElixirAst,
    acc: ExState,
    start: ExState,
    env: Env,
    run: Run,
    expand: (ElixirAst, ExState, Env) -> Expansion,
): Expansion =
    when (arg) {
        is ElixirAst.Literal -> Expansion.Expanded(acc, env, literalValue(arg), run.ast { arg })
        is ElixirAst.Value if arg.term is OtpErlangPid -> Expansion.Expanded(acc, env, NODE, run.ast { arg })
        else -> expand(arg, acc.resetRead(start), env)
    }

internal fun literalValue(literal: ElixirAst.Literal): Term =
    when (literal) {
        is ElixirAst.Literal.Atom -> Term.Atom(literal.name)
        is ElixirAst.Literal.Integer -> Term.Integer(literal.value)
        is ElixirAst.Literal.Binary -> Term.Binary(literal.bytes)
        is ElixirAst.Literal.Float -> Term.NonTuple
    }

/** `{name, meta, args}` with [arity] arguments. */
internal fun isCall(node: ElixirAst, name: String, arity: Int): Boolean =
    node is ElixirAst.Call &&
        (node.callee as? ElixirAst.Literal.Atom)?.name == name &&
        node.arguments?.size == arity

/** `{'<<>>', meta, args}`. */
internal fun isBitstring(node: ElixirAst): Boolean = isNamedCall(node, "<<>>")

/** `{'%{}', meta, args}`. */
internal fun isMap(node: ElixirAst): Boolean = isNamedCall(node, "%{}")

/** `{name, meta, args}` with a list of arguments, of any length. */
internal fun isNamedCall(node: ElixirAst, name: String): Boolean =
    node is ElixirAst.Call && (node.callee as? ElixirAst.Literal.Atom)?.name == name && node.arguments != null

/** `{name, meta, context}` with an atom context: a variable, `_` included. */
internal fun isVariable(node: ElixirAst): Boolean =
    node is ElixirAst.Call && node.callee is ElixirAst.Literal.Atom && node.arguments == null

/** `{'_', meta, context}`. */
internal fun isUnderscore(node: ElixirAst): Boolean =
    isVariable(node) && ((node as ElixirAst.Call).callee as ElixirAst.Literal.Atom).name == "_"

/** The arguments of `{'when', meta, args}`, the guard last, if [node] is one. */
internal fun whenArguments(node: ElixirAst): List<ElixirAst>? =
    (node as? ElixirAst.Call)?.takeIf { isNamedCall(it, "when") }?.arguments

/**
 * [node] in the shape its expansion has, as far as a ported clause's check reads it: a block of one expression is that
 * expression, an empty block is `nil`, and an alias is an atom. Every other ported clause keeps its node's shape.
 */
internal fun expandedShape(node: ElixirAst): ElixirAst {
    ProgressManager.checkCanceled()

    return when (node) {
        is ElixirAst.Block ->
            when (node.expressions.size) {
                0 -> ElixirAst.Literal.Atom(node.meta, "nil")
                1 -> expandedShape(node.expressions.single())
                else -> node
            }
        // The name is as written: only `literalShape` reads it, through the aliases.
        is ElixirAst.Alias ->
            node.segments
                .map { (it as? ElixirAst.Literal.Atom)?.name ?: return node }
                .let { concat(it)?.let { name -> ElixirAst.Literal.Atom(node.meta, name) } ?: node }
        else -> node
    }
}
