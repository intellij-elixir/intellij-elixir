package org.elixir_lang.expander.interpreter

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangBitstr
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangExternalFun
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.expander.Compiling
import org.elixir_lang.expander.DefinitionTable
import org.elixir_lang.expander.Dispatch
import org.elixir_lang.expander.Effect
import org.elixir_lang.expander.Env
import org.elixir_lang.expander.ExState
import org.elixir_lang.expander.Linify
import org.elixir_lang.expander.LinifyClause
import org.elixir_lang.expander.LinifyRight
import org.elixir_lang.expander.MacroExpanded
import org.elixir_lang.expander.ModuleClauses
import org.elixir_lang.expander.ModuleClauses.Dispatched
import org.elixir_lang.expander.ModuleEffects
import org.elixir_lang.expander.ModuleExports
import org.elixir_lang.expander.Place
import org.elixir_lang.expander.RescueHead
import org.elixir_lang.expander.Run
import org.elixir_lang.expander.Summary
import org.elixir_lang.expander.Warning
import org.elixir_lang.expander.children
import org.elixir_lang.expander.compilingKnown
import org.elixir_lang.expander.envField
import org.elixir_lang.expander.envFields
import org.elixir_lang.expander.forOptions
import org.elixir_lang.expander.givenAt
import org.elixir_lang.expander.isBitstringGenerator
import org.elixir_lang.expander.isCall
import org.elixir_lang.expander.isListGenerator
import org.elixir_lang.expander.isMap
import org.elixir_lang.expander.isNamedCall
import org.elixir_lang.expander.isUnderscore
import org.elixir_lang.expander.isVariable
import org.elixir_lang.expander.isVariableNamed
import org.elixir_lang.expander.keyOf
import org.elixir_lang.expander.lineOf
import org.elixir_lang.expander.literalOf
import org.elixir_lang.expander.macroExpand
import org.elixir_lang.expander.macroExpandOnce
import org.elixir_lang.expander.metaValue
import org.elixir_lang.expander.orGuards
import org.elixir_lang.expander.rescueHead
import org.elixir_lang.expander.splitOptions
import org.elixir_lang.expander.unfollowed
import org.elixir_lang.expander.unlessTooDeep
import org.elixir_lang.expander.unlessTooDeepToRun
import org.elixir_lang.expander.whenArguments
import org.elixir_lang.expander.withOptions
import org.elixir_lang.language_level.ElixirLanguageFeature.FIELD_OF_NON_MAP_RAISES_BADKEY
import org.elixir_lang.language_level.ElixirLanguageFeature.FN_CLAUSE_ERROR_IS_FUNCTION_CLAUSE
import org.elixir_lang.language_level.ElixirLanguageFeature.GENERATOR_RIGHT_SIDE_SCOPED
import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_ARGUMENTS_STOP_GENERATED
import org.elixir_lang.language_level.ElixirLanguageFeature.MAP_UPDATE_RAISES_WHERE_DISCARDED
import org.elixir_lang.language_level.ElixirLanguageFeature.FIELD_OF_NON_MAP_RAISES_BADMAP
import org.elixir_lang.language_level.ElixirLanguageFeature.RESCUED_ERROR_MESSAGE_FROM_ERROR_INFO
import org.elixir_lang.language_level.ElixirLanguageFeature.RESCUE_EXCEPTION_KEY_PRESENT
import org.elixir_lang.language_level.ElixirLanguageFeature.RESCUE_LIST_STACKTRACE_FOR_ANY
import org.elixir_lang.language_level.ElixirLanguageFeature.STRUCT_UPDATE_IS_MAP_UPDATE
import org.elixir_lang.language_level.ElixirLanguageFeature.WITH_ELSE_CLAUSE_ERROR
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.lowering.ParserOptions
import org.elixir_lang.psi.Import.Term
import java.util.Collections
import java.util.IdentityHashMap

/** A call of a macro with no summary, answered by evaluating the macro's clauses. */
internal object Interpreter {
    /**
     * The summary of [node], which dispatches as [dispatch], from its macro's clauses, or `null` where it declines.
     *
     * @param stopGenerated whether the macro is given its arguments through `stop_generated/1`
     */
    fun summary(
        dispatch: Dispatch,
        node: ElixirAst.Call,
        state: ExState,
        env: Env,
        run: Run,
        stopGenerated: Boolean,
    ): Summary? {
        val output = declining(run) {
            try {
                run.unlessTooDeepToRun({ return null }) {
                    Evaluation(node, state, env, run).output(dispatch, node.arguments.orEmpty(), stopGenerated)
                }
            } catch (_: Declined) {
                // The macro's code ran where the expander lost it.
                run.unfollowed()
                return null
            }
        }

        return object : Summary.Rewrite() {
            override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run) = output
        }
    }

    /**
     * [node], code of a module body or of the file's top level, run where the body runs it, from the variables [values]
     * holds, which it binds into. Code that nests too deeply for the thread's stack [declinesTooDeep], or its overflow
     * escapes to the walk, which stops at the unit running it.
     */
    fun body(
        node: ElixirAst,
        values: HashMap<Binding, OtpErlangObject>,
        state: ExState,
        env: Env,
        run: Run,
        declinesTooDeep: Boolean,
        use: ValueUse = ValueUse.READ,
    ): Evaluated =
        evaluated(node, state, env, run, declinesTooDeep) { evaluation ->
            evaluation.using(use) { evaluation.eval(node, Frame(run.bodyClauses(env.module), values)) }
        }

    /**
     * [pattern], of a match in a module body or the file's top level whose right side declined holding [holds]: each
     * variable it binds, binding into [values], holds them too.
     */
    fun bindDeclined(pattern: ElixirAst, holds: List<OtpErlangObject>, values: HashMap<Binding, OtpErlangObject>, env: Env, run: Run) {
        Frame(run.bodyClauses(env.module), values).bindEach(pattern, unknownOf(holds))
    }

    /**
     * [pattern], of the match [node] in a module body or the file's top level, matched against [value], binding into
     * [values].
     */
    fun bind(
        pattern: ElixirAst,
        value: OtpErlangObject,
        node: ElixirAst,
        values: HashMap<Binding, OtpErlangObject>,
        state: ExState,
        env: Env,
        run: Run,
    ): Evaluated =
        evaluated(node, state, env, run) { evaluation ->
            evaluation.bind(pattern, value, Frame(run.bodyClauses(env.module), values))
        }

    /**
     * `elixir_quote:[function]/n` of [args], the run-time helper the escape of a definition at [node] calls for a splice
     * or an unquoted call name.
     */
    fun quoteHelper(function: String, args: List<OtpErlangObject>, node: ElixirAst, state: ExState, env: Env, run: Run): Evaluated =
        evaluated(node, state, env, run) { evaluation -> evaluation.remote(atom("elixir_quote"), function, args) }

    /**
     * `module:function(env, kind, name, args, guards, body)`, an `@on_definition` callback of the definition at [node]
     * that [env]'s function names, given its head's [args] and [guards] and its [body].
     */
    fun onDefinition(
        module: String,
        function: String,
        kind: String,
        args: List<ElixirAst>,
        guards: List<ElixirAst>,
        body: ElixirAst?,
        node: ElixirAst,
        env: Env,
        run: Run,
    ): Evaluated =
        evaluated(node, ExState.empty(run.level), env, run) { evaluation ->
            evaluation.onDefinition(module, function, kind, args, guards, body)
        }

    /** [value] of [node]'s evaluation, which [declinesTooDeep] where it overflows the thread's stack, else lets the overflow escape. */
    private fun evaluated(
        node: ElixirAst,
        state: ExState,
        env: Env,
        run: Run,
        declinesTooDeep: Boolean = true,
        value: (Evaluation) -> OtpErlangObject,
    ): Evaluated =
        declining(run) { evaluatedIn(Evaluation(node, state, env, run, run.catching), declinesTooDeep, value) }

    /** [block], recording what the calls that decline in it are given ([Run.declinedGiven]) unless an outer one does. */
    private inline fun <T> declining(run: Run, block: () -> T): T {
        val outermost = run.declinedGiven == null
        if (outermost) run.declinedGiven = Given()

        try {
            return block()
        } finally {
            if (outermost) run.declinedGiven = null
        }
    }

    private fun evaluatedIn(evaluation: Evaluation, declinesTooDeep: Boolean, value: (Evaluation) -> OtpErlangObject): Evaluated {
        return try {
            if (declinesTooDeep) {
                // Where the stack overflows, which calls were given what isn't known.
                unlessTooDeep({
                    evaluation.run.directoryNoLongerKnown()

                    Evaluated.Declined(Given().apply { all() })
                }) {
                    Evaluated.Value(value(evaluation))
                }
            } else {
                Evaluated.Value(value(evaluation))
            }
        } catch (declined: Declined) {
            evaluation.run.directoryNoLongerKnown()

            Evaluated.Declined(declined.given, declined.holds, declined)
        } catch (thrown: Thrown) {
            val exception = try {
                thrown.takeIf { it.kind == "error" }?.let(evaluation::exceptionModule)
            } catch (_: Declined) {
                null
            }

            Evaluated.Raised(exception)
        }
    }
}

/** What running a module body's code gives. */
internal sealed interface Evaluated {
    data class Value(val value: OtpErlangObject) : Evaluated

    /** It raises [exception], or throws, exits or raises an exception that isn't known where that is `null`. */
    data class Raised(val exception: String?) : Evaluated

    /**
     * The evaluator declined it, in calls [given] the modules being compiled they may have written. The value it didn't
     * give may hold what [holds] does ([Declined.holds][org.elixir_lang.expander.interpreter.Declined.holds]).
     *
     * @property declined the decline as the evaluator threw it, where it did: code after it may have [Declined.ended] it
     */
    class Declined(
        val given: Given,
        val holds: List<OtpErlangObject> = emptyList(),
        val declined: org.elixir_lang.expander.interpreter.Declined? = null,
    ) : Evaluated
}

/**
 * A variable as a [Frame] binds it: the version its expansion gave it, which `elixir_erl_var` keys it by alone, using its
 * name only to spell a new Erlang variable.
 */
internal data class Binding(val version: Int)

/**
 * The variables a pattern binds, held until all of it matches. [fresh] where its construct binds them afresh, as an
 * Erlang fun head and generator do, rather than matching a version already bound.
 */
internal class PatternBindings(val fresh: Boolean) {
    val vars = HashMap<Binding, OtpErlangObject>()
}

/** The bindings of one clause of [module]'s, and the stacktrace a `rescue` or `catch` body reads. */
internal class Frame(
    val module: ModuleClauses,
    val vars: HashMap<Binding, OtpErlangObject> = HashMap(),
    val stacktrace: OtpErlangObject? = null,
    val guard: Boolean = false,
) {
    val source get() = module.source == ModuleClauses.Source.SOURCE

    /**
     * [node]'s binding, at the version its expansion gave it: from `Dbgi`, the `version:` in its metadata; from source,
     * the version its expansion recorded. `null` where it has none.
     */
    fun binding(node: ElixirAst): Binding? {
        val version = if (source) {
            module.dispatches.versionOf(node)
        } else {
            (metaValue(node.meta, "version") as? Meta.Value.Integer)?.value?.toInt()
        }

        return version?.let(::Binding)
    }

    /**
     * Binds each variable [pattern] binds, but those already bound, to [value], a value that isn't known: none where it
     * is [Unknown], which came from no module being compiled.
     */
    fun bindEach(pattern: ElixirAst, value: UnknownValue) {
        if (value === Unknown) return

        val pending = ArrayDeque(listOf(pattern))

        while (pending.isNotEmpty()) {
            ProgressManager.checkCanceled()

            val node = pending.removeLast()

            when {
                isVariable(node) -> if (!isUnderscore(node)) binding(node)?.let { vars.putIfAbsent(it, value) }
                isCall(node, "^", 1) -> {}
                else -> pending.addAll(children(node))
            }
        }
    }

    /**
     * [node] in the form `translate/3` receives it: from source, a macro's output in place of its call, and a literal
     * value its expansion injected, such as an attribute's, in place of its call.
     */
    fun expanded(node: ElixirAst): ElixirAst {
        ProgressManager.checkCanceled()

        return when (val dispatched = if (source) module.dispatches.of(node) else null) {
            is Dispatched.Macro -> expanded(dispatched.output)
            is Dispatched.Value -> literalOf(node, dispatched.value) ?: node
            else -> node
        }
    }

    fun copy(stacktrace: OtpErlangObject? = this.stacktrace) = Frame(module, HashMap(vars), stacktrace, guard)

    /** This frame's bindings, read as a guard reads them. */
    fun guarded() = Frame(module, vars, stacktrace, true)
}

/**
 * Whether the code being evaluated has its value read, as the Erlang compiler sees it, which before
 * [MAP_UPDATE_RAISES_WHERE_DISCARDED] drops a map update whose value is discarded.
 */
internal enum class ValueUse {
    /** Something reads the value: an argument, a function's result, or what a value that is read holds. */
    READ,

    /** Nothing reads the value: an expression before a block's last, or what a value that is discarded holds. */
    DISCARDED,

    /** Whether anything reads the value isn't known: a variable binds it, or a form in discarded code holds it. */
    UNKNOWN,
    ;

    /** The use of [expression], one of a block's with this use whose last is [last]: each before the last is discarded. */
    fun ofExpression(expression: ElixirAst, last: ElixirAst?): ValueUse = if (expression === last) this else DISCARDED

    /**
     * The use of what [node] is given where [node]'s own use is this: a list's, a tuple's, a map's and a match's right
     * side's are its own, but a discarded match binds a variable whose reads aren't known, and what any other form is
     * given is read where its value is.
     */
    fun ofPartsOf(node: ElixirAst): ValueUse =
        when {
            isCall(node, "=", 2) -> if (this == DISCARDED && !isUnderscore((node as ElixirAst.Call).arguments!![0])) UNKNOWN else this
            node is ElixirAst.ListNode || node is ElixirAst.Tuple || isNamedCall(node, "{}") || isMap(node) -> this
            this == READ -> READ
            else -> UNKNOWN
        }
}

