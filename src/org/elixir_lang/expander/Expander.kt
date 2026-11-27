package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangPid
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.system.OS
import org.elixir_lang.beam.MacroNameArity
import org.elixir_lang.expander.interpreter.ARM_BUDGET
import org.elixir_lang.expander.interpreter.Binding
import org.elixir_lang.expander.interpreter.Declined
import org.elixir_lang.expander.interpreter.Evaluated
import org.elixir_lang.expander.interpreter.Filename
import org.elixir_lang.expander.interpreter.Given
import org.elixir_lang.expander.interpreter.RunningCall
import org.elixir_lang.expander.interpreter.Unknown
import org.elixir_lang.expander.interpreter.UnknownBinary
import org.elixir_lang.expander.interpreter.ValueUse
import org.elixir_lang.expander.interpreter.atom
import org.elixir_lang.expander.interpreter.decline
import org.elixir_lang.expander.interpreter.isUnknown
import org.elixir_lang.expander.interpreter.tuple
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFMODULE_FAST_PATH
import org.elixir_lang.language_level.ElixirLanguageFeature.FAILED_COMPILE_PURGES_MODULE
import org.elixir_lang.language_level.ElixirLanguageFeature.FAST_PATH_ADDS_CONTEXT_MODULE
import org.elixir_lang.language_level.ElixirLanguageFeature.LAZY_MODULE_LOADING
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
     * Elixir would load, [structs] for their structs and [appEnv] for the application environment, up to the first
     * error Elixir raises or the first node that isn't ported. [observer] is told of each node reached, and
     * [counters] gives each macro expansion its hygiene counter. An error Elixir reports and carries on after isn't
     * returned, and a definition or module [ast] defines is never compiled; [expandFile] does both.
     */
    internal fun expand(
        ast: ElixirAst,
        state: ExState,
        env: Env,
        level: ElixirLanguageLevel,
        exports: Exports,
        structs: Structs,
        appEnv: AppEnv,
        observer: ExpansionObserver = ExpansionObserver.NONE,
        counters: Counters = Counters(),
    ): Expansion = expand(ast, state, env, Run(level, observer, exports, structs, appEnv, counters))

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
     * run only where it reaches them. So does a variable, but in a body the walk follows it is a unit, so a value the
     * evaluator doesn't have declines only the variable's, as a unit's decline does, not the code around it.
     */
    private fun queueStatement(node: ElixirAst, expansion: Expansion.Expanded, from: Int, state: ExState, env: Env, run: Run) {
        val statements = run.statementsAt(env)
            ?.takeIf { env.function == null && env.context == Env.Context.NONE && it.runs(node) }
            ?: return
        // A unit of the statement's code, on the statement path or in a body the walk follows, runs where the evaluator
        // reaches it as the statement runs, after the code Elixir runs before it there; any other runs before the
        // statement.
        val queued = run.pending.subList(from, run.pending.size).toList()
        val (inner, before) = queued.partition { statements.runs(it.site) }
        val statement = statements.contains(node)

        when {
            isCall(node, "=", 2) -> run.replacePending(from, before + Pending.Match(node, state, env, statement, inner))
            !statements.isWhole(node) || statements.isRewritten(node) -> Unit
            // A statement that is a unit's own node runs as that unit.
            queued.any { it.site === node } -> Unit
            inner.isNotEmpty() -> run.replacePending(from, before + Pending.Statement(node, state, env, statement, inner))
            expansion.value == VARIABLE_NODE && statement || isKnown(expansion.value) -> statements.inert(node)
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
        appEnv: AppEnv,
        observer: ExpansionObserver = ExpansionObserver.NONE,
        counters: Counters = Counters(),
        file: () -> String? = { null },
        directories: CompileDirectories = CompileDirectories.UNKNOWN,
        clauses: ModuleClauses.Lookup = ModuleClauses.Lookup.NONE,
        virtualFile: VirtualFile? = null,
        os: OS? = null,
        buildTarget: BuildTarget? = null,
        config: ConfigRead? = null,
        files: RunFiles = RunFiles.NONE,
        unregistered: (String) -> Boolean = { false },
    ): FileExpansion =
        expandFile(
            forms,
            Run(level, observer, exports, structs, appEnv, counters, file, directories, clauses, virtualFile, os, buildTarget, config, files, env, unregistered),
        )

    /** A file's top-level [forms] expanded from [run]'s [Run.newEnv], as the other `expandFile` expands them. */
    internal fun expandFile(forms: ElixirAst, run: Run): FileExpansion {
        val env = requireNotNull(run.newEnv) { "a file's run starts from its new env" }
        val level = run.level
        val state = ExState.empty(level)

        // `Code`'s evaluations expand the whole of what they're given, whatever it holds.
        val expansion =
            if (!run.evaluates && DEFMODULE_FAST_PATH.isSufficient(level) && env.module == null && onlyDefmodule(forms)) {
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

        val (expanded, assumed) = run.assuming {
            unlessTooDeep({ Expansion.Unported(forms, NESTS_TOO_DEEPLY) }) { expand(forms, state, env, run) }
        }
        run.fileValues.expanded(expanded, assumed)

        val top = expanded.certain(run.fileValues.expansionCertain)

        // Nothing queued runs, but what a macro created as the file expanded stays: Elixir loaded it before the raise.
        if (expanded is Expansion.Error) return run.fileExpansion(top, endsRun = true)

        val walked = compilePending(run)

        // A statement of a module's body, which this run doesn't walk: its outcome is its expansion's.
        if (run.evaluatedInBody(env)) run.fileValues.outcome(forms, expanded, ordered = true, known = true)

        if (run.config != null || run.isFollowed) {
            val read = run.config
            // A last expression the walk didn't run as a unit, as a literal, is run here for its value.
            val last = lastStatement(forms)
            val value = run.fileValues.ranGave(last, run)
                ?: lastModule(last, run)
                ?: (expanded as? Expansion.Expanded)?.let {
                    read?.muted = true
                    try {
                        run.fileValues.statement(last, it.state, it.env, run)
                    } finally {
                        read?.muted = false
                    }
                }
            run.fileValue = (value as? Evaluated.Value)?.value ?: Unknown
            if (!run.isFollowed) read?.result = run.fileValue
        }

        return run.fileExpansion(if (top is Expansion.Expanded) walked.ended ?: top else top, walked.endsRun)
    }

    /**
     * The value of a `defmodule` that is a followed file's [last] statement, which the walk compiled and doesn't run:
     * `{:module, name, binary, term}` of the module the run loaded last.
     */
    private fun lastModule(last: ElixirAst, run: Run): Evaluated? =
        run.takeIf { it.isFollowed && onlyDefmodule(last) }?.lastLoaded?.let {
            Evaluated.Value(tuple(atom("module"), atom(it), UnknownBinary, Unknown))
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

        return run.fileExpansion(walked.ended ?: Expansion.Expanded(state, env, NODE, ExpandedAst.NotAsked), walked.endsRun)
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
                val tail = nameNode.tailAtomNames() ?: return null

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

/** Where a node of a macro's output is written. */
internal sealed interface Place {
    /** At [origin] in the macro's source [file], as [ProjectModules] chose it, or in the file the run expands where it is `null`. */
    class Written(val file: VirtualFile?, val origin: TextRange) : Place

    /** In a `.beam`'s debug info [clause], which [ProjectModules.placed] finds in the `.beam`'s decompiled mirror. */
    class Compiled(val clause: CompiledClause) : Place
}

/**
 * A clause of [beam]'s debug info, as read at [stamp]: the [index]th of the definition debug info stores as [key].
 */
internal data class CompiledClause(val beam: VirtualFile, val stamp: Long, val key: MacroNameArity, val index: Int)

/**
 * Whether [node], in the expansion of [call]'s macro, is one the expansion built rather than one of [call]'s arguments
 * it passed through: every node a macro's output builds has its call's origin.
 */
internal fun builtBy(node: ElixirAst, call: ElixirAst): Boolean = node.meta.origin == call.meta.origin

/** [block]'s result, and whether code it expanded or ran made an assumption ([Run.assumptions]). */
internal inline fun <T> Run.assuming(block: () -> T): Pair<T, Boolean> {
    val from = assumptions
    val result = block()

    return result to (assumptions > from)
}

/** What a unit the evaluator reached gives the code around it: its code's [value], and whether that code [declined]. */
internal class UnitRun(val value: OtpErlangObject, val declined: Boolean)

/**
 * What of a [Run] the code run later reads, which the code running may change: its config [read] ([ConfigRead.snapshot])
 * and the apps whose env it wrote ([written], [Run.wroteEnvOf]).
 */
internal class RunState(val read: ConfigRead.Snapshot?, val written: WrittenApps) {
    /** Whether [other] is the same state: the same read ([sameRead]) and apps written. */
    fun isAt(other: RunState) = written.isAt(other.written) && sameRead(read, other.read)
}

/**
 * What code is inside, as is another run's code it runs, an imported file's or a closure's that run made: the [catching]
 * `try`s that may catch its raise ([Run.catching]), whether it may not run ([mayNotRun], [Run.mayNotRun]), the
 * [unordered] units stepped unordered around it ([Run.unordered]), and whether code the expander doesn't follow may have
 * run before it ([unfollowed], [Run.mayHaveRunUnfollowed]).
 */
internal data class Around(
    val catching: Int = 0,
    val mayNotRun: Boolean = false,
    val unordered: Int = 0,
    val unfollowed: Boolean = false,
)

/**
 * How the evaluator had a unit where it reached it: whether the unit's value is read ([use]), and whether it went on
 * past code that declined before it ([pastDecline]), which the unit's evaluations start from, as Elixir may have left
 * before the unit.
 */
internal class ReachedAs(val use: ValueUse, val pastDecline: Boolean)

/** What every recursive expansion of one [Expander.expand] call shares. */
internal class Run(
    val level: ElixirLanguageLevel,
    val observer: ExpansionObserver,
    exports: Exports,
    private val structs: Structs,
    val appEnv: AppEnv,
    val counters: Counters = Counters(),
    /** The path of the file the run expands, `null` where it isn't known; read through here, never held. */
    val file: () -> String? = { null },
    val directories: CompileDirectories = CompileDirectories.UNKNOWN,
    private val clauses: ModuleClauses.Lookup = ModuleClauses.Lookup.NONE,
    /** The file the run expands, where [ProjectModules] holds it. */
    val virtualFile: VirtualFile? = null,
    /** The OS the file's SDK runs as, where it is known. */
    val os: OS? = null,
    /** The build the file is compiled in, `null` where the run has none. */
    val buildTarget: BuildTarget? = null,
    /** The config read this run's file is evaluated in, `null` where it is evaluated on its own. */
    val config: ConfigRead? = null,
    /** The project's files the run reaches by path, which [Run.files] hands out. */
    files: RunFiles = RunFiles.NONE,
    /** The env the file's forms are expanded from, `null` where the run expands no file. */
    val newEnv: Env? = null,
    /**
     * Whether nothing is registered under a name as the build starts compiling the file, `false` where that isn't known;
     * read through [Run.unregistered].
     */
    unregistered: (String) -> Boolean = { false },
    /** The run that followed this one's file or string with `Code`, `null` for a file not followed. */
    private val parent: Run? = null,
    /** Whether the run is `Code`'s evaluation of a file or string, which Elixir expands whole, never by the fast path. */
    val evaluates: Boolean = false,
) {
    /** Each macro argument's term and node. */
    val origins = Origins()

    /** The modules whose exports the run read, in the order it read them. */
    val consulted = mutableListOf<String>()

    /**
     * How many answers the run's evaluations assumed, in a native or a macro's output, each of which Elixir may not have
     * given: a raise whose inputs aren't known taken not to happen, or an answer that holds only where what it reads
     * stays as read.
     */
    var assumptions = 0

    /** What the run shares with what it follows: a [config]'s read's, else the outermost run's. */
    val shared: RunShared = config?.shared ?: parent?.shared ?: RunShared()

    /**
     * Records an evaluation's write of [app]'s env: its [key] as [write], or any key where [key] is `null` or isn't
     * known, and every app's where [app] isn't known. A term that isn't an atom names no app or key a read names. Outside
     * a [config] read, later reads give the write ([envWritten]); where it may be written any number of times or never
     * ([joinsOnly]), the key is taken out.
     */
    fun wroteEnv(app: OtpErlangObject, key: OtpErlangObject? = null, write: EnvWrite = EnvWrite.Unknown) {
        val name = when {
            isUnknown(app) -> null
            app is OtpErlangAtom -> app.atomValue()
            else -> return
        }

        shared.written.add(name)

        if (config != null) return

        when {
            name == null || key == null || isUnknown(key) -> shared.envWrites.takeOut(name)
            key is OtpErlangAtom -> shared.envWrites[name, key] = if (joinsOnly) EnvWrite.Unknown else write
        }
    }

    /**
     * What a write made [app]'s [key], which a read gives, `null` where no write reached it. Under a [config] read, and
     * where the run's writes may have been made any number of times or never ([joinsOnly]), a read doesn't give them: a
     * key of an app written is [EnvWrite.Unknown] ([wroteEnvOf]).
     */
    fun envWritten(app: String, key: OtpErlangAtom): EnvWrite? =
        if (config == null && !joinsOnly) shared.envWrites[app, key] else EnvWrite.Unknown.takeIf { wroteEnvOf(app) }

    /** The run's state as the code run later reads it ([RunState]). */
    fun state() = RunState(config?.snapshot(), shared.written.copy())

    /** Puts the run back to [state], as code run again from there starts. */
    fun restore(state: RunState) {
        state.read?.let { config?.restore(it) }
        shared.written.restore(state.written)
    }

    /** Where code run again from a state doesn't return, keeps the apps [ended] wrote written: code past it may read them. */
    fun keepWritten(ended: List<RunState>) = ended.forEach { shared.written.addAll(it.written) }

    /**
     * The states where code that started at [from] may have ended, joined: [returned], or [raised] where it raised, which
     * a `try` around it may have [caught]. The read joins as [ConfigRead.joined] joins it, and the apps written are those
     * any of them wrote.
     */
    fun joinedState(from: RunState, returned: List<RunState>, raised: List<RunState> = emptyList(), caught: Boolean = false): RunState {
        val read = config
        val first = returned.firstOrNull()?.read
        val joined = if (read == null || raised.isEmpty() && first != null && returned.all { sameRead(it.read, first) }) {
            first
        } else {
            read.joined(from.read!!, returned.map { it.read!! }, raised.map { it.read!! }, caught)
        }
        val written = from.written.copy().also { apps -> (returned + raised).forEach { apps.addAll(it.written) } }

        return RunState(joined, written)
    }

    /**
     * Whether an evaluation in the run, or under its [config] in any of the read's files, wrote [app]'s env, or, `null`,
     * that of any app, so its value isn't [appEnv]'s.
     */
    fun wroteEnvOf(app: String?): Boolean = app in shared.written

    /**
     * The modules being compiled that calls declined in the module-body code or macro being run were given, any of
     * which those calls may have written, so reads after them there don't know them; `null` where none is being run. A
     * function that earlier code made reads them too, wherever it was made.
     */
    var declinedGiven: Given? = null

    /**
     * How many bodies of a `try` with `catch` or `rescue` are being evaluated, in this run, around the import of its
     * file ([ConfigRead.around]) or around another run's call of a function it made, any of which may catch a raise in
     * the code running now, whichever evaluation, or `fn`, it is of.
     */
    var catching = 0

    /** How the evaluator had the unit it runs where it reaches it ([reached]), `null` outside one. */
    var reachedAs: ReachedAs? = null

    /** The atoms the run's code has seen, which `binary_to_existing_atom` finds: the VM's, whichever unit's code made one. */
    val atoms = HashSet<String>()

    /** The atoms made in code that may not run ([mayNotRun]), which may not exist past it. */
    val atomsThatMayExist = HashSet<String>()

    /** How many arms that may not be taken are running ([mayNotRun]), the import of its file's ([ConfigRead.around]) among them. */
    var speculating = 0

    /** Whether the body of the `try` being evaluated may have raised over a value that isn't known. */
    var raisedOverUnknown = false

    /** Whether an enclosing `try` body, or `else`, may have raised before the code now running. */
    var pastRaise = false

    /**
     * Whether the code running may not run in Elixir: an arm taken over a value that isn't known, or code past a raise a
     * `try` may catch. Only the config such code writes is joined.
     */
    val mayNotRun get() = speculating > 0 || raisedOverUnknown || pastRaise

    /** How many units stepped unordered the code running is in. */
    var unordered = 0

    /** Whether the code running is in a unit stepped unordered ([unordered]), which may run any number of times. */
    val steppedUnordered get() = unordered > 0

    /** What the code running is inside ([Around]). */
    fun around() = Around(catching, mayNotRun, unordered, mayHaveRunUnfollowed)

    /** How many of the [Around]s the code running is inside have code the expander doesn't follow run before them. */
    private var unfollowedAround = 0

    /**
     * The code that runs next runs inside [around], as an imported file's or a closure's this run made is inside what its
     * import or call is; what it gives undoes that.
     */
    fun enter(around: Around): () -> Unit {
        val outer = catching

        catching = around.catching
        if (around.mayNotRun) speculating++
        unordered += around.unordered
        if (around.unfollowed) unfollowedAround++

        return {
            catching = outer
            if (around.mayNotRun) speculating--
            unordered -= around.unordered
            if (around.unfollowed) unfollowedAround--
        }
    }

    init {
        config?.around?.let(::enter)
    }

    /**
     * Whether a value the runs share ([shared]) that the code running writes may be written any number of times, or
     * never: the code may not run ([mayNotRun]), or is stepped unordered ([steppedUnordered]). Only such a value's join
     * holds.
     */
    val joinsOnly get() = mayNotRun || steppedUnordered

    /** The arms that may not be taken the run has run: under a [config], those its read's files have run. */
    private val arms = config?.arms ?: ArmsTaken()

    /**
     * Counts an arm that may not be taken, declining past [ARM_BUDGET] arms across the run, or across a config read's
     * files where it reads one.
     */
    fun takeArm() {
        if (++arms.count > ARM_BUDGET) throw Declined("more arms over values not known than a run takes")
    }

    /**
     * The calls running over a value that isn't known in code that may not run, innermost last: a call repeating one
     * answers unknown.
     */
    val runningOverUnknown = ArrayList<RunningCall>()

    /**
     * Declines on [what], a unit, a module, an effect or a unit's raise, where the code running may not run
     * ([mayNotRun]): nothing joins it. Where it [ends] that code, as a raise does, so does the decline.
     */
    fun unjoined(what: String, ends: Boolean = false) {
        if (mayNotRun) throw Declined("$what in code that may not run").apply { if (ends) end(null) }
    }

    /**
     * Declines on [what], which writes a run-wide value, where the code running may not run, or may run any number of
     * times ([joinsOnly]). Each `%s` in [what] names the next of [terms].
     */
    fun unjoinedOrRepeated(what: String, vararg terms: OtpErlangObject) {
        if (joinsOnly) decline("$what in code that may not run, or may run any number of times", *terms)
    }

    /**
     * Whether code the expander doesn't follow ran in this run, or in another whose code it went on from
     * ([unfollowedRanBefore]); once set it stays. What the run can't see reads [mayHaveRunUnfollowed].
     */
    var unfollowedRan = false
        private set

    /**
     * Whether code the expander doesn't follow ran in the run ([unfollowedRan]), before what the code running is inside
     * ([Around.unfollowed]), in the run `Code` followed it from, or, for a run followed from none, in the config read its
     * build runs first ([RunFiles.readRanUnfollowed]).
     */
    val mayHaveRunUnfollowed: Boolean
        get() = unfollowedRan || unfollowedAround > 0 || parent?.mayHaveRunUnfollowed ?: reachedFiles.readRanUnfollowed()

    /**
     * The project's files the run reaches by path: none at any path once code the expander doesn't follow may have run
     * ([mayHaveRunUnfollowed]), which may have written any of them.
     */
    val files: RunFiles get() = if (mayHaveRunUnfollowed) unfollowedFiles else reachedFiles

    private val reachedFiles = files

    /** [reachedFiles] with nothing known at a path. */
    private val unfollowedFiles: RunFiles by lazy(LazyThreadSafetyMode.NONE) {
        object : RunFiles by reachedFiles {
            override fun entry(path: String): FileEntry? = null
            override fun imported(path: String, read: ConfigRead): FileExpansion? = null
            override fun read(path: String): ByteArray? = null
            override fun listing(path: String): DirectoryListing? = null
            override fun followed(path: String): FollowedFile? = null
        }
    }

    /**
     * The file's expansion, ending at [top], with the modules the run compiled, whether code it doesn't follow ran and
     * whether it made an assumption.
     */
    fun fileExpansion(top: Expansion, endsRun: Boolean): FileExpansion =
        FileExpansion(top, compiledInto, endsRun, unfollowedRan, assumptions > 0)

    /**
     * Code the expander doesn't follow ran in another run that shares this one's [shared] values, before what this run
     * does from then on: one this run's code followed or imported, or one that made a closure this run called.
     * What it left in the files isn't known here either; the cells and keys it may have written were taken out where
     * it ran ([declinedCodeRan]).
     */
    fun unfollowedRanBefore() {
        unfollowedRan = true
    }

    /** Code the expander doesn't follow ran, or may have: a decline the walk went on past, a stop, a macro or callback it lost. */
    fun unfollowedCodeRan() {
        declinedCodeRan()
        config?.unitDeclined()
    }

    /**
     * Code declined, and the evaluation or the walk went on past it, so what it ran isn't known, nor the cells of the
     * [counterArrays][RunShared.counterArrays] it may have written, nor the keys of the apps whose env the run wrote
     * ([envWrites][RunShared.envWrites]). What that means for a read's table is for the
     * unit's recording to say, as only that knows whether the unit is ported.
     */
    fun declinedCodeRan() {
        unfollowedRan = true
        shared.counterArrays.values.forEach { it.cells.fill(null) }
        shared.envWrites.takeOutWritten()
    }

    /**
     * The directory Elixir compiles the run's [file] in now, a config read's project's in a read, which `file:get_cwd/0`,
     * `Path.relative_to_cwd/1` and `filename:absname/1` read, `null` where it isn't known: where the run's [file] has no
     * path on the SDK's machine, [directories] doesn't have it, or code the expander doesn't follow may have changed it
     * ([mayHaveRunUnfollowed]). On Windows its drive letter is lowercase, as OTP gives it.
     */
    fun workingDirectory(): String? = if (mayHaveRunUnfollowed) null else compileDirectory

    /**
     * Whether nothing is registered under [name] now, which `erlang:whereis/1` reads: what the build starts with, until
     * code the expander doesn't follow may have run ([mayHaveRunUnfollowed]), which may have registered it.
     */
    fun unregistered(name: String): Boolean = !mayHaveRunUnfollowed && unregisteredAtStart(name)

    private val unregisteredAtStart = unregistered

    /** The directory [workingDirectory] gives until [mayHaveRunUnfollowed], read once: none of what it reads changes in a run. */
    private val compileDirectory by lazy(LazyThreadSafetyMode.NONE) {
        file()?.let { config?.directory ?: directories.of(it) }
            ?.let { Filename.lowercaseDrive(it, os) }
    }

    /**
     * [path] made absolute as `filename:absname/1` makes it on [os], before `join/1` tidies it: an absolute path as it
     * is, and any other from the directory Elixir compiles the run's file in ([workingDirectory]), its volume alone for
     * a root with no drive on Windows (`absname_vr/3`); `null` where that directory isn't known, or the path is
     * relative to another drive's directory.
     */
    fun absolute(path: String, os: OS): String? {
        val type = Filename.pathtype(path, os)
        if (type == Filename.PathType.ABSOLUTE) return path

        val directory = workingDirectory() ?: return null
        if (type == Filename.PathType.RELATIVE) return "$directory/$path"

        // Only Windows has a path relative to a volume.
        val volume = Filename.split(directory, OS.Windows).first()

        return when {
            Filename.isSeparator(path[0].code, OS.Windows) -> volume + path.substring(1)
            path[0].lowercaseChar() == volume[0] -> "$directory/${path.substring(2)}"
            else -> null
        }
    }

    /**
     * `Path.relative_to_cwd/1` of [file], its SDK running as [os], from the directory Elixir compiles the run's file in
     * ([workingDirectory]), which a file a definition moved to needn't be; `null` where [file] is absolute and that
     * isn't known.
     */
    fun relativeToCwd(file: String, os: OS): String? = relativeTo(file, ::workingDirectory, os, level)

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
     * Each macro call given its arguments through `stop_generated/1`, by identity, to the metadata that carries the
     * mark: the arguments it marks ([stopGeneratedMarks]), and what a summary rebuilt from them keeping their keys. The
     * call's `generated: true` stops at a node of its output with one.
     */
    val stopsGenerated = IdentityHashMap<ElixirAst.Call, MutableSet<Meta>>()

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
     * loaded it; any other, what [exports] gives, unless code the expander doesn't follow may have created it
     * ([maybeCreated]).
     */
    val exports = Exports { module ->
        compiled[module] ?: if (module in compiling) {
            ModuleExports.Absent
        } else {
            consulted += module
            maybeCreated(exports.of(module), ModuleExports.Absent, ModuleExports.Unreadable)
        }
    }

    /**
     * [answer], for a module the run neither compiled nor is compiling, or [unreadable] where it is [absent] and code the
     * expander doesn't follow may have run ([mayHaveRunUnfollowed]), which may have created it with `Module.create/3`.
     * Such code is taken not to redefine or unload a module whose answer is already present.
     */
    private fun <T> maybeCreated(answer: T, absent: T, unreadable: T): T =
        if (answer == absent && mayHaveRunUnfollowed) unreadable else answer

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
     * what [structs] gives, unless code the expander doesn't follow may have created it ([maybeCreated]).
     */
    fun structOf(module: String): ModuleStruct {
        val inProgress = compiling[module]
            ?: return compiledStructs[module] ?: maybeCreated(structs.of(module), ModuleStruct.Absent, ModuleStruct.Unreadable)

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

            forget(result.module)
            loadOrder.remove(result.module)
            observer.loaded(result, loadedExports, ModuleStruct.Absent)
            observer.clausesLoaded(result.module, null)

            return
        }

        loadedLast(result.module, result)
        ownLoads += result.module
        val loadedStruct = LoadedModules.struct(result)
        compiledStructs[result.module] = loadedStruct
        compiled[result.module] = loadedExports
        observer.loaded(result, loadedExports, loadedStruct)
        val loaded = if (loadedExports is ModuleExports.Present) clauses() else null

        if (loaded == null) loadedClauses.remove(result.module) else loadedClauses[result.module] = loaded
        observer.clausesLoaded(result.module, loaded)
    }

    /** The modules the run loaded, in the order Elixir loads them. */
    private val loadOrder = LinkedHashMap<String, ExpansionResult>()

    /**
     * The modules `Code` gives as the run's file's, as `elixir_compiler` lists what a file compiled: each the run loaded,
     * once for each load, in turn, with those of the code it evaluated, whose loads Elixir lists as the file's own.
     */
    private val ownLoads = ArrayList<String>()

    /** [module], loaded as [result]: a module loaded again moves to the end, past what an ensure reached under it. */
    private fun loadedLast(module: String, result: ExpansionResult) {
        loadOrder.remove(module)
        loadOrder[module] = result
        ensured.remove(module)
    }

    /** [module] as no module of the run's. */
    private fun forget(module: String) {
        compiled.remove(module)
        compiledStructs.remove(module)
        loadedClauses.remove(module)
    }

    /** The module the run loaded last, `null` where it loaded none. */
    val lastLoaded: String? get() = loadOrder.keys.lastOrNull()

    /** Whether `Code` followed the run's file or string. */
    val isFollowed: Boolean get() = parent != null

    /** The value of the file's last expression, where the run is a read's or a follow's. */
    var fileValue: OtpErlangObject = Unknown

    /** The env a file `Code` follows starts from: the [newEnv] of the file the outermost run expands. */
    val emptyEnv: Env? get() = parent?.emptyEnv ?: newEnv

    /** The files being run, this one's innermost, so that a follow of one of them ends the cycle. */
    val following: Set<String> get() = (parent?.following ?: emptySet()) + setOfNotNull(file())

    /**
     * Whether nothing runs beside the run, so which of a file's callers runs it first is known: a config read, or a
     * script outside test-source content, whose scripts `mix test` loads in parallel. A followed run is as its caller is.
     */
    val sequential: Boolean =
        parent?.sequential ?: (config != null || buildTarget?.script == true && buildTarget.mixEnv != "test")

    /**
     * [forms], `Code`'s follow of [file] from [env], run in a run of their own, which then merges into this one
     * ([adopt]). A follow in code that may not run, or may run any number of times ([joinsOnly]), declines, as the
     * modules it loads and the file it marks required would join nothing. [observed], a node of [forms], is the one whose
     * expansion the result keeps.
     */
    fun follow(
        forms: ElixirAst,
        env: Env,
        file: String?,
        virtualFile: VirtualFile?,
        evaluates: Boolean,
        observed: ElixirAst? = null,
    ): Followed {
        unjoinedOrRepeated("Code's follow")

        var observedExpansion: Expansion? = null
        val observer =
            if (observed == null) {
                ExpansionObserver.NONE
            } else {
                object : ExpansionObserver {
                    override fun entering(node: ElixirAst, state: ExState, env: Env) {}

                    override fun left(node: ElixirAst, expansion: Expansion) {
                        if (node === observed) observedExpansion = expansion
                    }
                }
            }
        val caller = this
        val child = Run(
            level,
            observer,
            caller.exports,
            caller::structOf,
            appEnv,
            counters,
            { file },
            { _ -> caller.workingDirectory() },
            { caller.clausesOf(it) },
            virtualFile,
            os,
            buildTarget,
            config,
            reachedFiles,
            env,
            unregisteredAtStart,
            parent = caller,
            evaluates = evaluates,
        )

        val expansion = Expander.expandFile(forms, child)

        adopt(child)

        return Followed(
            child.ownLoads.toList(),
            child.fileValue,
            child.bound(expansion.top),
            observedExpansion,
            expansion.top,
            child.fileValues.certainAt(null) && expansion.modules.all { it.certain },
        )
    }

    /**
     * What [child] loaded, as this run's: its modules, in the order it loaded them, the answers it assumed, and whether
     * code it doesn't follow ran.
     */
    private fun adopt(child: Run) {
        for ((module, result) in child.loadOrder) {
            val exports = child.compiled[module] ?: continue
            val followed = FollowedModule(module, exports, child.compiledStructs[module] ?: ModuleStruct.Unreadable)

            loadedLast(module, result)
            // A run alone compiles beside nothing.
            see(FollowedLoad(followed, child.loadedClauses[module]), if (sequential) null else files.copyOf(module), result)
        }
        child.ensured.values.forEach(::ensuredLoaded)
        if (child.evaluates) ownLoads += child.ownLoads
        assumptions += child.assumptions
        // The arrays are shared, so the child's own decline cleared them.
        if (child.unfollowedRan) unfollowedRanBefore()
    }

    /**
     * Each module the run's `ensure_compiled`s reached since the run last loaded it, by name, with its clauses, which the
     * run that follows this one reaches too.
     */
    private val ensured = LinkedHashMap<String, FollowedLoad>()

    /**
     * [loaded], which the follows of a module's file this run ensures is compiled loaded. That file compiled in the build,
     * beside the index's copy, however this run is compiled.
     */
    fun ensuredLoaded(loaded: FollowedLoad) {
        ensured[loaded.module.name] = loaded
        see(loaded, files.copyOf(loaded.module.name), null)
    }

    /**
     * [loaded], as the run sees it ([seenAfterFollow]) beside the index's [copy], which the build may compile too. Where it
     * sees the index's, the run's own goes. The observer is told it loaded as [result], for a module of this run's follow,
     * else that the run reached it.
     */
    private fun see(loaded: FollowedLoad, copy: Boolean?, result: ExpansionResult?) {
        val seen = seenAfterFollow(loaded, copy, level, FollowedBy.FILE) ?: return forget(loaded.module.name)
        val name = seen.module.name

        compiled[name] = seen.module.exports
        compiledStructs[name] = seen.module.struct
        if (seen.clauses == null) loadedClauses.remove(name) else loadedClauses[name] = seen.clauses
        config?.followed(seen)
        if (result == null) observer.reached(seen.module) else observer.loaded(result, seen.module.exports, seen.module.struct)
        observer.clausesLoaded(name, seen.clauses)
    }

    /** Each variable [top] left bound, with its value, unknown where the run doesn't have it. */
    private fun bound(top: Expansion): List<Pair<Variable, OtpErlangObject>> =
        (top as? Expansion.Expanded)?.state?.read?.map { (variable, version) ->
            variable to (fileValues.bindings[Binding(version)] ?: Unknown)
        }.orEmpty()

    /** What each bitstring expanded builds, by node, for the bitstring that nests it. */
    val bitstrings = IdentityHashMap<ElixirAst, BitstringParts>()

    /**
     * The errors Elixir reported and carried on after, in its order, while the module that reported them compiles: they
     * leave with it, as its own. No state restore takes them back.
     */
    val errors = mutableListOf<Reported>()

    /** The warnings Elixir reported, in its order, as [errors] are kept. No state restore takes them back. */
    val warnings = mutableListOf<Warning>()

    /** How Elixir's own code raised after a reported error, if it did. */
    var crash: Crash? = null

    /** Each module whose body or definitions are being expanded, by name. */
    val compiling = HashMap<String, Compiling>()

    /**
     * The modules of the module being compiled, or of the file's top level, in the order Elixir compiles them, where
     * `Module.create/3` puts the module it compiles.
     */
    var compiledInto = mutableListOf<ExpansionResult>()

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
     * Each unit of an `fn` a match bound and kept, by its node, which runs where a later statement calls the `fn`: in
     * [reached] for every statement run while the body holding it compiles.
     */
    val deferred = IdentityHashMap<ElixirAst, Deferred>()

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

    /** Where each tuple of a module body's or the file's top level's quotes and dispatch records was written. */
    val bodyPlaces = ModuleClauses.Places()

    /** The file's top-level statements. */
    val fileStatements = Statements(level)

    /** What the file's top-level statements bound so far, and how far what ran is Elixir's. */
    val fileValues = BodyValues(fileStatements, module = null)

    /** Where [env]'s code records what it expanded as: a definition's module, or the run for a body. */
    fun dispatchesAt(env: Env): ModuleClauses.Dispatches? =
        if (inBody(env)) bodyDispatches else compiling[env.module]?.dispatches

    fun quotesAt(env: Env): ModuleClauses.Quotes? =
        if (inBody(env)) bodyQuotes else compiling[env.module]?.quotes

    fun placesAt(env: Env): ModuleClauses.Places? =
        if (inBody(env)) bodyPlaces else compiling[env.module]?.places

    /** The nodes [carryPlaces] has read for each record, each once. */
    private val carried = IdentityHashMap<ModuleClauses.Places, MutableSet<ElixirAst>>()

    /** Each 3-tuple node under [root] that [placedAt] places, into [places], as [carry] carries it. */
    fun carryPlaces(places: ModuleClauses.Places?, root: ElixirAst?) {
        if (places == null || root == null || placedAt.isEmpty()) return

        eachNodeOnce(listOf(root), carried.getOrPut(places) { Collections.newSetFromMap(IdentityHashMap()) }) {
            carry(places, it, it)
        }
    }

    /** Each tuple of [escaped] into [places], at the place [placedAt] gives the node it was built from, as [carry] carries it. */
    fun carryPlaces(places: ModuleClauses.Places?, escaped: Map<ElixirAst, ElixirAst>) {
        if (places == null || placedAt.isEmpty()) return

        escaped.forEach { (tuple, source) -> carry(places, tuple, source) }
    }

    /**
     * [node], where it builds a 3-tuple, at the place of [from] in [places], a place in the file the run expands
     * resolved to that file, as the run that reads the places may expand another.
     */
    private fun carry(places: ModuleClauses.Places, node: ElixirAst, from: ElixirAst) {
        if (!buildsThreeTuple(node)) return
        val place = placedAt[from] ?: return

        places.put(node, if (place is Place.Written && place.file == null) Place.Written(virtualFile, place.origin) else place)
    }

    /** Whether [env]'s code is a body's: outside a definition, or [evaluatedInBody]. */
    private fun inBody(env: Env): Boolean = env.function == null || evaluatedInBody(env)

    /**
     * Whether [env]'s code is `Code`'s evaluation in the env of a module this run doesn't compile, so a statement of that
     * module's body.
     */
    fun evaluatedInBody(env: Env): Boolean = isFollowed && env.module != null && env.module !in compiling

    /** The statements of the body [env] is in: its module's, or the file's. */
    fun statementsAt(env: Env): Statements? =
        if (env.module == null) fileStatements else compiling[env.module]?.statements

    /** A module body's code, or the file's where [module] is `null`, as the clauses the evaluator reads it through. */
    fun bodyClauses(module: String?): ModuleClauses =
        ModuleClauses(module ?: "nil", emptyMap(), ModuleClauses.Source.SOURCE, bodyQuotes, bodyDispatches, bodyPlaces, virtualFile)

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
internal fun Meta.ahead(key: Meta.Key): Meta = withKeys(listOf(key) + keys)

/** This metadata with its keys [keys]. */
internal fun Meta.withKeys(keys: List<Meta.Key>): Meta = Meta(origin, start, end, keys, built)

/** The node, or [source] where the run didn't build one. */
internal fun ExpandedAst.or(source: ElixirAst): ElixirAst = (this as? ExpandedAst.Built)?.node ?: source

/** The elements of a list node an expansion built. */
internal val ExpandedAst.elements: List<ElixirAst> get() = (node as ElixirAst.ListNode).elements

/** `Macro.escape/1` of [term] at [at]'s position, or `null` where the port can't build it. */
internal fun escaped(term: Term, at: ElixirAst, run: Run): ElixirAst? = escaped(ImportTermEscapeView, term, at, run) { literalOf(at, it) }

/**
 * `Macro.escape/1` of [input], which [view] reads, at [at]'s position, [insert] putting in a node it holds; `null`
 * where the port can't build it.
 */
internal fun <I> escaped(view: EscapeView<I>, input: I, at: ElixirAst, run: Run, insert: (I) -> ElixirAst?): ElixirAst? =
    QuoteEscape(view, AstEscapeBuilder(Synthetic(at.meta)), insert, run.level, run.exports)
        .escape(input)
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

/** `elixir_expand:expand_arg/3`, with [expand] for its `expand/3`, which it skips for what it takes as it is. */
internal inline fun expandArg(
    arg: ElixirAst,
    acc: ExState,
    start: ExState,
    env: Env,
    run: Run,
    expand: (ElixirAst, ExState, Env) -> Expansion,
): Expansion =
    when {
        !isTakenAsIs(arg) -> expand(arg, acc.resetRead(start), env)
        arg is ElixirAst.Literal -> Expansion.Expanded(acc, env, literalValue(arg), run.ast { arg })
        else -> Expansion.Expanded(acc, env, NODE, run.ast { arg })
    }

/** Whether `elixir_expand:expand_arg/3` takes [arg] as it is, unexpanded: a literal, or a pid in a macro's result. */
internal fun isTakenAsIs(arg: ElixirAst): Boolean = arg is ElixirAst.Literal || arg is ElixirAst.Value && arg.term is OtpErlangPid

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

/** Whether evaluating [node] builds a 3-tuple: a tuple of three elements, or `{}` of three arguments. */
internal fun buildsThreeTuple(node: ElixirAst): Boolean =
    node is ElixirAst.Tuple && node.elements.size == 3 || isCall(node, "{}", 3)

/** The last expression of a file's top-level [forms], whose value the file gives. */
private fun lastStatement(forms: ElixirAst): ElixirAst = blockExpressions(forms)?.lastOrNull() ?: forms

/** The expressions of [node], a block, `null` where it isn't one. */
internal fun blockExpressions(node: ElixirAst): List<ElixirAst>? =
    when {
        node is ElixirAst.Block -> node.expressions
        isNamedCall(node, "__block__") -> (node as ElixirAst.Call).arguments
        else -> null
    }

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
 * expression, an empty block is `nil`, and an alias is an atom.
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
        is ElixirAst.Alias -> node.atomNames()?.let { concat(it) }?.let { ElixirAst.Literal.Atom(node.meta, it) } ?: node
        else -> node
    }
}

/** A module `Code`'s follow loaded: what it exports and its struct. */
internal class FollowedModule(val name: String, val exports: ModuleExports, val struct: ModuleStruct)

/** A module a follow loaded, with its [clauses], `null` where they aren't known. */
internal class FollowedLoad(val module: FollowedModule, val clauses: ModuleClauses?) {
    /** The module, its exports, struct and clauses not known. */
    fun unknown() = FollowedLoad(FollowedModule(module.name, ModuleExports.Unreadable, ModuleStruct.Unreadable), null)
}

/** Whose follow loaded a module: the file's own run, or the config read its build runs after. */
internal enum class FollowedBy { FILE, READ }

/**
 * The one site that says what a run sees of [loaded], which a follow [by] loaded, `null` where it sees the index's
 * copy. [compiled] is `null` where the index holds no copy or nothing compiles beside the run: the followed module.
 * `false`, a source copy or several the build compiles too, races the follow, so the module isn't known. `true`, a
 * compiled copy on the code path: where Elixir loads lazily ([LAZY_MODULE_LOADING]) a module is loaded once, when code
 * first asks for it, so a file's follow, compiling beside the build, leaves the copy, and a read's, which ran before the
 * build, stays. Before, a module is loaded as it compiles: a file's follow, the last to compile it, is seen, and a read's
 * only where the dependency doesn't compile again in the build, which isn't known.
 */
internal fun seenAfterFollow(loaded: FollowedLoad, compiled: Boolean?, level: ElixirLanguageLevel, by: FollowedBy): FollowedLoad? =
    when (compiled) {
        null -> loaded
        false -> loaded.unknown()
        true -> {
            val lazy = LAZY_MODULE_LOADING.isSufficient(level)

            when (by) {
                FollowedBy.FILE -> if (lazy) null else loaded
                FollowedBy.READ -> if (lazy) loaded else loaded.unknown()
            }
        }
    }

/**
 * What `Code`'s follow left: the [modules] it gives as the file's, those it loaded and those of the code it evaluated;
 * the [value] of its last expression and the variables [bound] at its end; the expansion of the node it [observed]; how
 * it ended, [top]; and whether what it ran is Elixir's, [certain].
 */
internal class Followed(
    val modules: List<String>,
    val value: OtpErlangObject,
    val bound: List<Pair<Variable, OtpErlangObject>>,
    val observed: Expansion?,
    val top: Expansion,
    val certain: Boolean,
)