/** An evaluation of code at [call]: a macro's clauses for its call, or a module body's own code. */
internal class Evaluation(
    val call: ElixirAst,
    private val state: ExState,
    private val env: Env,
    val run: Run,
    /**
     * How many bodies of a `try` with `catch` or `rescue` are being evaluated, any of which may catch a raise: this
     * evaluation's, and those around the unit it runs ([Run.catching]).
     */
    private var catching: Int = 0,
) {
    /** Each term made from an argument's node, so the output keeps the nodes the macro passed through. */
    private val nodes = IdentityHashMap<OtpErlangObject, ElixirAst>()

    /** Each `{form, meta, args}` node the clauses built, by identity, to the place of the node that built it. */
    private val places = IdentityHashMap<OtpErlangObject, Place>()

    /**
     * Each metadata entry, by identity, to the place of the first node built around it, and that node's build order: an
     * argument's, first, to its node's.
     */
    private val metaPlaces = IdentityHashMap<OtpErlangObject, IndexedValue<Place>>()

    /** The `{M, F, Arity, []}` of each call being evaluated, innermost first. */
    private val stack = ArrayDeque<OtpErlangTuple>()

    /** The atoms the evaluation has seen, which `binary_to_existing_atom` finds. */
    val atoms = HashSet<String>()

    val natives = Natives(this)

    /** Whether the value of the code being evaluated is read. */
    private var use = ValueUse.READ

    /** [block], evaluated as code whose value has [use]. */
    internal inline fun <T> using(use: ValueUse, block: () -> T): T {
        val outer = this.use
        this.use = use

        try {
            return block()
        } finally {
            this.use = outer
        }
    }

    /** The module whose code is evaluated, its body's or the macro's caller's, or `null` at the file's top level. */
    val module: String? get() = env.module

    /**
     * [module], being compiled, where its table and attributes are Elixir's here: as the walk has them
     * ([compilingKnown]), and where no call declined in the code being run may have written it since
     * ([Run.declinedGiven]).
     */
    internal fun compilingKnown(module: String): Compiling? =
        run.compilingKnown(module)?.takeUnless { run.declinedGiven?.contains(module) == true }

    /** [declined], whose call [gives] the modules being compiled it may have written, which reads then don't know. */
    private inline fun gave(declined: Declined, gives: Given.() -> Unit): Declined {
        declined.given.gives()
        declined.given.attributes.forEach { (module, names) -> run.compiling[module]?.attributes?.markUnknown(*names.toTypedArray()) }
        run.declinedGiven?.add(declined.given)

        return declined
    }

    private val bits = Bits(this)

    /** Each env's term, so the env a callback is given, when it equals its caller's, is `__CALLER__`. */
    private val envs = HashMap<Env, OtpErlangObject>()

    /** `__CALLER__`, one term, so `Macro.expand/2` knows the env it is given. */
    private val caller by lazy { envOf(env) }

    /** Records an `IO.warn` of [message] as the warning of the call being evaluated. */
    fun warn(message: String, stacktrace: OtpErlangObject?) {
        run.warnings += Warning.Emitted(call, message, stacktrace)
    }

    /**
     * The output of the call, which dispatches as [dispatch], given [arguments].
     *
     * @param stopGenerated whether the macro is given its arguments through `stop_generated/1`
     */
    fun output(dispatch: Dispatch, arguments: List<ElixirAst>, stopGenerated: Boolean): Summary.Output {
        val module = run.clausesOf(dispatch.receiver, local = dispatch.kind == Dispatch.Kind.LOCAL_MACRO)
            ?: decline("no clauses of ${dispatch.receiver}")
        val definition = module.definitions[NameArity(dispatch.name, dispatch.arity)]
            ?.takeIf { it.kind.macro }
            ?: decline("${dispatch.receiver} has no macro ${dispatch.name}/${dispatch.arity}")
        val marks = stopGenerated && MACRO_ARGUMENTS_STOP_GENERATED.isSufficient(run.level)
        val args = arguments.map { arg ->
            run.givenEnvs[arg]?.let(::envOf) ?: termOf(arg).let { if (marks) stopGenerated(it, arg) else it }
        }
        val function = ModuleExports.macroExport(NameArity(dispatch.name, args.size))
        val linifier = Linify(dispatch.receiver, run.level)
        val result = try {
            invoke(module, definition, function.name, args, function.arity).also { linify(it, linifier) }
        } catch (thrown: Thrown) {
            if (thrown.kind != "error") decline("the body ${thrown.kind}s")

            return Summary.Output.Raised(exceptionModule(thrown))
        }

        return Summary.Output.Built(
            codeOf(result, call.meta.origin, values = true, known = nodes::get) { term, node ->
                placeOf(term)?.let { run.placedAt[node] = it }
            } ?: decline("the result isn't quoted or is too large to copy"),
            injects = true,
        )
    }

    /**
     * Raises where `elixir_quote:linify_with_context_counter/3` raises on [term], the macro's result, before
     * `elixir_dispatch:expand_quoted/7` expands it: at the head [linifier] chooses, each `lists` call it makes on a
     * node's metadata, and its comprehension over a list. What linify gives back is `linifyWithContextCounter`'s.
     */
    private fun linify(term: OtpErlangObject, linifier: Linify) {
        ProgressManager.checkCanceled()

        when (term) {
            is OtpErlangTuple if term.arity() == 3 -> {
                // The clause is chosen by all three.
                val (left, meta, right) = term.elements().map(::seen)
                val shape = when {
                    isNonEmptyList(right) -> LinifyRight.NonEmptyList
                    right is OtpErlangAtom -> LinifyRight.Atom(right.atomValue())
                    else -> LinifyRight.Other
                }

                val clause = linifier.clause((left as? OtpErlangAtom)?.atomValue(), isList(meta), shape)

                if (clause != LinifyClause.ELSE) {
                    if (linifier.counted(clause) { keyfind("context", meta) == tuple(CONTEXT, atom(it)) }) {
                        keymember("counter", meta)
                    }
                    linify(left, meta, right, linifier)
                }
            }
            is OtpErlangTuple if term.arity() == 2 -> term.elements().forEach { linify(it, linifier) }
            is OtpErlangList -> {
                term.elements().forEach { linify(it, linifier) }
                term.lastTail?.let { error(tuple(atom("bad_generator"), it)) }
            }
        }
    }

    /** `do_tuple_linify`: [left], then [meta] as the line is written into it, then [right]. */
    private fun linify(left: OtpErlangObject, meta: OtpErlangObject, right: OtpErlangObject, linifier: Linify) {
        if (linifier.readsStopGenerated) keyfind("stop_generated", meta)
        linify(left, linifier)
        if (lineOf(call.meta) != 0) keymember("line", meta)
        linify(right, linifier)
    }

    private fun keyfind(key: String, meta: OtpErlangObject) =
        natives.call("lists", "keyfind", listOf(atom(key), ONE, meta))

    private fun keymember(key: String, meta: OtpErlangObject) {
        natives.call("lists", "keymember", listOf(atom(key), ONE, meta))
    }

    /** The module of the exception Elixir raises for [thrown], an `error`. */
    fun exceptionModule(thrown: Thrown): String {
        val exception = try {
            normalize(thrown.reason, thrown.stacktrace)
        } catch (_: Thrown) {
            decline("Exception.normalize/3 raised")
        }

        return ((exception as? OtpErlangMap)?.get(STRUCT) as? OtpErlangAtom)?.atomValue()
            ?: decline("Exception.normalize/3 gave %s", exception)
    }

    private fun normalize(reason: OtpErlangObject, stacktrace: OtpErlangObject): OtpErlangObject =
        remote(atom("Elixir.Exception"), "normalize", listOf(atom("error"), reason, stacktrace))

    /** `elixir_dispatch:stop_generated/1` of [term], [arg]'s: a call's meta gains `stop_generated: true`. */
    private fun stopGenerated(term: OtpErlangObject, arg: ElixirAst): OtpErlangObject {
        val meta = (term as? OtpErlangTuple)?.takeIf { it.arity() == 3 }?.elementAt(1) as? OtpErlangList ?: return term

        return tuple(term.elementAt(0), cons(listOf(tuple(atom("stop_generated"), TRUE)), meta), term.elementAt(2))
            .also { nodes[it] = arg }
    }

    /**
     * `Macro.expand/2`, or with [once] `Macro.expand_once/2`, of [ast] in [env], expanded where the call is: in
     * `__CALLER__`, in `__CALLER__` with only its `:function` changed, or in `Macro.Env.to_match/1` of `__CALLER__`.
     * Any other env declines.
     */
    fun macroExpand(ast: OtpErlangObject, env: OtpErlangObject, once: Boolean): OtpErlangObject {
        val (given, expandState) = expansionEnv(env) ?: decline("Macro.expand/2 in an env other than __CALLER__")
        val expanding = givenAt(given, lineAt(given), run.level)
        val node = codeOf(ast, call.meta.origin, values = true, known = nodes::get)
            ?: decline("Macro.expand/2 of a term it can't read or copy")
        val expanded =
            if (once) macroExpandOnce(node, expandState, expanding, run) else macroExpand(node, expandState, expanding, run)

        return when (expanded) {
            // A node that didn't expand is the term given, metadata the node doesn't hold included.
            is MacroExpanded.Node -> if (expanded.expanded) termOf(expanded.node) else ast
            MacroExpanded.Dir -> UnknownBinary
            is MacroExpanded.Stopped -> decline("Macro.expand/2 stops")
        }
    }

    /**
     * The env [given] stands for where it is `__CALLER__` on every key but `:function`, which is `{name, arity}` or
     * `nil`: the call's, in that function. `null` where it is any other term.
     */
    private fun withFunction(given: OtpErlangObject): Env? {
        val map = seen(given) as? OtpErlangMap ?: return null
        val caller = caller as? OtpErlangMap ?: return null
        val key = atom("function")

        if (map.arity() != caller.arity()) return null

        for ((field, value) in entries(caller)) {
            val held = map.get(field) ?: return null

            if (field != key && held !== value && !equal(held, value, true, run.level)) return null
        }

        val function = when (val value = seen(map.get(key)!!)) {
            NIL -> null
            is OtpErlangTuple -> {
                val name = (value.elementAt(0) as? OtpErlangAtom)?.takeIf { value.arity() == 2 } ?: return null
                val arity = (seen(value.elementAt(1)) as? OtpErlangLong)?.takeIf { it.signum() >= 0 && it.bitLength() < 32 } ?: return null

                NameArity(name.atomValue(), arity.intValue())
            }
            else -> return null
        }

        return env.copy(function = function)
    }

    /** `run_on_definition_callbacks/8`'s call of `module:function/6` (`elixir_def.erl:255–262`). */
    fun onDefinition(
        module: String,
        function: String,
        kind: String,
        args: List<ElixirAst>,
        guards: List<ElixirAst>,
        body: ElixirAst?,
    ): OtpErlangObject =
        remote(
            atom(module),
            function,
            listOf(
                envOf(env),
                atom(kind),
                atom(env.function!!.name),
                list(args.map(::termOf)),
                list(guards.map(::termOf)),
                body?.let(::termOf) ?: NIL,
            ),
        )

    /** [node] as the term a macro is given, each of its terms remembering the node it came from. */
    private fun termOf(node: ElixirAst): OtpErlangObject =
        node.toOtp(ParserOptions.DEFAULT) { each, term ->
            when (each) {
                is ElixirAst.Placeholder -> decline("an argument holds a placeholder")
                is ElixirAst.Literal.Atom -> atoms += each.name
                else -> {}
            }

            nodes[term] = each
            val place = run.placedAt[each] ?: Place(null, each.meta.origin)
            metaOf(term).forEach { metaPlaces.putIfAbsent(it, IndexedValue(0, place)) }
        }

    /** [definition] of [module]'s, called as [function] with [args]; [arity] is its stacktrace entry's. */
    fun invoke(
        module: ModuleClauses,
        definition: ModuleClauses.Definition,
        function: String,
        args: List<OtpErlangObject>,
        arity: Int = args.size,
    ): OtpErlangObject {
        ProgressManager.checkCanceled()

        val clauses = definition.clauses ?: decline("${module.module}.$function/${args.size}'s clauses aren't known")

        stack.addFirst(frame(module.module, function, integer(arity.toLong())))
        try {
            val arms = Arms { soleArm(clauses.filter { it.args.size == args.size }.map { it.body }, Frame(module)) }

            for (clause in clauses) {
                if (clause.args.size != args.size) continue

                val frame = Frame(module)

                val body = clause.body

                if (arms.tested(body, frame) { matches(clause.args, args, frame) && guards(clause.guards, frame) }) {
                    // A function's result is read, as the compiler sees its body, wherever it is called.
                    return using(ValueUse.READ) {
                        arms.taken(body ?: decline("$function/${args.size} matches a clause that stopped expanding"), frame)
                    }
                }
            }

            arms.none()
        } finally {
            stack.removeFirst()
        }

        error(atom("function_clause"), frame(module.module, function, list(args)))
    }

    /** Raises [reason] as an `error`, with [top] above the calls being evaluated. */
    fun error(reason: OtpErlangObject, top: OtpErlangTuple? = null): Nothing =
        throw Thrown("error", reason, list(listOfNotNull(top) + stack))

    /**
     * Raises [reason] as `error/2` and `error/3` do: the raising function's frame takes [args] in place of its arity,
     * where they are a list, and [errorInfo] at the end of its location (`erts_build_mfa_item`). Raised from a module
     * body, whose frame is the compiler's, the frame is [Unknown].
     */
    fun error(reason: OtpErlangObject, args: OtpErlangObject, errorInfo: OtpErlangMap?): Nothing {
        val (module, function, arity, location) = stack.firstOrNull()?.elements()
            ?: throw Thrown("error", reason, list(listOf(Unknown)))
        val top = OtpErlangTuple(
            arrayOf(
                module,
                function,
                if (isList(seen(args))) args else arity,
                errorInfo?.let { list((location as OtpErlangList).elements().toList() + tuple(atom("error_info"), it)) }
                    ?: location,
            ),
        )

        throw Thrown("error", reason, list(listOf(top) + stack.drop(1)))
    }

    /** Raises [reason] as [kind], with [stacktrace], or else the calls being evaluated. */
    fun raiseAs(kind: String, reason: OtpErlangObject, stacktrace: OtpErlangObject? = null): Nothing =
        throw Thrown(kind, reason, stacktrace ?: list(stack.toList()))

    /** A raise whose inputs aren't known is taken not to happen ([mayRaise]): Elixir may have raised there ([assume]). */
    fun assumeNoRaise() {
        mayRaise()
        assume()
    }

    /**
     * Code that may raise ran. Under a `try` that may catch the raise ([catching]), the build goes on either way, so it
     * declines.
     */
    fun mayRaise() {
        if (catching > 0) decline("a raise a `try` may catch")
    }

    /** An answer that holds only where what it reads stays as read (`Run.assumptions`). */
    fun assume() {
        run.assumptions++
    }

    /** Raises [module]'s exception, its struct with [fields] put. */
    fun raise(module: String, vararg fields: Pair<String, OtpErlangObject>): Nothing {
        val struct = remote(atom(module), "__struct__", emptyList()) as? OtpErlangMap ?: decline("$module has no struct")

        error(mapOf(entries(struct) + fields.map { (key, value) -> atom(key) to value }, run.level))
    }

    /**
     * [expr] read as a guard reads it in [frame], `null` where it raises, which fails its guard or its pattern: a raise
     * after a decline ([Declined.ended]) included, as it raises whatever the decline gave.
     */
    internal fun guardValue(expr: ElixirAst, frame: Frame): OtpErlangObject? =
        try {
            eval(expr, frame.guarded())
        } catch (_: Thrown) {
            null
        } catch (declined: Declined) {
            if (declined.ended) null else throw declined
        }

    internal fun eval(node: ElixirAst, frame: Frame): OtpErlangObject {
        ProgressManager.checkCanceled()

        // A unit of the statement being run, which the walk runs where the evaluator reaches it.
        run.reached[node]?.let { unit ->
            val outer = run.catching
            val outerUse = run.reachedUse
            run.catching = catching
            run.reachedUse = use

            val reached = try {
                unit(frame.vars)
            } finally {
                run.catching = outer
                run.reachedUse = outerUse
            }
            if (reached.declined) mayRaise()

            return reached.value
        }

        return when (node) {
            is ElixirAst.Literal.Atom -> atom(node.name).also { atoms += node.name }
            is ElixirAst.Literal.Integer -> integer(node.value)
            // The lowering reads a float past a double's range, which Elixir's tokenizer refuses, as infinite.
            is ElixirAst.Literal.Float ->
                OtpErlangDouble(node.value.takeIf(Double::isFinite) ?: decline("a float past a double's range"))
            is ElixirAst.Literal.Binary -> OtpErlangBinary(node.bytes)
            is ElixirAst.ListNode -> using(use.ofPartsOf(node)) { evalList(node.elements, frame) }
            is ElixirAst.Tuple ->
                using(use.ofPartsOf(node)) { placed(OtpErlangTuple(parts(node.elements, frame).toTypedArray()), node, frame) }
            is ElixirAst.Block -> block(node.expressions, frame)
            is ElixirAst.Alias -> if (frame.source) value(node, frame) else decline("an alias in debug info")
            is ElixirAst.Placeholder -> decline("a placeholder")
            // A function's arguments are read wherever it is called ([operands]).
            is ElixirAst.Call -> using(use.ofPartsOf(node)) { evalCall(node, frame) }
            is ElixirAst.Value -> node.term
        }
    }

    /** [tuple], which [node] of [frame]'s clauses built, placed at [node]. */
    private fun placed(tuple: OtpErlangTuple, node: ElixirAst, frame: Frame): OtpErlangTuple {
        frame.module.file?.takeIf { tuple.arity() == 3 }?.let { file ->
            val place = Place(file, node.meta.origin)
            places[tuple] = place
            metaOf(tuple).forEach { metaPlaces.putIfAbsent(it, IndexedValue(places.size, place)) }
        }

        return tuple
    }

    /**
     * Where [term] was written: where its metadata was first built into a node, else where it was built, so a node the
     * macro rebuilds, or whose metadata it edits, keeps the place its metadata was written at.
     */
    private fun placeOf(term: OtpErlangObject): Place? =
        metaOf(term).mapNotNull(metaPlaces::get).minByOrNull { it.index }?.value ?: places[term]

    /** The metadata entries of [term], when it is a `{form, meta, args}` node. */
    private fun metaOf(term: OtpErlangObject): List<OtpErlangObject> =
        ((term as? OtpErlangTuple)?.takeIf { it.arity() == 3 }?.elementAt(1) as? OtpErlangList)?.elements()?.asList()
            .orEmpty()

    /** The values of the module body or the file's top level the code runs in, which the walk ran. */
    private val bodyValues get() = module?.let(run.compiling::get)?.values ?: run.fileValues

    /**
     * The last of [expressions], each evaluated in turn as [parts] are, so one that declines runs those after it, past
     * those before the last the walk ran without a value
     * ([ranWithoutValue][org.elixir_lang.expander.BodyValues.ranWithoutValue]).
     */
    private fun block(expressions: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val last = expressions.lastOrNull()
        val values = bodyValues
        val taken = expressions.filter { it === last || !values.ranWithoutValue(it) }
        val outcome = partsOf(taken) { using(use.ofExpression(it, last)) { part(it, frame) } }

        return when (outcome) {
            is PartsOutcome.Values -> outcome.values.lastOrNull() ?: NIL
            is PartsOutcome.Declines -> throw outcome.declined
            is PartsOutcome.Raises -> throw outcome.raise
        }
    }

    private fun evalList(elements: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val last = elements.lastOrNull()

        if (last != null && isCall(last, "|", 2)) {
            val values = parts(elements.dropLast(1) + (last as ElixirAst.Call).arguments!!, frame)

            return cons(values.dropLast(1), values.last())
        }

        return list(parts(elements, frame))
    }

    /** [parts] of a value built from them, evaluated in turn ([partsOf]). */
    private fun parts(
        parts: List<ElixirAst>,
        frame: Frame,
        before: List<OtpErlangObject> = emptyList(),
        rest: List<ElixirAst> = emptyList(),
    ): List<OtpErlangObject> =
        when (val outcome = partsOf(parts, before, rest) { part(it, frame) }) {
            is PartsOutcome.Values -> outcome.values
            is PartsOutcome.Declines -> throw outcome.declined
            is PartsOutcome.Raises -> throw outcome.raise
        }

    /** [evaluate]'s value: where it declines, what the value built from it would have held includes [held] and [rest]. */
    private inline fun <T> holding(held: List<OtpErlangObject>, rest: List<ElixirAst>, frame: Frame, evaluate: () -> T): T =
        try {
            evaluate()
        } catch (declined: Declined) {
            declined.holds += held
            holdRest(declined, rest) { part(it, frame) }
            throw declined
        }

    /** [node]'s value as a part of a value built from parts ([partsOf]), evaluated [once]. */
    private fun part(node: ElixirAst, frame: Frame): PartOutcome<Thrown> = part { once(node, frame) }

    /**
     * [node]'s value: a statement the walk ran gives what it gave
     * ([ranGave][org.elixir_lang.expander.BodyValues.ranGave]), not a second run.
     */
    private fun once(node: ElixirAst, frame: Frame): OtpErlangObject =
        when (val gave = bodyValues.ranGave(node, run)) {
            null -> eval(node, frame)
            is Evaluated.Value -> gave.value
            is Evaluated.Declined -> throw Declined("code the walk ran").apply { holds += gave.holds }
            is Evaluated.Raised -> throw Declined("code the walk ran")
        }

    /** What [evaluate] gives, as a part of a value built from parts ([partsOf]). */
    internal inline fun part(evaluate: () -> OtpErlangObject): PartOutcome<Thrown> =
        try {
            PartOutcome.Value(evaluate())
        } catch (declined: Declined) {
            PartOutcome.Declines(declined)
        } catch (thrown: Thrown) {
            PartOutcome.Raises(thrown)
        }

    /**
     * [iteration], the next of a loop's, taken in turn ([InTurn]): where it declines, the loop goes on, and [declines] is
     * told what it held. A raise ends the loop, as the first decline where one came before it.
     */
    private inline fun InTurn<Thrown>.iterate(declines: (Declined) -> Unit, iteration: () -> Unit) {
        try {
            iteration()
        } catch (declined: Declined) {
            run.continuedDeclines++
            declines(declined)
            take(PartOutcome.Declines(declined))
        } catch (thrown: Thrown) {
            take(PartOutcome.Raises(thrown))
            throw declined ?: thrown
        }

        if (raised) throw declined!!
    }

    /** [build]'s value, made of [parts]: where it declines, what it would have held is them. */
    private inline fun <T> built(parts: List<OtpErlangObject>, build: () -> T): T =
        try {
            build()
        } catch (declined: Declined) {
            declined.holds += parts
            throw declined
        }

    private fun evalCall(node: ElixirAst.Call, frame: Frame): OtpErlangObject {
        val callee = node.callee
        val args = node.arguments

        if (callee is ElixirAst.Literal.Atom) {
            return if (args == null) variable(callee.name, node, frame) else named(callee.name, args, node, frame)
        }
        if (args == null) decline("a variable whose name isn't an atom")
        if (isCall(callee, ".", 2)) return dot(callee as ElixirAst.Call, node, args, frame)
        if (isCall(callee, ".", 1)) {
            val function = operands(listOf((callee as ElixirAst.Call).arguments!!.single()), frame, rest = args).single()

            return apply(function, operands(args, frame, before = listOf(function)))
        }

        decline("a call of ${callee.javaClass.simpleName}")
    }

    private fun variable(name: String, node: ElixirAst.Call, frame: Frame): OtpErlangObject {
        frame.binding(node)?.let(frame.vars::get)?.let { return it }

        return when (name) {
            "__CALLER__" -> caller
            "__STACKTRACE__" -> frame.stacktrace ?: decline("__STACKTRACE__ outside a rescue or catch")
            "__MODULE__" if frame.source -> atom(frame.module.module)
            "__DIR__" if frame.source -> value(node, frame)
            // An env the expansion gave no term of is a `Macro.Env` whose fields may name any module being compiled.
            "__ENV__" if frame.source -> givingAll { value(node, frame) }.let { if (it === Unknown) unknownEnv else it }
            // Before 1.15, the local call its expansion made of it.
            else if frame.source && frame.module.dispatches.of(node) != null -> dispatched(name, emptyList(), node, frame)
            else -> decline("reads $name, which isn't bound here")
        }
    }

    /** [env] at the call, as `__CALLER__` reads it: each field of the leg's `Macro.Env` ([envField]). */
    private fun envOf(env: Env): OtpErlangObject = envs.getOrPut(env) {
        macroEnv { field -> envField(field, env, state, lineAt(env), false, run)!! }
    }

    /** The line the macro runs at in [env]: [Env.line], or the call's. */
    private fun lineAt(env: Env): Int = env.line ?: lineOf(call.meta)

    /**
     * The env and state [term] is: `__CALLER__`'s, `__CALLER__`'s with only its `:function` changed ([withFunction]), or
     * the caller's in a match, as `Macro.Env.to_match/1` gives it, with the variables read before the match those it can
     * read now.
     */
    private fun expansionEnv(term: OtpErlangObject): Pair<Env, ExState>? {
        if (term === caller) return env to state
        withFunction(term)?.let { return it to state }

        val matchEnv = env.copy(context = Env.Context.MATCH)
        val matchState = state.copy(prematch = ExState.Prematch.InMatch(state.read, state.version, emptyMap(), emptyList()))
        val match = macroEnv { field -> envField(field, matchEnv, matchState, lineAt(matchEnv), false, run)!! }

        return (matchEnv to matchState).takeIf { term == match }
    }

    /** A `Macro.Env` whose every field is [ModuleUnknown]. */
    private val unknownEnv: OtpErlangObject by lazy { macroEnv { ModuleUnknown } }

    /** The leg's `Macro.Env`, each field [valueOf] it. */
    private fun macroEnv(valueOf: (String) -> OtpErlangObject): OtpErlangObject =
        mapOf(
            listOf(atom("__struct__") to atom("Elixir.Macro.Env")) + envFields(run.level).map { atom(it) to valueOf(it) },
            run.level,
        )

    /** `Left.right(args)`: a remote call, or a field of a map. */
    private fun dot(callee: ElixirAst.Call, node: ElixirAst.Call, args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val (left, right) = callee.arguments!!
        val name = (right as? ElixirAst.Literal.Atom)?.name ?: decline("a remote call whose name isn't an atom")

        if (frame.source) {
            when (val dispatched = frame.module.dispatches.of(node)) {
                is Dispatched.Remote ->
                    return remoteCall(atom(dispatched.module), dispatched.name, args, frame, marks = node !in run.effects)
                is Dispatched.Macro -> return once(dispatched.output, frame)
                is Dispatched.Value -> return otpOf(dispatched.value)
                Dispatched.Apply -> {}
                else -> decline("a remote call its expansion didn't reach")
            }
        }

        val marks = node !in run.effects

        if (args.isEmpty() && isFieldReceiver(left, frame)) return field(eval(left, frame), name, node.meta, frame, marks)

        return remoteCall(operands(listOf(left), frame, marks, rest = args).single(), name, args, frame, marks)
    }

    /**
     * `receiver.name(args)`, `:erlang.andalso/2` and `orelse/2` evaluating their right only where it is needed. A call
     * whose effect the walk applies [marks] nothing it is given.
     */
    private fun remoteCall(
        receiver: OtpErlangObject,
        name: String,
        args: List<ElixirAst>,
        frame: Frame,
        marks: Boolean = true,
    ): OtpErlangObject {
        if (receiver is OtpErlangAtom && shortCircuits(receiver.atomValue(), name, args.size)) {
            val value = selecting(args.subList(1, 2), emptyList(), emptyList(), frame) { seen(once(args[0], frame)) }

            if (value != TRUE && value != FALSE) error(tuple(atom("badarg"), value))

            return if ((value == TRUE) == (name == "andalso")) eval(args[1], frame) else value
        }

        return remote(receiver, name, operands(args, frame, marks, before = listOf(receiver)), marks)
    }

    /**
     * [args], a call's, evaluated in turn, [parts] of the call's value with [before] and [rest], each read, as the
     * function is given it. Where one declines, the call isn't run: where it [marks] what it is given, it is a declined
     * call given what its value would have held.
     */
    private fun operands(
        args: List<ElixirAst>,
        frame: Frame,
        marks: Boolean = true,
        before: List<OtpErlangObject> = emptyList(),
        rest: List<ElixirAst> = emptyList(),
    ): List<OtpErlangObject> =
        try {
            using(ValueUse.READ) { parts(args, frame, before, rest) }
        } catch (declined: Declined) {
            throw if (marks) gave(declined) { add(declined.holds, run.compiling.keys) } else declined
        }

    /** Whether `left.key` reads a field: where [left] is a tuple in the expanded AST, or, from 1.14, a boolean or `nil`. */
    private fun isFieldReceiver(left: ElixirAst, frame: Frame): Boolean =
        when (left) {
            is ElixirAst.Call -> !(frame.source && isVariableNamed(left, "__MODULE__"))
            is ElixirAst.Tuple, is ElixirAst.Block -> true
            is ElixirAst.Literal.Atom ->
                FIELD_OF_NON_MAP_RAISES_BADKEY.isSufficient(run.level) && left.name in setOf("nil", "true", "false")
            else -> false
        }

    private fun field(value: OtpErlangObject, name: String, meta: Meta, frame: Frame, marks: Boolean): OtpErlangObject {
        val key = atom(name)

        if (frame.guard) return natives.mapGet(key, value)
        received(value, emptyList(), marks)
        (value as? OtpErlangMap)?.get(key)?.let { return it }

        if (!FIELD_OF_NON_MAP_RAISES_BADKEY.isSufficient(run.level)) {
            if (value is OtpErlangMap) error(tuple(atom("badkey"), key, value))

            return remote(value, name, emptyList(), marks)
        }

        if ((metaValue(meta, "no_parens") as? Meta.Value.Atom)?.name != "true") return remote(value, name, emptyList(), marks)

        val badmap = FIELD_OF_NON_MAP_RAISES_BADMAP.isSufficient(run.level)

        return when {
            value is OtpErlangMap -> error(tuple(atom("badkey"), key, value))
            value is OtpErlangAtom && value.atomValue() !in setOf("nil", "true", "false") -> remote(value, name, emptyList(), marks)
            badmap -> error(tuple(atom("badmap"), value))
            else -> error(tuple(atom("badkey"), key, value))
        }
    }

    /** [pattern] matched against [value], as `=` matches: [value], or a `badmatch`. */
    fun bind(pattern: ElixirAst, value: OtpErlangObject, frame: Frame): OtpErlangObject {
        val matched = try {
            matches(listOf(pattern), listOf(value), frame)
        } catch (declined: Declined) {
            // Where what it declined on came from a module being compiled, so may what the pattern binds.
            frame.bindEach(pattern, unknownOf(declined.holds + value))
            throw declined
        }

        if (!matched) error(tuple(atom("badmatch"), value))

        return value
    }

    /** [pattern] matched against [right]'s value; where [right] declines, each variable it binds holds what that held. */
    private fun match(pattern: ElixirAst, right: ElixirAst, frame: Frame): OtpErlangObject {
        val value = try {
            eval(right, frame)
        } catch (declined: Declined) {
            frame.bindEach(pattern, unknownOf(declined.holds))
            throw declined
        }

        return bind(pattern, value, frame)
    }

    /** A call of [name], a special form at the arity `elixir_expand` and `translate/3` take it, else a call. */
    private fun named(name: String, args: List<ElixirAst>, node: ElixirAst.Call, frame: Frame): OtpErlangObject =
        when (name) {
            "=" if args.size == 2 -> match(args[0], args[1], frame)
            "{}" -> placed(OtpErlangTuple(parts(args, frame).toTypedArray()), node, frame)
            "%{}" -> map(args, frame)
            "%" if args.size == 2 -> struct(args, frame)
            "<<>>" -> bits.build(args, frame)
            "__block__" -> block(args, frame)
            "case" if args.size == 2 -> {
                val options = (frame.module.dispatches.of(node) as? Dispatched.Clauses)?.options ?: args[1]
                val arrows = arrows(doOption(options))
                val value = try {
                    selecting(arrows, heads(arrows), emptyList(), frame) { once(args[0], frame) }
                } catch (declined: Declined) {
                    // An arm after those that only raise may still be taken.
                    return clauses(arrows, listOf(unknownOf(declined.holds)), frame, subject = declined)!!
                }

                clauses(arrows, listOf(value), frame) ?: error(tuple(atom("case_clause"), value))
            }
            "cond" if args.size == 1 -> cond(arrows(doOption(args[0])), frame)
            "fn" -> fn(node, args, frame)
            "&" if args.size == 1 -> capture(node, frame)
            "try" if args.size == 1 -> evalTry(args.single(), frame)
            "for" if args.isNotEmpty() -> evalFor(args, frame)
            "with" if args.isNotEmpty() -> evalWith(args, frame)
            "quote" if args.size in 1..2 ->
                if (frame.source) {
                    eval(frame.module.quotes.of(node) ?: decline("a quote whose escaped block wasn't built"), frame)
                } else {
                    decline("a quote in debug info")
                }
            "receive" if args.size == 1 -> {
                val arrows = listOf("do", "after").flatMap { key -> optionOrNull(args.single(), key)?.let(::arrows).orEmpty() }

                selecting(arrows, heads(arrows), emptyList(), frame) { decline("receive") }
            }
            "super" -> superCall(node, args, frame)
            // Outside a pattern, only in the size `expand_specs/7` infers for a pin: `byte_size(^x)`.
            "^" if args.size == 1 -> eval(args.single(), frame)
            else -> if (frame.source) dispatched(name, args, node, frame) else local(name, args, frame)
        }

    /** A source call of [name], as its expansion dispatched it. */
    private fun dispatched(name: String, args: List<ElixirAst>, node: ElixirAst.Call, frame: Frame): OtpErlangObject =
        when (val dispatched = frame.module.dispatches.of(node)) {
            Dispatched.Local -> local(name, args, frame)
            is Dispatched.Remote -> remoteCall(atom(dispatched.module), dispatched.name, args, frame)
            is Dispatched.Macro -> once(dispatched.output, frame)
            is Dispatched.Value -> otpOf(dispatched.value)
            is Dispatched.Apply, is Dispatched.Super, is Dispatched.Fn, is Dispatched.Clauses, null ->
                decline("calls $name/${args.size}, which its expansion didn't reach")
        }

    /** The value a source [node] expanded to, in place of the node. */
    private fun value(node: ElixirAst, frame: Frame): OtpErlangObject =
        (frame.module.dispatches.of(node) as? Dispatched.Value)?.let { otpOf(it.value) }
            ?: decline("a form its expansion didn't reach")

    /**
     * `translate/3`'s `super` [node]: a call of the definition its `{super, {Kind, Name}}` names, or a source `super`'s
     * expansion stored, and a macro's given `__CALLER__`.
     */
    private fun superCall(node: ElixirAst.Call, args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val (kind, name) = superOf(node, frame) ?: decline("a super its expansion didn't reach")
        val definition = frame.module.definitions[NameArity(name, args.size)]
            ?.takeIf { it.kind.macro == kind.macro }
            ?: decline("super calls $name/${args.size}, which ${frame.module.module} doesn't hold")
        val values = operands(args, frame)

        return calling(values) {
            if (kind.macro) {
                val function = ModuleExports.macroExport(NameArity(name, args.size))

                invoke(frame.module, definition, function.name, values, function.arity)
            } else {
                invoke(frame.module, definition, name, values)
            }
        }
    }

    /** The kind and name of the definition [node], a `super`, calls. */
    private fun superOf(node: ElixirAst.Call, frame: Frame): Dispatched.Super? {
        (frame.module.dispatches.of(node) as? Dispatched.Super)?.let { return it }

        val (kind, name) = (metaValue(node.meta, "super") as? Meta.Value.Tuple)
            ?.elements
            ?.map { (it as? Meta.Value.Atom)?.name ?: return null }
            ?.takeIf { it.size == 2 }
            ?: return null

        return DefinitionTable.Kind.entries.find { it.name.lowercase() == kind }?.let { Dispatched.Super(it, name) }
    }

    /** An attribute's [value], as the escaped value `@` gives in a function evaluates to, its atoms seen. */
    fun otpOf(value: Term): OtpErlangObject = otpOf(value) { atoms += it }

    /** An attribute's [value] that a [Term] can't hold, as the table keeps it, its atoms seen. */
    fun held(value: OtpErlangObject): OtpErlangObject =
        value.also {
            anyHeld(listOf(it)) { term ->
                if (term is OtpErlangAtom) atoms += term.atomValue()
                false
            }
        }

    /** A call of a function of the clause's own module. */
    private fun local(name: String, args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val definition = frame.module.definitions[NameArity(name, args.size)]
            ?.takeIf { !it.kind.macro }
            ?: decline("calls $name/${args.size}, which isn't a function of ${frame.module.module}")

        val values = operands(args, frame)

        return nativeResult(frame.module.module, name, values, marks = true)
            ?: calling(values) { invoke(frame.module, definition, name, values) }
    }

    private fun map(args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val update = args.singleOrNull()?.takeIf { isCall(it, "|", 2) } as ElixirAst.Call?

        if (update != null) {
            val (left, pairs) = update.arguments!!
            val values = parts(listOf(left) + pairNodes(listOf(pairs)), frame)

            return update(values.first(), pairsOf(values.drop(1)))
        }

        return mapOf(pairsOf(parts(pairNodes(args), frame)), run.level)
    }

    /** The key and the value of each `key => value` of [args]: a keyword list's pairs, or the tuples a map's arguments are. */
    private fun pairNodes(args: List<ElixirAst>): List<ElixirAst> =
        args.flatMap { arg -> (arg as? ElixirAst.ListNode)?.elements ?: listOf(arg) }.flatMap { pair ->
            (pair as? ElixirAst.Tuple)?.elements?.takeIf { it.size == 2 } ?: decline("a map pair")
        }

    /** [values], each key followed by its value, as pairs. */
    private fun pairsOf(values: List<OtpErlangObject>): List<Pair<OtpErlangObject, OtpErlangObject>> =
        values.chunked(2) { (key, value) -> key to value }

    /** `%{map | pairs}`: every key must already be in [map]. Where it declines, what it would have held is them. */
    private fun update(map: OtpErlangObject, pairs: List<Pair<OtpErlangObject, OtpErlangObject>>): OtpErlangObject =
        built(listOf(map) + pairs.flatMap { it.toList() }) {
            seen(map)
            if (map !is OtpErlangMap) error(tuple(atom("badmap"), map))

            val missing = pairs.firstOrNull { (key, _) -> valueAt(map, key, run.level) == null }?.first

            when {
                missing == null -> mapOf(entries(map) + pairs, run.level)
                MAP_UPDATE_RAISES_WHERE_DISCARDED.isSufficient(run.level) || use == ValueUse.READ ->
                    error(tuple(atom("badkey"), missing))
                // The compiler dropped the update, so nothing raises, and nothing reads what it gives.
                use == ValueUse.DISCARDED -> map
                else -> decline("a map update whose value may be discarded, which this OTP doesn't raise for")
            }
        }

    /**
     * `%Name{pairs}`, from debug info its defaults escaped among its pairs, or `%Name{value | pairs}`. From source, the
     * defaults are what `Name.__struct__/1` gives, as `elixir_map:expand_struct/5` reads them.
     */
    private fun struct(args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val (nameNode, mapNode) = args
        val mapArgs = (mapNode as? ElixirAst.Call)?.takeIf { isCall(it, "%{}", it.arguments?.size ?: -1) }?.arguments
            ?: decline("a struct whose fields aren't a map")
        val update = mapArgs.singleOrNull()?.takeIf { isCall(it, "|", 2) } as ElixirAst.Call?

        if (update == null) {
            val values = parts(listOf(nameNode) + pairNodes(mapArgs), frame)
            val name = values.first()
            val given = pairsOf(values.drop(1))

            if (!frame.source) return mapOf(listOf(STRUCT to name) + given, run.level)

            val struct = built(listOf(name) + given.flatMap { it.toList() }) {
                remote(name, "__struct__", listOf(list(given.map { (key, value) -> tuple(key, value) })))
            } as? OtpErlangMap ?: decline("a struct whose __struct__/1 isn't a map")
            val defaults = entries(struct).filter { (key, _) -> key != STRUCT && given.none { equal(it.first, key, true, run.level) } }

            return mapOf(
                buildList {
                    add(STRUCT to name)
                    addAll(defaults)
                    addAll(given)
                },
                run.level,
            )
        }

        val (left, pairs) = update.arguments!!
        val pairNodes = pairNodes(listOf(pairs))
        val (name, value) = holding(emptyList(), pairNodes, frame) { parts(listOf(nameNode, left), frame) }

        // `translate_struct/4` checks the struct before it runs the pairs, which the value it doesn't build holds.
        if (!STRUCT_UPDATE_IS_MAP_UPDATE.isSufficient(run.level)) {
            holding(listOf(name, value), pairNodes, frame) { seen(value) }
            if ((value as? OtpErlangMap)?.get(STRUCT)?.let { equal(it, name, true, run.level) } != true) {
                error(tuple(atom("badstruct"), name, value))
            }
        }

        return update(value, pairsOf(parts(pairNodes, frame, before = listOf(name, value))))
    }

    private fun cond(arrows: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val arms = Arms { soleArm(arrows.map { arrowParts(it).second }, frame) }

        for ((index, arrow) in arrows.withIndex()) {
            val (head, body) = arrowParts(arrow)
            val scope = frame.copy()
            val rest = listOf(body) + arrows.subList(index + 1, arrows.size)

            val test = { selecting(rest, emptyList(), emptyList(), scope) { truthy(eval(head.single(), scope)) } }

            if (arms.tested(body, scope, test)) return arms.taken(body, scope)
        }

        arms.none()
        error(atom("cond_clause"))
    }

    private fun fn(node: ElixirAst, arrows: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val closed = frame.copy()
        val arity = clauseHead(arrowParts(arrows.first()).first).first.size

        return Closure(arity, { marks(arrows, closed) }, node) { values ->
            run.entered += node
            run.running.addLast(node)

            try {
                clauses(arrows, values, closed, fresh = true) ?: noClause(closed, values)
            } finally {
                run.running.removeLast()
            }
        }
    }

    /**
     * [select]'s value, which picks which of [branches] run. Where it declines, any of them may: the calls in them were
     * given what they hold ([marks]), each variable of [patterns] holding what [values] and the decline do.
     */
    private inline fun <T> selecting(
        branches: List<ElixirAst>,
        patterns: List<ElixirAst>,
        values: List<OtpErlangObject>,
        frame: Frame,
        select: () -> T,
    ): T =
        try {
            select()
        } catch (declined: Declined) {
            val scope = frame.copy()
            val value = unknownOf(values + declined.holds)

            for (pattern in patterns) scope.bindEach(pattern, value)
            throw gave(declined) { add(marks(branches, scope), run.compiling.keys) }
        }

    /** The head of each of [arrows]. */
    private fun heads(arrows: List<ElixirAst>): List<ElixirAst> = arrows.flatMap { arrowParts(it).first }

    /**
     * What an `fn` of [arrows] closing over [closed] holds ([Closure.marks]): each atom its code names, and the values of
     * the variables it captures and of the `__MODULE__`, `__ENV__` and `__CALLER__` it reads. A call whose effect the walk
     * applies ([Run.effects]) gives its module only through its other arguments: the effect says what it writes there,
     * which a read beside it needs too ([effectWritten]). A call whose writes are read from its callee's clauses
     * ([attributesWritten]) does too. Code that only raises holds only what its arguments write ([raiseWritten]).
     */
    private fun marks(arrows: List<ElixirAst>, closed: Frame): List<OtpErlangObject> {
        val marks = ArrayList<OtpErlangObject>()
        val pending = ArrayDeque(arrows)
        val walked: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())

        while (pending.isNotEmpty()) {
            val node = closed.expanded(pending.removeLast())

            if (!walked.add(node)) continue

            when {
                onlyRaises(node, closed) -> raiseWritten(node as ElixirAst.Call, closed)?.let { marks += it }
                node is ElixirAst.Call && node in run.effects -> {
                    effectWritten(node, closed)?.let { marks += it }
                    pending.addAll(children(node).filterNot { isModule(it, closed) })
                }
                node is ElixirAst.Call && attributesWritten(node, closed)?.also { marks += it } != null ->
                    pending.addAll(children(node).filterNot { isModule(it, closed) })
                node is ElixirAst.Literal.Atom -> marks += OtpErlangAtom(node.name)
                node is ElixirAst.Value -> marks += node.term
                isVariable(node) -> {
                    val name = ((node as ElixirAst.Call).callee as ElixirAst.Literal.Atom).name
                    val bound = closed.binding(node)?.let(closed.vars::get)

                    when {
                        bound != null -> marks += bound
                        name == "__MODULE__" && closed.source -> marks += OtpErlangAtom(closed.module.module)
                        name == "__ENV__" && closed.source -> marks += ModuleUnknown
                        name == "__CALLER__" -> marks += caller
                    }
                }
                else -> pending.addAll(children(node))
            }
        }

        return marks
    }

    /**
     * What [call], in code of [frame], writes of the module [frame] runs in where it is given that module and that can be
     * read from its callee's clauses ([attributesWritten]), `null` otherwise.
     */
    private fun attributesWritten(call: ElixirAst.Call, frame: Frame): AttributesWritten? {
        val args = call.arguments ?: return null
        val (module, function) = remoteOf(call, frame) ?: return null

        return attributesWritten(frame.module.module, module, function, args.size, args.indices.filter { isModule(args[it], frame) })
    }

    /** What [call], whose effect the walk applies ([Run.effects]), writes of the module [frame] runs in, for a read of it beside it. */
    private fun effectWritten(call: ElixirAst.Call, frame: Frame): EffectWritten? {
        val given = frame.module.module
        val writes = CalleeWrites(given)

        return if (given in run.compiling && writes.effect(call, frame)) EffectWritten(given, writes.names) else null
    }

    /**
     * What the arguments of [call], which only raises, write of the module [frame] runs in: the module itself where a
     * writer can't be read, `null` where they write nothing.
     */
    private fun raiseWritten(call: ElixirAst.Call, frame: Frame): OtpErlangObject? {
        val given = frame.module.module

        if (given !in run.compiling) return null

        val writes = CalleeWrites(given)

        return when {
            !writes.effect(call, frame) -> OtpErlangAtom(given)
            writes.names.isEmpty() -> null
            else -> AttributesWritten(given, writes.names, writes.read)
        }
    }

    /**
     * What a call of [module]'s [function] of [arity], given [given], a module being compiled, at [positions], writes of
     * it: the attributes [CalleeWrites] reads from its clauses, or `null` where it isn't given it or that read fails.
     */
    private fun attributesWritten(given: String, module: String, function: String, arity: Int, positions: List<Int>): AttributesWritten? {
        if (positions.isEmpty() || given !in run.compiling) return null
        if (ModuleEffects.target(module, function, arity, run.level) != null) return null

        val writes = CalleeWrites(given)

        return if (writes.of(module, function, arity, positions.toSet())) AttributesWritten(given, writes.names, writes.read) else null
    }

    /**
     * What a call writes of [given], a module being compiled, read from the clauses of the function it calls, as the
     * module stands at the call: [names], the attributes its writers name, and [read], those whose value there the reading
     * rests on. Where a callee's clauses aren't held, or a writer's target or attribute isn't known, [of] is `false`.
     */
    private inner class CalleeWrites(private val given: String) {
        val names = HashSet<String>()
        val read = HashSet<String>()

        private val visited = HashSet<Pair<Triple<String, String, Int>, Set<Int>>>()
        private var writerUnread = false

        /** Adds what [module]'s [function] of [arity], given the module at [positions], writes to [names]; `false` where it can't. */
        fun of(module: String, function: String, arity: Int, positions: Set<Int>): Boolean {
            if (!visited.add(Triple(module, function, arity) to positions)) return true
            if (module in Natives.CLAUSES_UNREAD) return false

            val clauses = run.clausesOf(module) ?: return false
            val definition = clauses.definitions[NameArity(function, arity)]?.clauses ?: return false
            val frame = Frame(clauses)

            return definition.all { clause ->
                val held = HashSet<String>()

                for (position in positions) clause.args.getOrNull(position)?.let { variables(it, held) }
                clause.body?.let { walk(it, frame, held, HashMap()) } == true
            }
        }

        /** Adds what [call], in code of the module itself, writes to [names]; `false` where it can't. */
        fun effect(call: ElixirAst.Call, frame: Frame): Boolean = walk(call, frame, HashSet(), HashMap())

        /** Adds what [node] writes to [names], [held] the variables that hold the module; `false` where it can't. */
        private fun walk(node: ElixirAst, frame: Frame, held: MutableSet<String>, listed: MutableMap<String, List<String>>): Boolean {
            ProgressManager.checkCanceled()

            val expanded = frame.expanded(node)
            val walkAll = { nodes: List<ElixirAst> -> nodes.all { walk(it, frame, held, listed) } }

            if (expanded !is ElixirAst.Call || expanded.arguments == null) return walkAll(children(expanded))

            val args = expanded.arguments

            remoteOf(expanded, frame)?.let { (module, function) -> return remote(module, function, args, frame, held, listed) }

            val name = (expanded.callee as? ElixirAst.Literal.Atom)?.name
                ?: return !holds(expanded, frame, held) && walkAll(children(expanded))

            when {
                isCall(expanded, "=", 2) -> {
                    val right = walk(args[1], frame, held, listed)

                    if (holds(args[1], frame, held)) variables(args[0], held)
                    return right
                }
                isCall(expanded, "case", 2) && holds(args[0], frame, held) -> variables(args[1], held)
                isCall(expanded, "<-", 2) -> generator(args[0], args[1], frame, held, listed)
            }

            val local = !frame.source || frame.module.dispatches.of(expanded) is Dispatched.Local

            // From debug info `super` is also a default-argument clause's call of its full arity, and isn't followed.
            if (name == "super" && frame.source || local && frame.module.definitions[NameArity(name, args.size)] != null) {
                val positions = args.indices.filter { holds(args[it], frame, held) }.toSet()
                val definition = if (name == "super") superOf(expanded, frame)?.name else name

                return if (positions.isEmpty()) walkAll(args) else definition != null && of(frame.module.module, definition, args.size, positions)
            }

            return walkAll(args)
        }

        /** The generator [pattern] `<-` [enumerable]: its elements hold the module where it does, or are named by it. */
        private fun generator(pattern: ElixirAst, enumerable: ElixirAst, frame: Frame, held: MutableSet<String>, listed: MutableMap<String, List<String>>) {
            if (holds(enumerable, frame, held)) variables(pattern, held)

            val attribute = attributeRead(enumerable, frame, held) ?: return

            read += attribute

            val value = natives.attributeAt(given, attribute) as? OtpErlangList ?: return
            val elements = value.elements().map { (it as? OtpErlangAtom)?.atomValue() ?: return }

            if (isVariable(pattern)) listed[variableName(pattern)] = elements
        }

        /** The attribute of the module that [node] reads by a literal name, where it is such a read. */
        private fun attributeRead(node: ElixirAst, frame: Frame, held: Set<String>): String? {
            val call = frame.expanded(node) as? ElixirAst.Call ?: return null
            val args = call.arguments ?: return null
            val (module, function) = remoteOf(call, frame) ?: return null

            if (!natives.onlyReads(module, function, args.size) || args.size < 2) return null
            if (!heldModule(args[0], frame, held)) return null

            return (frame.expanded(args[1]) as? ElixirAst.Literal.Atom)?.name
        }

        private fun remote(module: String, function: String, args: List<ElixirAst>, frame: Frame, held: MutableSet<String>, listed: MutableMap<String, List<String>>): Boolean {
            // A raise's arguments run before it and a `try` can catch it, so what they write is written. A message that
            // can't be read, as one interpolating the module, doesn't end the reading.
            if (natives.neverReturns(module, function, args.size)) {
                args.forEach { walk(it, frame, held, listed) }
                return !writerUnread
            }

            val target = ModuleEffects.target(module, function, args.size, run.level)

            if (target != null) {
                val written = frame.expanded(args[target])

                if (written is ElixirAst.Literal.Atom && written.name != given) return args.all { walk(it, frame, held, listed) }

                if (!heldModule(written, frame, held) || !wrote(module, function, args, frame, listed)) {
                    writerUnread = true
                    return false
                }

                return args.all { walk(it, frame, held, listed) }
            }

            if (natives.onlyReads(module, function, args.size)) return args.all { walk(it, frame, held, listed) }
            // A native writes nothing of what it is given, unless it runs a function it is given.
            if (module in Natives.CLAUSES_UNREAD && !natives.runsAFunction(module, function, args.size)) return args.all { walk(it, frame, held, listed) }

            val positions = args.indices.filter { holds(args[it], frame, held) }.toSet()

            return if (positions.isEmpty()) args.all { walk(it, frame, held, listed) } else of(module, function, args.size, positions)
        }

        /**
         * Adds what `Module`'s writer [function] writes, its attribute named by a literal or by a variable [listed], as
         * its row in [ModuleEffects] has it. A delete known to change nothing writes nothing.
         */
        private fun wrote(module: String, function: String, args: List<ElixirAst>, frame: Frame, listed: Map<String, List<String>>): Boolean {
            val attribute = args.getOrNull(1)?.let(frame::expanded) ?: return false
            val attributes = when {
                attribute is ElixirAst.Literal.Atom -> listOf(attribute.name)
                isVariable(attribute) -> listed[variableName(attribute)] ?: return false
                else -> return false
            }

            return attributes.all { name ->
                val terms = args.indices.map { if (it == 0) Term.Atom(given) else if (it == 1) Term.Atom(name) else Term.Unexpanded }
                val change = ModuleEffects.of(Term.Atom(module), function, terms, args, given, run.level)

                when ((change as? ModuleEffects.Change.Attributes)?.effect) {
                    is Effect.Write, is Effect.Register -> names.add(name).let { true }
                    is Effect.Delete -> {
                        if (!natives.deleteChangesNothing(given, name)) names += name
                        true
                    }
                    else -> false
                }
            }
        }

        /** Whether [node] is the module, by its atom or a variable that holds it. */
        private fun heldModule(node: ElixirAst, frame: Frame, held: Set<String>): Boolean =
            when (val expanded = frame.expanded(node)) {
                is ElixirAst.Literal.Atom -> expanded.name == given
                else -> isVariable(expanded) &&
                    (variableName(expanded) in held ||
                        frame.source && variableName(expanded) == "__MODULE__" && frame.module.module == given ||
                        frame.binding(expanded)?.let(frame.vars::get) == OtpErlangAtom(given))
            }

        /** Whether [node]'s value may hold the module: code that only raises doesn't. */
        private fun holds(node: ElixirAst, frame: Frame, held: Set<String>): Boolean {
            val expanded = frame.expanded(node)

            if (heldModule(expanded, frame, held)) return true
            if (isVariable(expanded) && frame.source && variableName(expanded) == "__ENV__") return true
            if (isVariable(expanded) && frame.binding(expanded)?.let(frame.vars::get)?.let(::valueHolds) == true) return true
            if (onlyRaises(expanded, frame)) return false
            if (expanded is ElixirAst.Call) {
                val args = expanded.arguments.orEmpty()

                remoteOf(expanded, frame)?.let { (module, function) ->
                    if (natives.onlyReads(module, function, args.size)) {
                        val returned = natives.returnable(module, function, args)

                        return returned.isNotEmpty() && readHolds(expanded, returned, frame, held)
                    }
                }
            }

            return children(expanded).any { holds(it, frame, held) }
        }

        /** Whether what [call], a read of an attribute, gives may hold the module: its value there, or the rest of [returned]. */
        private fun readHolds(call: ElixirAst.Call, returned: List<ElixirAst>, frame: Frame, held: Set<String>): Boolean {
            val attribute = attributeRead(call, frame, held) ?: return true
            val value = natives.attributeAt(given, attribute) ?: return true

            read += attribute

            return valueHolds(value) || returned.drop(1).any { holds(it, frame, held) }
        }

        private fun valueHolds(value: OtpErlangObject): Boolean = !Given().apply { add(listOf(value), setOf(given)) }.none

        private fun variables(pattern: ElixirAst, into: MutableSet<String>) {
            if (isVariable(pattern)) into += variableName(pattern) else children(pattern).forEach { variables(it, into) }
        }

        private fun variableName(variable: ElixirAst): String =
            ((variable as ElixirAst.Call).callee as ElixirAst.Literal.Atom).name
    }

    /** Whether [node] is the module [frame] runs in: `__MODULE__`, or its atom. */
    private fun isModule(node: ElixirAst, frame: Frame): Boolean =
        when (val expanded = frame.expanded(node)) {
            is ElixirAst.Literal.Atom -> expanded.name == frame.module.module
            else -> frame.source && isVariableNamed(expanded, "__MODULE__")
        }

    /** What an `fn` of [closed] raises where none of its clauses matches [values]. */
    private fun noClause(closed: Frame, values: List<OtpErlangObject>): Nothing {
        if (!FN_CLAUSE_ERROR_IS_FUNCTION_CLAUSE.isSufficient(run.level)) decline("an fn clause error")

        // Its name is the compiler's, from its fun counter and what it inlines.
        error(atom("function_clause"), OtpErlangTuple(arrayOf(atom(closed.module.module), Unknown, list(values), EMPTY)))
    }

    /**
     * `&` at [amp]: from source, as its expansion dispatched it, a capture of a function or the `fn` `capture_expr/6`
     * rewrote it to; from `Dbgi`, `&name/arity` or `&Module.name/arity`, the captures `elixir_expand` leaves.
     */
    private fun capture(amp: ElixirAst.Call, frame: Frame): OtpErlangObject {
        val dispatched = if (frame.source) frame.module.dispatches.of(amp) else null

        if (dispatched is Dispatched.Fn) return eval(dispatched.fn, frame)

        val captured = amp.arguments!!.single() as? ElixirAst.Call ?: decline("a capture's function")
        val (function, arity) = when {
            isCall(captured, "/", 2) -> {
                val (function, arity) = captured.arguments!!

                function to ((arity as? ElixirAst.Literal.Integer)?.value?.toInt() ?: decline("a capture's arity"))
            }
            // `&f(&1, &2)`, where its expansion dispatched it, captures `f/2`.
            frame.source -> captured to (captured.arguments?.size ?: decline("a capture's arity"))
            else -> decline("a capture other than name/arity")
        }
        val callee = (function as? ElixirAst.Call)?.callee ?: decline("a capture's function")

        val remote = if (frame.source) {
            when (dispatched) {
                is Dispatched.Remote -> return OtpErlangExternalFun(dispatched.module, dispatched.name, arity)
                Dispatched.Local, is Dispatched.Super -> false
                Dispatched.Apply -> true
                else -> decline("a capture its expansion didn't reach")
            }
        } else {
            isCall(callee, ".", 2)
        }

        if (remote) {
            val (receiver, name) = (callee as ElixirAst.Call).arguments!!
            val value = eval(receiver, frame)
            val module = value as? OtpErlangAtom ?: built(listOf(value)) { decline("a capture's module") }

            return OtpErlangExternalFun(module.atomValue(), (name as? ElixirAst.Literal.Atom)?.name ?: decline("a capture's name"), arity)
        }

        val name = (dispatched as? Dispatched.Super)?.name
            ?: (callee as? ElixirAst.Literal.Atom)?.name
            ?: decline("a capture's name")
        val module = frame.module
        val definition = module.definitions[NameArity(name, arity)]
            ?.takeIf { !it.kind.macro }
            ?: decline("a capture of $name/$arity, which isn't a function of ${module.module}")

        return Closure(arity, { listOf(OtpErlangAtom(module.module)) }) { values -> invoke(module, definition, name, values) }
    }

    /** [function] applied to [args]. */
    fun apply(function: OtpErlangObject, args: List<OtpErlangObject>): OtpErlangObject {
        // As [invoke] does, so a loop calling an `fn` checks once per call, though the `fn` calls nothing.
        ProgressManager.checkCanceled()

        return when (function) {
            is Closure ->
                if (function.arity == args.size) {
                    calling(args) { using(ValueUse.READ) { function.invoke(args) } }
                } else {
                    error(tuple(atom("badarity"), tuple(function, list(args))))
                }
            is OtpErlangExternalFun ->
                if (function.arity == args.size) {
                    remote(atom(function.module), function.function, args)
                } else {
                    error(tuple(atom("badarity"), tuple(function, list(args))))
                }
            is UnknownValue -> calling(args + function) { decline("an unknown value") }
            else -> error(tuple(atom("badfun"), function))
        }
    }

    /**
     * `receiver:function(args)`: a native, else a public function of a module whose clauses can be read. A decline in
     * it [marks] what [args] give it ([calling]). A native's unknown result came from a module being compiled where an
     * argument it may return ([Natives.returnable]) gives one.
     */
    fun remote(
        receiver: OtpErlangObject,
        function: String,
        args: List<OtpErlangObject>,
        marks: Boolean = true,
    ): OtpErlangObject {
        received(receiver, args, marks)

        val module = (receiver as? OtpErlangAtom)?.atomValue()
            ?: error(atom("badarg"), frame("erlang", "apply", list(listOf(receiver, atom(function), list(args)))))

        nativeResult(module, function, args, marks)?.let { return it }

        return if (marks) callingDefined(module, function, args) else defined(module, function, args)
    }

    /**
     * [module]`.`[function]`(`[args]`)` where it is a native, else `null`, called by its module or from outside it alike. Its
     * unknown result came from a module being compiled where an argument it may return ([Natives.returnable]) gives one.
     */
    private fun nativeResult(module: String, function: String, args: List<OtpErlangObject>, marks: Boolean): OtpErlangObject? =
        native(module, function, args, marks)?.let { result ->
            if (result === Unknown) unknownOf(natives.returnable(module, function, args)) else result
        }

    /**
     * [receiver], a remote call's given [args]: one that isn't known may be any module's code, and where the call [marks]
     * what it is given, that code is given it and [args] ([calling]).
     */
    private fun received(receiver: OtpErlangObject, args: List<OtpErlangObject>, marks: Boolean): OtpErlangObject =
        if (marks) calling(args + receiver) { seen(receiver) } else seen(receiver)

    /**
     * [module]`.`[function]`(`[args]`)` where it is a native, else `null`. Where it declines, what it didn't return may
     * hold the arguments it can return ([Natives.returnable]), and where it [marks] what it is given, it may have
     * written what those it runs give it ([Natives.runs]) and the module it writes ([Natives.writes]). A write whose
     * effect the walk applies declines, as its row applies it.
     */
    private fun native(module: String, function: String, args: List<OtpErlangObject>, marks: Boolean): OtpErlangObject? =
        try {
            if (!marks && natives.writes(module, function, args).isNotEmpty()) {
                decline("$module:$function/${args.size}'s effect is applied by the walk")
            }

            natives.call(module, function, args)
        } catch (declined: Declined) {
            declined.holds += natives.returnable(module, function, args)
            if (!marks) throw declined
            throw gave(declined) {
                add(natives.runs(module, function, args) + natives.writes(module, function, args), run.compiling.keys)
            }
        }

    /** [module]`.`[function]`(`[args]`)`, from its clauses: what Elixir runs where it isn't a native. */
    private fun defined(module: String, function: String, args: List<OtpErlangObject>): OtpErlangObject {
        if (module in Natives.CLAUSES_UNREAD) decline("$module:$function/${args.size} isn't a native")

        val clauses = run.clausesOf(module) ?: decline("no clauses of $module")
        val nameArity = NameArity(function, args.size)
        val definition = clauses.definitions[nameArity]

        when (definition?.kind) {
            DefinitionTable.Kind.DEF -> return invoke(clauses, definition, function, args)
            DefinitionTable.Kind.DEFMACRO, DefinitionTable.Kind.DEFMACROP -> decline("calls the macro $module.$nameArity")
            DefinitionTable.Kind.DEFP, null -> {}
        }

        when (val exports = run.exports.of(module)) {
            is ModuleExports.Present ->
                if (nameArity in exports.exported) decline("$module exports $nameArity, whose clauses aren't read")
            ModuleExports.Absent, ModuleExports.Unreadable -> decline("$module's exports aren't known")
        }

        error(atom("undef"), frame(module, function, list(args)))
    }

    /** [read] of a value that would give every module being compiled ([ModuleUnknown]): a decline in it gives them all. */
    private inline fun <T> givingAll(read: () -> T): T =
        try {
            read()
        } catch (declined: Declined) {
            throw gave(declined) { all() }
        }

    /**
     * [module]'s [function] run from its clauses, given [args]: a decline in it may have written each module being
     * compiled that [args] give it ([calling]), but an argument that is one whose writes its clauses say
     * ([attributesWritten]) gives only those.
     */
    private fun callingDefined(module: String, function: String, args: List<OtpErlangObject>): OtpErlangObject =
        try {
            defined(module, function, args)
        } catch (declined: Declined) {
            val written = run.compiling.keys.mapNotNull { given ->
                attributesWritten(given, module, function, args.size, args.indices.filter { args[it] == atom(given) })
            }

            throw gave(declined) {
                written.forEach(::written)
                add(args.filterNot { arg -> written.any { arg == atom(it.module) } }, run.compiling.keys)
            }
        }

    /** [call], given [args]: a decline in it may have written each module being compiled that [args] give it ([Given]). */
    private inline fun <T> calling(args: List<OtpErlangObject>, call: () -> T): T =
        try {
            call()
        } catch (declined: Declined) {
            throw gave(declined) { add(args, run.compiling.keys) }
        }

    private fun evalTry(options: ElixirAst, frame: Frame): OtpErlangObject {
        var declined: Declined? = null
        var raised: Thrown? = null

        try {
            // Where the body declines, it may have raised, thrown or exited, or not.
            val branches = listOf("rescue", "catch", "else").flatMap { key -> optionOrNull(options, key)?.let(::arrows).orEmpty() }
            val catches = listOf("rescue", "catch").any { optionOrNull(options, it) != null }
            val value = try {
                selecting(branches, heads(branches), emptyList(), frame) {
                    if (catches) catching++

                    try {
                        eval(doOption(options), frame.copy())
                    } finally {
                        if (catches) catching--
                    }
                }
            } catch (thrown: Thrown) {
                return rescued(thrown, options, frame) ?: throw thrown
            } catch (body: Declined) {
                body.endedBy?.let { raise -> rescuedAfter(raise, body, options, frame) }
                throw body
            }

            return optionOrNull(options, "else")?.let { elses ->
                clauses(arrows(elses), listOf(value), frame) ?: error(tuple(atom("try_clause"), value))
            } ?: value
        } catch (body: Declined) {
            declined = body
            throw body
        } catch (thrown: Thrown) {
            raised = thrown
            throw thrown
        } finally {
            optionOrNull(options, "after")?.let { after ->
                val body = declined

                // Nothing reads `after`'s value.
                using(ValueUse.DISCARDED) {
                    when {
                        body != null -> afterDeclined(after, body, frame)
                        raised != null -> afterRaised(after, raised, frame)
                        else -> eval(after, frame.copy())
                    }
                }
            }
        }
    }

    /**
     * The clause of a `try`'s that takes [raise], which ended the body's decline ([Declined.ended]), run: where one takes
     * it and returns, the decline ends nothing and holds what it returns; where it raises, or none takes it, the decline
     * stays ended.
     */
    private fun rescuedAfter(raise: Thrown, declined: Declined, options: ElixirAst, frame: Frame) {
        try {
            rescued(raise, options, frame)?.let { value ->
                declined.holds.add(value)
                declined.caught()
            }
        } catch (other: Declined) {
            declined.absorb(other)
            if (other.ended) declined.end(other.endedBy) else declined.caught()
        } catch (thrown: Thrown) {
            declined.end(thrown)
        }
    }

    /** `after`, where the body raised [raised]: where `after` declines, the `try` still raises it ([Declined.ended]). */
    private fun afterRaised(after: ElixirAst, raised: Thrown, frame: Frame) {
        try {
            eval(after, frame.copy())
        } catch (declined: Declined) {
            if (!declined.ended) declined.end(raised)
            throw declined
        }
    }

    /**
     * `after`, where the body [declined]: the decline holds what `after`'s calls were given, and where `after` raises,
     * or raises after a decline of its own, the `try` raises whatever the body did, which ends the decline
     * ([Declined.ended]).
     */
    private fun afterDeclined(after: ElixirAst, declined: Declined, frame: Frame) {
        try {
            eval(after, frame.copy())
        } catch (other: Declined) {
            // `after`'s value isn't the `try`'s.
            declined.absorb(other, holds = false)
            if (other.ended) declined.end(other.endedBy)
        } catch (thrown: Thrown) {
            declined.end(thrown)
        }
    }

    /** The body of the first `rescue` or `catch` clause [thrown] matches, evaluated, or `null` where none does. */
    private fun rescued(thrown: Thrown, options: ElixirAst, frame: Frame): OtpErlangObject? {
        for (arrow in optionOrNull(options, "rescue")?.let(::arrows).orEmpty()) {
            val (head, body) = arrowParts(arrow)
            val scope = frame.copy(thrown.stacktrace)

            if (thrown.kind == "error" && rescue(head.single(), thrown, scope)) return eval(body, scope)
        }

        for (arrow in optionOrNull(options, "catch")?.let(::arrows).orEmpty()) {
            val (head, body) = arrowParts(arrow)
            val (patterns, guards) = clauseHead(head)
            val scope = frame.copy(thrown.stacktrace)
            val kindPattern = when (patterns.size) {
                1 -> null
                2 -> patterns[0]
                else -> decline("a catch clause of ${patterns.size} patterns")
            }
            val values = listOfNotNull(kindPattern?.let { atom(thrown.kind) }, thrown.reason)

            if (kindPattern == null && thrown.kind != "throw") continue
            if (matches(listOfNotNull(kindPattern, patterns.last()), values, scope) && guards(guards, scope)) {
                return eval(body, scope)
            }
        }

        return null
    }

    /** Whether a `rescue` clause's [head] rescues [thrown], binding its variable in [scope] where it does. */
    private fun rescue(head: ElixirAst, thrown: Thrown, scope: Frame): Boolean {
        val reason = thrown.reason
        val (left, aliases) = rescues(head, scope)
        val erlangAliases = if (aliases == null) {
            listOf(ERLANG_ERROR)
        } else {
            seen(reason)

            val elixir = aliases.any { alias ->
                reason is OtpErlangMap && reason.get(STRUCT)?.let { equal(it, atom(alias), false, run.level) } == true &&
                    isException(reason)
            }
            val erlang = aliases.filter { erlangGuard(it) != null }

            // `rescue_guards/3` gives no guard for no aliases, so they rescue every error.
            if (aliases.isNotEmpty() && !elixir && erlang.none { erlangGuard(it)!!(reason) }) return false

            erlang.reversed()
        }

        if (isUnderscore(left)) return true

        val value = if (erlangAliases.isEmpty()) {
            reason
        } else {
            normalize(reason, rescueStacktrace(reason, thrown.stacktrace, erlangAliases))
        }

        // `elixir_erl_try` binds the variable by a `=` heading the clause's body.
        if (!matches(listOf(left), listOf(value), scope)) error(tuple(atom("badmatch"), value))

        return true
    }

    /** A rescue clause's variable and the aliases it rescues, `null` for a variable's, as `expand_rescue` reads it. */
    private fun rescues(node: ElixirAst, frame: Frame): Pair<ElixirAst, List<String>?> =
        // A macro call is the head it expanded to.
        when (val head = rescueHead(frame.expanded(node), run.level)) {
            is RescueHead.Variable -> head.variable to null
            // A macro left of `in` is the variable it expanded to.
            is RescueHead.In -> frame.expanded(head.left).let { left ->
                if (isVariable(left)) left to rescuedBy(head.right, frame) else decline("a rescue whose left isn't a variable")
            }
            RescueHead.Expanded, RescueHead.Stopped -> decline("a rescue clause's head")
        }

    /** The aliases [right] evaluates to, which `normalize_rescue` takes as an atom or a list of atoms. */
    private fun rescuedBy(right: ElixirAst, frame: Frame): List<String> {
        val value = eval(right, frame)
        val elements = if (value is OtpErlangAtom) listOf(value) else properElements(value)

        return elements?.map { (it as? OtpErlangAtom)?.atomValue() ?: decline("a rescue of a value that isn't an alias") }
            ?: decline("a rescue of a value that isn't an alias")
    }

    /** The stacktrace `normalize_rescue/5` passes `Exception.normalize/3` for [reason], rescued by [erlangAliases]. */
    private fun rescueStacktrace(
        reason: OtpErlangObject,
        stacktrace: OtpErlangObject,
        erlangAliases: List<String>,
    ): OtpErlangObject {
        val requires = REQUIRES_STACKTRACE +
            if (RESCUED_ERROR_MESSAGE_FROM_ERROR_INFO.isSufficient(run.level)) REQUIRES_STACKTRACE_FROM_ERROR_INFO else emptyList()
        val dynamic = { modules: List<String> ->
            if (modules.any { stacktraceGuard(it)(reason) }) stacktrace else EMPTY
        }

        if (ERLANG_ERROR in erlangAliases) return dynamic(requires)

        val (some, rest) = if (RESCUE_LIST_STACKTRACE_FOR_ANY.isSufficient(run.level)) {
            erlangAliases.partition { it in requires }
        } else {
            erlangAliases.takeWhile { it in requires } to erlangAliases.dropWhile { it in requires }
        }

        return when {
            some.isEmpty() -> EMPTY
            rest.isEmpty() -> stacktrace
            else -> dynamic(some)
        }
    }

    /** `erl_rescue_stacktrace_for/3`. */
    private fun stacktraceGuard(module: String): (OtpErlangObject) -> Boolean =
        if (module == "Elixir.KeyError") {
            { reason -> isRecord(reason, 2, "badkey") }
        } else {
            erlangGuard(module) ?: { false }
        }

    /** `erl_rescue_guard_for/3`: whether an Erlang error is [module]'s exception, `null` where none is. */
    private fun erlangGuard(module: String): ((OtpErlangObject) -> Boolean)? {
        val equals = { name: String -> { reason: OtpErlangObject -> reason == atom(name) } }
        val record = { size: Int, name: String -> { reason: OtpErlangObject -> isRecord(reason, size, name) } }

        return when (module) {
            "Elixir.UndefinedFunctionError" -> equals("undef")
            "Elixir.FunctionClauseError" -> equals("function_clause")
            "Elixir.SystemLimitError" -> equals("system_limit")
            "Elixir.ArithmeticError" -> equals("badarith")
            "Elixir.CondClauseError" -> equals("cond_clause")
            "Elixir.BadArityError" -> record(2, "badarity")
            "Elixir.BadFunctionError" -> record(2, "badfun")
            "Elixir.MatchError" -> record(2, "badmatch")
            "Elixir.CaseClauseError" -> record(2, "case_clause")
            "Elixir.WithClauseError" ->
                record(2, if (WITH_ELSE_CLAUSE_ERROR.isSufficient(run.level)) "else_clause" else "with_clause")
            "Elixir.TryClauseError" -> record(2, "try_clause")
            "Elixir.BadStructError" ->
                if (STRUCT_UPDATE_IS_MAP_UPDATE.isSufficient(run.level)) null else record(3, "badstruct")
            "Elixir.BadMapError" -> record(2, "badmap")
            "Elixir.BadBooleanError" -> record(3, "badbool")
            "Elixir.KeyError" -> { reason -> isRecord(reason, 2, "badkey") || isRecord(reason, 3, "badkey") }
            "Elixir.ArgumentError" -> { reason -> reason == atom("badarg") || isRecord(reason, 2, "badarg") }
            ERLANG_ERROR -> { reason ->
                val struct = !RESCUE_EXCEPTION_KEY_PRESENT.isSufficient(run.level) || (reason as? OtpErlangMap)?.get(STRUCT) != null

                !(reason is OtpErlangMap && struct && reason.get(EXCEPTION) != null)
            }
            else -> null
        }
    }

    /** Whether [map]'s `__exception__` marks it an exception, as a `rescue` of its struct's alias reads it. */
    private fun isException(map: OtpErlangMap): Boolean {
        val exception = map.get(EXCEPTION) ?: return false

        return RESCUE_EXCEPTION_KEY_PRESENT.isSufficient(run.level) || seen(exception) == TRUE
    }

    private fun isRecord(term: OtpErlangObject, size: Int, name: String): Boolean =
        term is OtpErlangTuple && term.arity() == size && seen(term.elementAt(0)) == atom(name)

    private fun evalFor(args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        // As `elixir_expand` merges a source `for`'s options with its `do` block's.
        val (cases, elements) = forOptions(args)
        // `elixir_erl_for` reads each case as it expanded, so a macro's `<-/2` is a generator.
        val qualifiers = cases.map(frame::expanded)
        val options = ElixirAst.ListNode(args.last().meta, elements)
        val body = doOption(options)
        val reduce = optionOrNull(options, "reduce")
        // `expand_for/4` expands the options first, and the generators start from their scope.
        val outer = frame.copy()
        val start = frame.copy()
        val arrows = if (reduce != null) arrows(body) else emptyList()
        // Where an option declines, the qualifiers and the body may run.
        val option = { node: ElixirAst ->
            selecting(qualifiers + arrows.ifEmpty { listOf(body) }, heads(arrows), emptyList(), start) { eval(node, start) }
        }

        if (reduce != null) {
            var acc = option(reduce)
            // An iteration that declines gives the next an accumulator that isn't known.
            val comprehended = Comprehended(false, outer, arrows, heads(arrows), { listOf(acc) }) {
                acc = unknownOf(listOf(acc) + it.holds)
            }

            try {
                comprehend(qualifiers, 0, start, comprehended) { scope ->
                    acc = clauses(arrows, listOf(acc), scope) ?: error(tuple(atom("case_clause"), acc))
                }
            } catch (declined: Declined) {
                declined.holds += acc
                throw declined
            }

            return acc
        }

        val intoNode = optionOrNull(options, "into")
        // `Collectable.into/1` runs before the generators.
        val into = intoNode?.let(option)
        val uniqNode = optionOrNull(options, "uniq")
        // `build_inline/7`: an Erlang comprehension where every generator is a bitstring's, with no `uniq: true`, into
        // `[]` or `{bin, _, []}`, which both `""` and `<<>>` translate to, each as the options expanded.
        val intoForm = intoFormOf(intoNode?.let(frame::expanded))
        val inline = (uniqNode?.let(frame::expanded) as? ElixirAst.Literal.Atom)?.name != "true" &&
            (intoForm == IntoForm.LIST || intoForm == IntoForm.BINARY) &&
            qualifiers.all { isBitstringGenerator(it) || !isListGenerator(it) }
        val collection = selecting(qualifiers + body, emptyList(), emptyList(), start) { collection(intoForm, into) }
        val uniq = uniqNode?.let { truthy(option(it)) } == true
        val values = mutableListOf<OtpErlangObject>()

        built(values) {
            comprehend(qualifiers, 0, start, Comprehended(inline, outer, listOf(body))) { scope ->
                val value = eval(body, scope)

                if (!uniq || values.none { ProgressManager.checkCanceled(); equal(it, value, true, run.level) }) {
                    collection.take(value)
                    values += value
                }
            }
        }

        return collection.collect(values)
    }

    /**
     * What a `for` collects into: [take] each result as its body gives it, after `uniq:`, and [collect] them at the
     * end, as `elixir_erl_for` builds the collection.
     */
    private class Collection(val take: (OtpErlangObject) -> Unit, val collect: (List<OtpErlangObject>) -> OtpErlangObject)

    /** A `for`'s `into:` form, as `inline_or_into/1` and `build_into/7` read it. */
    private enum class IntoForm {
        /** `[]`, or no `into:`. */
        LIST,

        /** `%{}`. */
        MAP,

        /** `""` or `<<>>`, which both translate to `{bin, _, []}`. */
        BINARY,

        /** Any other, which `Collectable.into/1` collects into. */
        COLLECTABLE,
    }

    private fun intoFormOf(form: ElixirAst?): IntoForm =
        when {
            form == null || form is ElixirAst.ListNode && form.elements.isEmpty() -> IntoForm.LIST
            isCall(form, "%{}", 0) -> IntoForm.MAP
            form is ElixirAst.Literal.Binary && form.bytes.isEmpty() || isCall(form, "<<>>", 0) -> IntoForm.BINARY
            else -> IntoForm.COLLECTABLE
        }

    /**
     * [IntoForm.LIST] and [IntoForm.BINARY] collect inline, [IntoForm.MAP] into a list `maps:from_list/1` converts at
     * the end, and [IntoForm.COLLECTABLE] through `Collectable.into/1`, which takes each result as it comes.
     */
    private fun collection(intoForm: IntoForm, into: OtpErlangObject?): Collection {
        val fromList = { results: List<OtpErlangObject> -> natives.call("maps", "from_list", listOf(list(results)))!! }

        return when (intoForm) {
            IntoForm.LIST -> Collection({}, ::list)
            IntoForm.MAP -> Collection({}, fromList)
            // `build_inline_each/7`.
            IntoForm.BINARY ->
                Collection({ if (seen(it) !is OtpErlangBitstr) error(tuple(atom("badarg"), it)) }, bits::concat)
            // A collector rejects a result with an `ArgumentError` from 1.17, a `FunctionClauseError` before.
            IntoForm.COLLECTABLE -> when (into) {
                is OtpErlangList if into.arity() == 0 -> Collection({}, ::list)
                is OtpErlangMap if into.arity() == 0 ->
                    Collection({ if ((seen(it) as? OtpErlangTuple)?.arity() != 2) decline("for into a map: %s", it) }, fromList)
                is OtpErlangBinary if into.size() == 0 ->
                    Collection({ if (seen(it) !is OtpErlangBitstr) decline("for into a binary: %s", it) }, bits::concat)
                // `Collectable.into/1` is given it.
                else -> calling(listOf(into!!)) { decline("for into: %s", into) }
            }
        }
    }

    /**
     * How `elixir_erl_for` translates a `for`: as an Erlang comprehension or not, and the scope the `for` starts in; and
     * its [body], which runs for each element the qualifiers reach, with [patterns], each bound to an unknown holding
     * what [held] gives when a qualifier declines, and [declines], told what an iteration that declines held.
     */
    private class Comprehended(
        val inline: Boolean,
        val outer: Frame,
        val body: List<ElixirAst>,
        val patterns: List<ElixirAst> = emptyList(),
        val held: () -> List<OtpErlangObject> = { emptyList() },
        val declines: (Declined) -> Unit = {},
    )

    /**
     * [select], a qualifier of this comprehension, [selecting] [rest]: where it declines, [patterns] hold [values] and
     * what [Comprehended.held] gives then, the accumulator the iterations before it left.
     */
    private inline fun <T> Comprehended.qualifying(
        rest: List<ElixirAst>,
        patterns: List<ElixirAst>,
        values: List<OtpErlangObject>,
        frame: Frame,
        select: () -> T,
    ): T =
        selecting(rest, patterns, values, frame) {
            try {
                select()
            } catch (declined: Declined) {
                declined.holds += held()
                throw declined
            }
        }

    /** [emit] once per element the qualifiers from [index] reach, with a scope binding each generator's pattern. */
    private fun comprehend(qualifiers: List<ElixirAst>, index: Int, frame: Frame, comprehended: Comprehended, emit: (Frame) -> Unit) {
        ProgressManager.checkCanceled()

        if (index == qualifiers.size) return emit(frame)

        val qualifier = qualifiers[index]
        // Where a qualifier declines, the qualifiers after it and the body may run.
        val rest = qualifiers.subList(index + 1, qualifiers.size) + comprehended.body
        // An iteration that declines doesn't end the loop.
        val turn = InTurn<Thrown>()

        if (isListGenerator(qualifier)) {
            val (head, enumerable) = (qualifier as ElixirAst.Call).arguments!!
            val (patterns, guards) = clauseHead(ElixirAst.ListNode(head.meta, listOf(head)))
            val heads = listOf(head) + comprehended.patterns
            val value = comprehended.qualifying(rest, heads, emptyList(), frame) { eval(enumerable, frame) }

            for (element in comprehended.qualifying(rest, heads, listOf(value), frame) { natives.enumerate(value) }) {
                turn.iterate(comprehended.declines) {
                    val scope = frame.copy()

                    val qualifies = comprehended.qualifying(rest, heads, listOf(element), frame) {
                        matches(patterns, listOf(element), scope, fresh = true) && filters(guards, scope)
                    }

                    if (qualifies) {
                        comprehend(qualifiers, index + 1, scope, comprehended, emit)
                    }
                }
            }
        } else if (isBitstringGenerator(qualifier)) {
            val generator = (qualifier as ElixirAst.Call).arguments!!
            val (segments, bitstring) = if (frame.source) {
                val (last, bitstring) = (generator.last() as ElixirAst.Call).arguments!!

                generator.dropLast(1) + last to bitstring
            } else {
                // Expanded, it is `<<(<<segments>>) <- bitstring>>`.
                val (left, bitstring) = (generator.single() as ElixirAst.Call).arguments!!

                ((left as? ElixirAst.Call)?.takeIf { isCall(it, "<<>>", it.arguments?.size ?: -1) }?.arguments
                    ?: decline("a bitstring generator that isn't expanded")) to bitstring
            }

            val heads = segments + comprehended.patterns
            val value = comprehended.qualifying(rest, heads, emptyList(), frame) { eval(bitstring, frame) }

            comprehended.qualifying(rest, heads, listOf(value), frame) {
                bits.generate(segments, value, frame, comprehended.inline, comprehended.outer) { scope ->
                    turn.iterate(comprehended.declines) { comprehend(qualifiers, index + 1, scope, comprehended, emit) }
                }
            }
        } else if (comprehended.qualifying(rest, comprehended.patterns, emptyList(), frame) { truthy(eval(qualifier, frame)) }) {
            comprehend(qualifiers, index + 1, frame, comprehended, emit)
        }

        turn.declined?.let { throw it }
    }

    private fun evalWith(args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        // From `Dbgi`, `expand_with/3` has merged the options into the last list.
        val (clauses, elements) = if (frame.source) withOptions(args, run.level) else splitOptions(args, false)
        val options = ElixirAst.ListNode(args.last().meta, elements)
        val scope = frame.copy()
        val elses = optionOrNull(options, "else")?.let(::arrows).orEmpty()

        for ((index, clause) in clauses.withIndex()) {
            if (!isCall(clause, "<-", 2)) {
                // Where it declines, the clauses after it, the `do` and the `else` clauses may run.
                val rest = buildList {
                    addAll(clauses.subList(index + 1, clauses.size))
                    add(doOption(options))
                    addAll(elses)
                }

                selecting(rest, heads(elses), emptyList(), scope) {
                    eval(clause, scope)
                }
                continue
            }

            val (head, right) = (clause as ElixirAst.Call).arguments!!
            val (patterns, guards) = clauseHead(ElixirAst.ListNode(head.meta, listOf(head)))
            // Where it declines, the clauses after it, the `do` and the `else` clauses may run.
            val rest = buildList {
                addAll(clauses.subList(index + 1, clauses.size))
                add(doOption(options))
                addAll(elses)
            }
            val matched = selecting(rest, listOf(head) + heads(elses), emptyList(), scope) {
                val value = eval(right, scope)

                (matches(patterns, listOf(value), scope) && guards(guards, scope)) to value
            }
            val value = matched.second

            if (!matched.first) {
                // Before 1.13 `expand_with/2` rewrites `var <- e` to `var = e`.
                if (!GENERATOR_RIGHT_SIDE_SCOPED.isSufficient(run.level) && isVariable(head)) {
                    error(tuple(atom("badmatch"), value))
                }

                if (optionOrNull(options, "else") == null) return value
                // `translate_with_else/3` makes a lone variable clause the last of the `case`, not one of its own.
                val error = when {
                    elses.singleOrNull()?.let(::isVariableClause) == true -> "case_clause"
                    WITH_ELSE_CLAUSE_ERROR.isSufficient(run.level) -> "else_clause"
                    else -> "with_clause"
                }

                return clauses(elses, listOf(value), frame) ?: error(tuple(atom(error), value))
            }
        }

        return eval(doOption(options), scope)
    }

    /** Whether [arrow]'s head is one variable, with no guard. */
    private fun isVariableClause(arrow: ElixirAst): Boolean {
        val (patterns, guards) = clauseHead(arrowParts(arrow).first)

        return guards.isEmpty() && patterns.singleOrNull()?.let(::isVariable) == true
    }

    /**
     * The body of the first of [arrows] whose head matches [values], evaluated, or `null` where none does; [fresh] as
     * [matches] takes it. Where [subject] declined, [values] hold what it would have given, and only an arm after one
     * passed over is taken ([Arms]).
     */
    private fun clauses(
        arrows: List<ElixirAst>,
        values: List<OtpErlangObject>,
        frame: Frame,
        fresh: Boolean = false,
        subject: Declined? = null,
    ): OtpErlangObject? {
        val arms = Arms(subject) { soleArm(arrows.map { arrowParts(it).second }, frame) }

        for ((index, arrow) in arrows.withIndex()) {
            val (head, body) = arrowParts(arrow)
            val (patterns, guards) = clauseHead(head)

            if (patterns.size != values.size) continue

            val scope = frame.copy()
            val rest = arrows.subList(index, arrows.size)
            val test = {
                selecting(rest, heads(rest), values, frame) { matches(patterns, values, scope, fresh) && guards(guards, scope) }
            }

            if (arms.tested(body, scope, test)) return arms.taken(body, scope)
        }

        arms.none()

        return null
    }

    /**
     * The arms of a loop that takes the first whose test holds. An arm whose test declines is passed over where its
     * value only raises ([onlyRaises]) and the decline is [passable]: a project that compiles doesn't take it, so the
     * next arm is tried, and one taken after it assumes the raise away. Where every other arm only raises, whichever
     * order they come in, [sole] is the one left: it is taken, on the same assumption, where its test or a [passable]
     * [subject] declines. Where [subject], the decline that gave the values the arms test, is set, only an arm after one
     * passed over, or [sole], is taken; otherwise the loop declines as [subject] did.
     */
    private inner class Arms(private val subject: Declined? = null, sole: () -> ElixirAst? = { null }) {
        private val sole by lazy(LazyThreadSafetyMode.NONE, sole)

        /** The decline of the first arm passed over. */
        private var passed: Declined? = null

        /** What [test], of the arm whose value is [body], gives: `false` where it declines and the arm is passed over. */
        fun tested(body: ElixirAst?, frame: Frame, test: () -> Boolean): Boolean =
            try {
                test()
            } catch (declined: Declined) {
                val left = body != null && body === sole

                if (subject?.let(::passable) == false || !passable(declined) || !left && !onlyRaises(body, frame)) {
                    throw subject ?: declined
                }

                run.unfollowedCodeRan()
                passed = passed ?: declined
                left
            }

        /** [body], of the arm taken, evaluated in [frame]. */
        fun taken(body: ElixirAst, frame: Frame): OtpErlangObject {
            if (passed == null && subject != null && passable(subject) && body === sole) {
                run.unfollowedCodeRan()
                passed = subject
            }
            if (passed == null) subject?.let { throw it } else assumeNoRaise()

            return eval(body, frame)
        }

        /** No arm is taken: where one was passed over, or the values weren't known, the loop declines. */
        fun none() {
            (subject ?: passed)?.let { throw it }
        }
    }

    /** Whether [body] only raises: it is a call of a native that never returns ([Natives.neverReturns]). */
    private fun onlyRaises(body: ElixirAst?, frame: Frame): Boolean {
        val call = body?.let(frame::expanded) as? ElixirAst.Call ?: return false
        val (module, function) = remoteOf(call, frame) ?: return false

        return natives.neverReturns(module, function, call.arguments?.size ?: return false)
    }

    /** The one of [bodies], each an arm's value, that doesn't only raise, where there are others and each of them does. */
    private fun soleArm(bodies: List<ElixirAst?>, frame: Frame): ElixirAst? =
        bodies.filterNot { onlyRaises(it, frame) }.singleOrNull()?.takeIf { bodies.size > 1 }

    /** Whether [declined] leaves nothing a pass-over would drop: it neither stops nor ended, and was given no module. */
    private fun passable(declined: Declined) = !declined.stops && !declined.ended && declined.given.none

    /** The module and function [call] runs, where it is a remote call of a module its code names. */
    private fun remoteOf(call: ElixirAst.Call, frame: Frame): Pair<String, String>? {
        if (frame.source) return (frame.module.dispatches.of(call) as? Dispatched.Remote)?.let { it.module to it.name }

        val callee = call.callee as? ElixirAst.Call ?: return null
        if (!isCall(callee, ".", 2)) return null
        val (left, right) = callee.arguments!!.map { (it as? ElixirAst.Literal.Atom)?.name ?: return null }

        return left to right
    }

    /**
     * Whether each of [patterns] matches its value, binding their variables in [frame] where all do: afresh where
     * [fresh], as a fun head and a generator bind them.
     */
    private fun matches(
        patterns: List<ElixirAst>,
        values: List<OtpErlangObject>,
        frame: Frame,
        fresh: Boolean = false,
    ): Boolean {
        val pending = PatternBindings(fresh)

        if (!patterns.indices.all { match(patterns[it], values[it], frame, pending) }) return false

        frame.vars.putAll(pending.vars)

        return true
    }

    /** Whether [pattern] matches [value], binding its variables in [pending]. */
    internal fun match(pattern: ElixirAst, value: OtpErlangObject, frame: Frame, pending: PatternBindings): Boolean {
        ProgressManager.checkCanceled()

        if (isVariable(pattern)) {
            if (isUnderscore(pattern)) return true

            val binding = frame.binding(pattern) ?: decline("binds a variable with no version")
            // A version already bound is the same Erlang variable, so it is matched, unless the construct binds afresh.
            val bound = pending.vars[binding] ?: frame.vars[binding]?.takeUnless { pending.fresh }
                ?: return true.also { pending.vars[binding] = value }

            return equal(bound, value, true, run.level)
        }
        seen(value)

        return when (pattern) {
            is ElixirAst.Literal, is ElixirAst.Alias, is ElixirAst.Value -> equal(eval(pattern, frame), value, true, run.level)
            is ElixirAst.Tuple -> matchTuple(pattern.elements, value, frame, pending)
            is ElixirAst.ListNode -> matchList(pattern.elements, value, frame, pending)
            is ElixirAst.Call -> matchCall(pattern, value, frame, pending)
            is ElixirAst.Block, is ElixirAst.Placeholder -> decline("a ${pattern.javaClass.simpleName} pattern")
        }
    }

    private fun matchTuple(
        patterns: List<ElixirAst>,
        value: OtpErlangObject,
        frame: Frame,
        pending: PatternBindings,
    ): Boolean =
        value is OtpErlangTuple && value.arity() == patterns.size &&
            patterns.indices.all { match(patterns[it], value.elementAt(it), frame, pending) }

    private fun matchList(
        patterns: List<ElixirAst>,
        value: OtpErlangObject,
        frame: Frame,
        pending: PatternBindings,
    ): Boolean {
        val (elements, tail) = cells(value) ?: return false
        val last = patterns.lastOrNull()

        if (last != null && isCall(last, "|", 2)) {
            val (head, tailPattern) = (last as ElixirAst.Call).arguments!!
            val heads = patterns.dropLast(1) + head

            if (elements.size < heads.size) return false
            if (!heads.indices.all { match(heads[it], elements[it], frame, pending) }) return false

            return match(tailPattern, cons(elements.drop(heads.size), tail ?: EMPTY), frame, pending)
        }

        return tail == null && elements.size == patterns.size &&
            patterns.indices.all { match(patterns[it], elements[it], frame, pending) }
    }

    private fun matchCall(
        pattern: ElixirAst.Call,
        value: OtpErlangObject,
        frame: Frame,
        pending: PatternBindings,
    ): Boolean {
        val args = pattern.arguments ?: decline("a pattern call")

        return when ((pattern.callee as? ElixirAst.Literal.Atom)?.name) {
            "^" -> {
                val pinned = args.single() as? ElixirAst.Call ?: decline("a pin of a non-variable")

                equal(frame.binding(pinned)?.let(frame.vars::get) ?: decline("a pin of an unbound variable"), value, true, run.level)
            }
            "=" -> args.all { match(it, value, frame, pending) }
            "{}" -> matchTuple(args, value, frame, pending)
            "%{}" -> matchMap(args, value, frame, pending)
            "%" -> {
                val (name, fields) = args
                val struct = (value as? OtpErlangMap)?.get(STRUCT)?.let(::seen) ?: return false

                if (isVariable(name) && struct !is OtpErlangAtom) return false

                match(name, struct, frame, pending) &&
                    matchMap((fields as? ElixirAst.Call)?.arguments ?: decline("a struct pattern's fields"), value, frame, pending)
            }
            "<<>>" -> bits.match(args, value, frame, pending)
            else ->
                when (val dispatched = frame.module.dispatches.of(pattern)) {
                    is Dispatched.Macro -> match(dispatched.output, value, frame, pending)
                    // An attribute or a signed integer, whose term is a literal pattern.
                    is Dispatched.Value -> equal(otpOf(dispatched.value), value, true, run.level)
                    else -> decline("a pattern ${pattern.callee}")
                }
        }
    }

    private fun matchMap(
        args: List<ElixirAst>,
        value: OtpErlangObject,
        frame: Frame,
        pending: PatternBindings,
    ): Boolean {
        val map = value as? OtpErlangMap ?: return false

        return args.flatMap { arg -> (arg as? ElixirAst.ListNode)?.elements ?: listOf(arg) }.all { pair ->
            val (key, valuePattern) = (pair as? ElixirAst.Tuple)?.elements?.takeIf { it.size == 2 } ?: decline("a map pattern pair")
            val keyValue = if (isCall(key, "^", 1)) {
                frame.binding((key as ElixirAst.Call).arguments!!.single())?.let(frame.vars::get)
                    ?: decline("a pin of an unbound variable")
            } else {
                eval(key, frame)
            }
            val found = valueAt(map, keyValue, run.level)

            found != null && match(valuePattern, found, frame, pending)
        }
    }

    /** Whether any of [guards] holds, as a guard does: one that raises doesn't. */
    private fun guards(guards: List<ElixirAst>, frame: Frame): Boolean =
        guards.isEmpty() || guards.any { guard ->
            val value = guardValue(guard, frame) ?: FALSE

            seen(value)

            value == TRUE
        }

    /**
     * Whether a generator's [guards] keep its element, as `elixir_erl_for:translate_gen/5` runs them: not as a guard
     * but as a filter, joined by `orelse`, in which `true` keeps it and `false` skips it. Any other value raises, as an
     * error in them does.
     */
    private fun filters(guards: List<ElixirAst>, frame: Frame): Boolean {
        for ((index, guard) in guards.withIndex()) {
            when (val value = seen(eval(guard, frame))) {
                TRUE -> return true
                FALSE -> {}
                else -> error(tuple(atom(if (index < guards.lastIndex) "badarg" else "case_clause"), value))
            }
        }

        return guards.isEmpty()
    }

    /** The value of `do:` in the keyword list [keywords]. */
    private fun doOption(keywords: ElixirAst): ElixirAst = optionOrNull(keywords, "do") ?: decline("no do:")

    private fun optionOrNull(keywords: ElixirAst, key: String): ElixirAst? =
        (keywords as? ElixirAst.ListNode ?: decline("options that aren't a keyword list"))
            .elements.firstOrNull { keyOf(it) == key }?.let { (it as ElixirAst.Tuple).elements[1] }

    private fun arrows(value: ElixirAst): List<ElixirAst> =
        (value as? ElixirAst.ListNode)?.elements ?: decline("clauses that aren't a list")

    /** The head's patterns and the body of the `->` [arrow]. */
    private fun arrowParts(arrow: ElixirAst): Pair<List<ElixirAst>, ElixirAst> {
        val (head, body) = (arrow as? ElixirAst.Call)?.takeIf { isCall(it, "->", 2) }?.arguments ?: decline("a clause that isn't ->")

        return ((head as? ElixirAst.ListNode)?.elements ?: decline("a clause head that isn't a list")) to body
    }

    /** [head]'s patterns and guards, as `elixir_utils:extract_splat_guards/1` reads them. */
    private fun clauseHead(head: List<ElixirAst>): Pair<List<ElixirAst>, List<ElixirAst>> {
        val splat = head.singleOrNull()?.let(::whenArguments)

        return if (splat != null) splat.dropLast(1) to orGuards(splat.last()) else head to emptyList()
    }

    private fun clauseHead(head: ElixirAst.ListNode) = clauseHead(head.elements)

    private companion object {
        val STRUCT = atom("__struct__")
        val EXCEPTION = atom("__exception__")
        val CONTEXT = atom("context")
        val ONE = integer(1)
        const val ERLANG_ERROR = "Elixir.ErlangError"
        val REQUIRES_STACKTRACE = listOf("Elixir.FunctionClauseError", "Elixir.UndefinedFunctionError", "Elixir.KeyError")
        val REQUIRES_STACKTRACE_FROM_ERROR_INFO = listOf("Elixir.ArgumentError", "Elixir.SystemLimitError")
    }
}

/** `is_list/1`, true of an improper list too. */
private fun isList(term: OtpErlangObject) = term is OtpErlangList || term is OtpErlangString

/** `[_ | _]`. */
private fun isNonEmptyList(term: OtpErlangObject) =
    term is OtpErlangList && term.arity() > 0 || term is OtpErlangString && term.stringValue().isNotEmpty()

/**
 * Whether `module.name/arity` is `:erlang.andalso/2` or `orelse/2`, which evaluate their right only where the left
 * doesn't decide.
 */
internal fun shortCircuits(module: String, name: String, arity: Int): Boolean =
    module == "erlang" && arity == 2 && (name == "andalso" || name == "orelse")
