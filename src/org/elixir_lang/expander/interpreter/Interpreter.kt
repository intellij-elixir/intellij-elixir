package org.elixir_lang.expander.interpreter

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangBitstr
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangExternalFun
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.expander.DefinitionTable
import org.elixir_lang.expander.Dispatch
import org.elixir_lang.expander.Env
import org.elixir_lang.expander.ExState
import org.elixir_lang.expander.Linify
import org.elixir_lang.expander.LinifyClause
import org.elixir_lang.expander.LinifyRight
import org.elixir_lang.expander.MacroExpanded
import org.elixir_lang.expander.ModuleClauses
import org.elixir_lang.expander.ModuleClauses.Dispatched
import org.elixir_lang.expander.ModuleExports
import org.elixir_lang.expander.Run
import org.elixir_lang.expander.Summary
import org.elixir_lang.expander.forOptions
import org.elixir_lang.expander.isBitstringGenerator
import org.elixir_lang.expander.isCall
import org.elixir_lang.expander.isListGenerator
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
import org.elixir_lang.expander.splitOptions
import org.elixir_lang.expander.unlessTooDeep
import org.elixir_lang.expander.whenArguments
import org.elixir_lang.expander.withOptions
import org.elixir_lang.language_level.ElixirLanguageFeature.FIELD_OF_NON_MAP_RAISES_BADKEY
import org.elixir_lang.language_level.ElixirLanguageFeature.FN_CLAUSE_ERROR_IS_FUNCTION_CLAUSE
import org.elixir_lang.language_level.ElixirLanguageFeature.GENERATOR_RIGHT_SIDE_SCOPED
import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_ARGUMENTS_STOP_GENERATED
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
        val output = try {
            unlessTooDeep({ return null }) { Evaluation(dispatch, node, state, env, run, stopGenerated).output() }
        } catch (_: Declined) {
            return null
        }

        return object : Summary.Rewrite() {
            override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run) = output
        }
    }
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

internal class Evaluation(
    private val dispatch: Dispatch,
    private val call: ElixirAst.Call,
    private val state: ExState,
    private val env: Env,
    val run: Run,
    private val stopGenerated: Boolean,
) {
    /** Each term made from an argument's node, so the output keeps the nodes the macro passed through. */
    private val nodes = IdentityHashMap<OtpErlangObject, ElixirAst>()

    /** The `{M, F, Arity, []}` of each call being evaluated, innermost first. */
    private val stack = ArrayDeque<OtpErlangTuple>()

    /** The atoms the evaluation has seen, which `binary_to_existing_atom` finds. */
    val atoms = HashSet<String>()

    val natives = Natives(this)

    private val bits = Bits(this)

    /** `__CALLER__`, one term, so `Macro.expand/2` knows the env it is given. */
    private val caller by lazy { caller() }

    fun output(): Summary.Output {
        val module = run.clausesOf(dispatch.receiver, local = dispatch.kind == Dispatch.Kind.LOCAL_MACRO)
            ?: decline("no clauses of ${dispatch.receiver}")
        val definition = module.definitions[NameArity(dispatch.name, dispatch.arity)]
            ?.takeIf { it.kind.macro }
            ?: decline("${dispatch.receiver} has no macro ${dispatch.name}/${dispatch.arity}")
        val marks = stopGenerated && MACRO_ARGUMENTS_STOP_GENERATED.isSufficient(run.level)
        val args = call.arguments.orEmpty().map { arg -> termOf(arg).let { if (marks) stopGenerated(it, arg) else it } }
        val function = ModuleExports.macroExport(NameArity(dispatch.name, args.size))
        val result = try {
            invoke(module, definition, function.name, args, function.arity).also(::linify)
        } catch (thrown: Thrown) {
            if (thrown.kind != "error") decline("the body ${thrown.kind}s")

            return Summary.Output.Raised(exceptionModule(thrown))
        }

        return Summary.Output.Built(
            ElixirAst.fromOtp(result, call.meta.origin, values = true, known = nodes::get)
                ?: decline("the result isn't quoted")
        )
    }

    private val linifier by lazy { Linify(dispatch.receiver, run.level) }

    /**
     * Raises where `elixir_quote:linify_with_context_counter/3` raises on [term], the macro's result, before
     * `elixir_dispatch:expand_quoted/7` expands it: at the head [Linify] chooses, each `lists` call it makes on a node's
     * metadata, and its comprehension over a list. What linify gives back is `linifyWithContextCounter`'s.
     */
    private fun linify(term: OtpErlangObject) {
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
                    linify(left, meta, right)
                }
            }
            is OtpErlangTuple if term.arity() == 2 -> term.elements().forEach(::linify)
            is OtpErlangList -> {
                term.elements().forEach(::linify)
                term.lastTail?.let { error(tuple(atom("bad_generator"), it)) }
            }
        }
    }

    /** `do_tuple_linify`: [left], then [meta] as the line is written into it, then [right]. */
    private fun linify(left: OtpErlangObject, meta: OtpErlangObject, right: OtpErlangObject) {
        if (linifier.readsStopGenerated) keyfind("stop_generated", meta)
        linify(left)
        if (lineOf(call.meta) != 0) keymember("line", meta)
        linify(right)
    }

    private fun keyfind(key: String, meta: OtpErlangObject) =
        natives.call("lists", "keyfind", listOf(atom(key), ONE, meta))

    private fun keymember(key: String, meta: OtpErlangObject) {
        natives.call("lists", "keymember", listOf(atom(key), ONE, meta))
    }

    /** The module of the exception Elixir raises for [thrown], an `error`. */
    private fun exceptionModule(thrown: Thrown): String {
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
     * `Macro.expand/2`, or with [once] `Macro.expand_once/2`, of [ast] in [env], expanded where the call is. An env
     * other than `__CALLER__` declines.
     */
    fun macroExpand(ast: OtpErlangObject, env: OtpErlangObject, once: Boolean): OtpErlangObject {
        if (env !== caller) decline("Macro.expand/2 in an env other than __CALLER__")

        val node = ElixirAst.fromOtp(ast, call.meta.origin, values = true, known = nodes::get)
            ?: decline("Macro.expand/2 of a term it can't read")
        val expanded = if (once) macroExpandOnce(node, state, this.env, run) else macroExpand(node, state, this.env, run)

        return when (expanded) {
            // A node that didn't expand is the term given, metadata the node doesn't hold included.
            is MacroExpanded.Node -> if (expanded.expanded) termOf(expanded.node) else ast
            MacroExpanded.Dir -> decline("Macro.expand/2 of __DIR__")
            is MacroExpanded.Stopped -> decline("Macro.expand/2 stops")
        }
    }

    /** [node] as the term a macro is given, each of its terms remembering the node it came from. */
    private fun termOf(node: ElixirAst): OtpErlangObject =
        node.toOtp(ParserOptions.DEFAULT) { each, term ->
            when (each) {
                is ElixirAst.Placeholder -> decline("an argument holds a placeholder")
                is ElixirAst.Literal.Atom -> atoms += each.name
                else -> {}
            }

            nodes[term] = each
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
            for (clause in clauses) {
                if (clause.args.size != args.size) continue

                val frame = Frame(module)

                if (matches(clause.args, args, frame) && guards(clause.guards, frame)) {
                    return eval(clause.body ?: decline("$function/${args.size} matches a clause that stopped expanding"), frame)
                }
            }
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
     * where they are a list, and [errorInfo] at the end of its location (`erts_build_mfa_item`).
     */
    fun error(reason: OtpErlangObject, args: OtpErlangObject, errorInfo: OtpErlangMap?): Nothing {
        val (module, function, arity, location) = stack.first().elements()
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

    /** Raises [module]'s exception, its struct with [fields] put. */
    fun raise(module: String, vararg fields: Pair<String, OtpErlangObject>): Nothing {
        val struct = remote(atom(module), "__struct__", emptyList()) as? OtpErlangMap ?: decline("$module has no struct")

        error(mapOf(entries(struct) + fields.map { (key, value) -> atom(key) to value }, run.level))
    }

    /** [expr] read as a guard reads it in [frame], `null` where it raises, which fails its guard or its pattern. */
    internal fun guardValue(expr: ElixirAst, frame: Frame): OtpErlangObject? =
        try {
            eval(expr, frame.guarded())
        } catch (_: Thrown) {
            null
        }

    internal fun eval(node: ElixirAst, frame: Frame): OtpErlangObject {
        ProgressManager.checkCanceled()

        return when (node) {
            is ElixirAst.Literal.Atom -> atom(node.name).also { atoms += node.name }
            is ElixirAst.Literal.Integer -> integer(node.value)
            // The lowering reads a float past a double's range, which Elixir's tokenizer refuses, as infinite.
            is ElixirAst.Literal.Float ->
                OtpErlangDouble(node.value.takeIf(Double::isFinite) ?: decline("a float past a double's range"))
            is ElixirAst.Literal.Binary -> OtpErlangBinary(node.bytes)
            is ElixirAst.ListNode -> evalList(node.elements, frame)
            is ElixirAst.Tuple -> OtpErlangTuple(node.elements.map { eval(it, frame) }.toTypedArray())
            is ElixirAst.Block -> block(node.expressions, frame)
            is ElixirAst.Alias -> if (frame.source) value(node, frame) else decline("an alias in debug info")
            is ElixirAst.Placeholder -> decline("a placeholder")
            is ElixirAst.Call -> evalCall(node, frame)
            is ElixirAst.Value -> node.term
        }
    }

    private fun block(expressions: List<ElixirAst>, frame: Frame): OtpErlangObject {
        var value: OtpErlangObject = NIL

        for (expression in expressions) value = eval(expression, frame)

        return value
    }

    private fun evalList(elements: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val last = elements.lastOrNull()

        if (last != null && isCall(last, "|", 2)) {
            val (head, tail) = (last as ElixirAst.Call).arguments!!
            val values = elements.dropLast(1).map { eval(it, frame) } + eval(head, frame)

            return cons(values, eval(tail, frame))
        }

        return list(elements.map { eval(it, frame) })
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
            val function = eval((callee as ElixirAst.Call).arguments!!.single(), frame)

            return apply(function, args.map { eval(it, frame) })
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
            "__ENV__" if frame.source -> value(node, frame)
            // Before 1.15, the local call its expansion made of it.
            else if frame.source && frame.module.dispatches.of(node) != null -> dispatched(name, emptyList(), node, frame)
            else -> decline("reads $name, which isn't bound here")
        }
    }

    /** `__CALLER__`: the env at the call, with the fields of the leg's `Macro.Env`, each one [Env] doesn't hold [Unknown]. */
    private fun caller(): OtpErlangObject {
        val struct = remote(atom("Elixir.Macro.Env"), "__struct__", emptyList()) as? OtpErlangMap ?: decline("Macro.Env's fields")
        val fields = struct.keys().map { (it as OtpErlangAtom).atomValue() } - "__struct__"
        val held = mapOf(
            "module" to atom(env.module ?: "nil"),
            "function" to (env.function?.let { tuple(atom(it.name), integer(it.arity.toLong())) } ?: NIL),
            "context" to when (env.context) {
                Env.Context.NONE -> NIL
                Env.Context.MATCH -> atom("match")
                Env.Context.GUARD -> atom("guard")
            },
            "context_modules" to list(env.contextModules.map(::atom)),
            "line" to integer(lineOf(call.meta).toLong()),
            "file" to (run.file?.let(::binary) ?: UnknownBinary),
        )

        return mapOf(
            listOf(atom("__struct__") to atom("Elixir.Macro.Env")) + fields.map { atom(it) to (held[it] ?: Unknown) },
            run.level,
        )
    }

    /** `Left.right(args)`: a remote call, or a field of a map. */
    private fun dot(callee: ElixirAst.Call, node: ElixirAst.Call, args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val (left, right) = callee.arguments!!
        val name = (right as? ElixirAst.Literal.Atom)?.name ?: decline("a remote call whose name isn't an atom")

        if (frame.source) {
            when (val dispatched = frame.module.dispatches.of(node)) {
                is Dispatched.Remote -> return remoteCall(atom(dispatched.module), dispatched.name, args, frame)
                is Dispatched.Macro -> return eval(dispatched.output, frame)
                is Dispatched.Value -> return otpOf(dispatched.value)
                Dispatched.Apply -> {}
                else -> decline("a remote call its expansion didn't reach")
            }
        }

        if (args.isEmpty() && isFieldReceiver(left, frame)) return field(eval(left, frame), name, node.meta, frame)

        return remoteCall(eval(left, frame), name, args, frame)
    }

    /** `receiver.name(args)`, `:erlang.andalso/2` and `orelse/2` evaluating their right only where it is needed. */
    private fun remoteCall(receiver: OtpErlangObject, name: String, args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        if (receiver == ERLANG && args.size == 2 && (name == "andalso" || name == "orelse")) {
            val value = eval(args[0], frame)

            seen(value)
            if (value != TRUE && value != FALSE) error(tuple(atom("badarg"), value))

            return if ((value == TRUE) == (name == "andalso")) eval(args[1], frame) else value
        }

        return remote(receiver, name, args.map { eval(it, frame) })
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

    private fun field(value: OtpErlangObject, name: String, meta: Meta, frame: Frame): OtpErlangObject {
        val key = atom(name)

        if (frame.guard) return natives.mapGet(key, value)
        seen(value)
        (value as? OtpErlangMap)?.get(key)?.let { return it }

        if (!FIELD_OF_NON_MAP_RAISES_BADKEY.isSufficient(run.level)) {
            if (value is OtpErlangMap) error(tuple(atom("badkey"), key, value))

            return remote(value, name, emptyList())
        }

        if ((metaValue(meta, "no_parens") as? Meta.Value.Atom)?.name != "true") return remote(value, name, emptyList())

        val badmap = FIELD_OF_NON_MAP_RAISES_BADMAP.isSufficient(run.level)

        return when {
            value is OtpErlangMap -> error(tuple(atom("badkey"), key, value))
            value is OtpErlangAtom && value.atomValue() !in setOf("nil", "true", "false") -> remote(value, name, emptyList())
            badmap -> error(tuple(atom("badmap"), value))
            else -> error(tuple(atom("badkey"), key, value))
        }
    }

    /** A call of [name], a special form at the arity `elixir_expand` and `translate/3` take it, else a call. */
    private fun named(name: String, args: List<ElixirAst>, node: ElixirAst.Call, frame: Frame): OtpErlangObject =
        when (name) {
            "=" if args.size == 2 -> {
                val value = eval(args[1], frame)

                if (!matches(listOf(args[0]), listOf(value), frame)) error(tuple(atom("badmatch"), value))

                value
            }
            "{}" -> OtpErlangTuple(args.map { eval(it, frame) }.toTypedArray())
            "%{}" -> map(args, frame)
            "%" if args.size == 2 -> struct(args, frame)
            "<<>>" -> bits.build(args, frame)
            "__block__" -> block(args, frame)
            "case" if args.size == 2 -> {
                val value = eval(args[0], frame)
                val options = (frame.module.dispatches.of(node) as? Dispatched.Clauses)?.options ?: args[1]

                clauses(arrows(doOption(options)), listOf(value), frame) ?: error(tuple(atom("case_clause"), value))
            }
            "cond" if args.size == 1 -> cond(arrows(doOption(args[0])), frame)
            "fn" -> fn(args, frame)
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
            "receive" if args.size == 1 -> decline("receive")
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
            is Dispatched.Macro -> eval(dispatched.output, frame)
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
        val values = args.map { eval(it, frame) }

        return if (kind.macro) {
            val function = ModuleExports.macroExport(NameArity(name, args.size))

            invoke(frame.module, definition, function.name, values, function.arity)
        } else {
            invoke(frame.module, definition, name, values)
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

    /** An attribute's [value], as the escaped value `@` gives in a function evaluates to. */
    private fun otpOf(value: Term): OtpErlangObject {
        ProgressManager.checkCanceled()

        return when (value) {
            is Term.Atom -> atom(value.name).also { atoms += value.name }
            is Term.Integer -> integer(value.value)
            is Term.Binary -> value.bytes?.let(::OtpErlangBinary) ?: UnknownBinary
            is Term.List -> cons(value.elements.map(::otpOf), value.tail?.let(::otpOf) ?: EMPTY)
            is Term.Pair -> tuple(otpOf(value.first), otpOf(value.second))
            is Term.Tuple -> OtpErlangTuple(value.elements.map(::otpOf).toTypedArray())
            else -> Unknown
        }
    }

    /** A call of a function of the clause's own module. */
    private fun local(name: String, args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val definition = frame.module.definitions[NameArity(name, args.size)]
            ?.takeIf { !it.kind.macro }
            ?: decline("calls $name/${args.size}, which isn't a function of ${frame.module.module}")

        return invoke(frame.module, definition, name, args.map { eval(it, frame) })
    }

    private fun map(args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val update = args.singleOrNull()?.takeIf { isCall(it, "|", 2) } as ElixirAst.Call?

        if (update != null) {
            val (left, pairs) = update.arguments!!

            return update(eval(left, frame), pairs(listOf(pairs), frame))
        }

        return mapOf(pairs(args, frame), run.level)
    }

    /** Each `key => value` of [args], evaluated: a keyword list's pairs, or the tuples a map's arguments are. */
    private fun pairs(args: List<ElixirAst>, frame: Frame): List<Pair<OtpErlangObject, OtpErlangObject>> =
        args.flatMap { arg -> (arg as? ElixirAst.ListNode)?.elements ?: listOf(arg) }.map { pair ->
            val (key, value) = (pair as? ElixirAst.Tuple)?.elements?.takeIf { it.size == 2 } ?: decline("a map pair")

            eval(key, frame) to eval(value, frame)
        }

    /** `%{map | pairs}`: every key must already be in [map]. */
    private fun update(map: OtpErlangObject, pairs: List<Pair<OtpErlangObject, OtpErlangObject>>): OtpErlangObject {
        seen(map)
        if (map !is OtpErlangMap) error(tuple(atom("badmap"), map))

        for ((key, _) in pairs) if (valueAt(map, key, run.level) == null) error(tuple(atom("badkey"), key))

        return mapOf(entries(map) + pairs, run.level)
    }

    /**
     * `%Name{pairs}`, from debug info its defaults escaped among its pairs, or `%Name{value | pairs}`. From source, the
     * defaults are what `Name.__struct__/1` gives, as `elixir_map:expand_struct/5` reads them.
     */
    private fun struct(args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val (nameNode, mapNode) = args
        val name = eval(nameNode, frame)
        val mapArgs = (mapNode as? ElixirAst.Call)?.takeIf { isCall(it, "%{}", it.arguments?.size ?: -1) }?.arguments
            ?: decline("a struct whose fields aren't a map")
        val update = mapArgs.singleOrNull()?.takeIf { isCall(it, "|", 2) } as ElixirAst.Call?

        if (update == null) {
            val given = pairs(mapArgs, frame)

            if (!frame.source) return mapOf(listOf(STRUCT to name) + given, run.level)

            val struct = remote(name, "__struct__", listOf(list(given.map { (key, value) -> tuple(key, value) })))
                as? OtpErlangMap ?: decline("a struct whose __struct__/1 isn't a map")
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
        val value = eval(left, frame)

        if (!STRUCT_UPDATE_IS_MAP_UPDATE.isSufficient(run.level)) {
            seen(value)
            if ((value as? OtpErlangMap)?.get(STRUCT)?.let { equal(it, name, true, run.level) } != true) {
                error(tuple(atom("badstruct"), name, value))
            }
        }

        return update(value, pairs(listOf(pairs), frame))
    }

    private fun cond(arrows: List<ElixirAst>, frame: Frame): OtpErlangObject {
        for (arrow in arrows) {
            val (head, body) = arrowParts(arrow)
            val scope = frame.copy()

            if (truthy(eval(head.single(), scope))) return eval(body, scope)
        }

        error(atom("cond_clause"))
    }

    private fun fn(arrows: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val closed = frame.copy()
        val arity = clauseHead(arrowParts(arrows.first()).first).first.size

        return Closure(arity) { values -> clauses(arrows, values, closed, fresh = true) ?: noClause(closed, values) }
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
            val module = eval(receiver, frame) as? OtpErlangAtom ?: decline("a capture's module")

            return OtpErlangExternalFun(module.atomValue(), (name as? ElixirAst.Literal.Atom)?.name ?: decline("a capture's name"), arity)
        }

        val name = (dispatched as? Dispatched.Super)?.name
            ?: (callee as? ElixirAst.Literal.Atom)?.name
            ?: decline("a capture's name")
        val module = frame.module
        val definition = module.definitions[NameArity(name, arity)]
            ?.takeIf { !it.kind.macro }
            ?: decline("a capture of $name/$arity, which isn't a function of ${module.module}")

        return Closure(arity) { values -> invoke(module, definition, name, values) }
    }

    /** [function] applied to [args]. */
    fun apply(function: OtpErlangObject, args: List<OtpErlangObject>): OtpErlangObject {
        // As [invoke] does, so a loop calling an `fn` checks once per call, though the `fn` calls nothing.
        ProgressManager.checkCanceled()

        return when (function) {
            is Closure ->
                if (function.arity == args.size) {
                    function.invoke(args)
                } else {
                    error(tuple(atom("badarity"), tuple(function, list(args))))
                }
            is OtpErlangExternalFun ->
                if (function.arity == args.size) {
                    remote(atom(function.module), function.function, args)
                } else {
                    error(tuple(atom("badarity"), tuple(function, list(args))))
                }
            Unknown -> decline("an unknown value")
            else -> error(tuple(atom("badfun"), function))
        }
    }

    /** `receiver:function(args)`: a native, else a public function of a module whose clauses can be read. */
    fun remote(receiver: OtpErlangObject, function: String, args: List<OtpErlangObject>): OtpErlangObject {
        seen(receiver)

        val module = (receiver as? OtpErlangAtom)?.atomValue()
            ?: error(atom("badarg"), frame("erlang", "apply", list(listOf(receiver, atom(function), list(args)))))

        natives.call(module, function, args)?.let { return it }

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

    private fun evalTry(options: ElixirAst, frame: Frame): OtpErlangObject {
        try {
            val value = try {
                eval(doOption(options), frame.copy())
            } catch (thrown: Thrown) {
                return rescued(thrown, options, frame) ?: throw thrown
            }

            return optionOrNull(options, "else")?.let { elses ->
                clauses(arrows(elses), listOf(value), frame) ?: error(tuple(atom("try_clause"), value))
            } ?: value
        } finally {
            optionOrNull(options, "after")?.let { eval(it, frame.copy()) }
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
        val (left, aliases) = rescueHead(head, scope)
        val erlangAliases = if (aliases == null) {
            listOf(ERLANG_ERROR)
        } else {
            seen(reason)

            val elixir = aliases.any { alias ->
                reason is OtpErlangMap && reason.get(STRUCT)?.let { equal(it, atom(alias), false, run.level) } == true &&
                    isException(reason)
            }
            val erlang = aliases.filter { erlangGuard(it) != null }

            if (!elixir && erlang.none { erlangGuard(it)!!(reason) }) return false

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

    /** A rescue clause's variable and the aliases it rescues, `null` for a bare variable's, as `expand_rescue` reads it. */
    private fun rescueHead(head: ElixirAst, frame: Frame): Pair<ElixirAst, List<String>?> {
        if (isVariable(head)) return head to null

        if (isCall(head, "in", 2)) {
            val (left, right) = (head as ElixirAst.Call).arguments!!
            val aliases = (right as? ElixirAst.ListNode)?.elements ?: listOf(right)

            if (!isVariable(left)) decline("a rescue whose left isn't a variable")

            return left to aliases.map { aliasOf(it, frame) }
        }

        if (frame.source && head is ElixirAst.Alias) {
            return ElixirAst.Call(head.meta, ElixirAst.Literal.Atom(head.meta, "_"), null) to listOf(aliasOf(head, frame))
        }

        decline("a rescue clause's head")
    }

    private fun aliasOf(node: ElixirAst, frame: Frame): String =
        (eval(node, frame) as? OtpErlangAtom)?.atomValue() ?: decline("a rescue of a value that isn't an alias")

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

        if (reduce != null) {
            var acc = eval(reduce, start)

            comprehend(qualifiers, 0, start, Comprehended(false, outer)) { scope ->
                acc = clauses(arrows(body), listOf(acc), scope) ?: error(tuple(atom("case_clause"), acc))
            }

            return acc
        }

        val intoNode = optionOrNull(options, "into")
        // `Collectable.into/1` runs before the generators.
        val into = intoNode?.let { eval(it, start) }
        val uniqNode = optionOrNull(options, "uniq")
        // `build_inline/7`: an Erlang comprehension where every generator is a bitstring's, with no `uniq: true`, into
        // `[]` or `{bin, _, []}`, which both `""` and `<<>>` translate to, each as the options expanded.
        val intoForm = intoFormOf(intoNode?.let(frame::expanded))
        val inline = (uniqNode?.let(frame::expanded) as? ElixirAst.Literal.Atom)?.name != "true" &&
            (intoForm == IntoForm.LIST || intoForm == IntoForm.BINARY) &&
            qualifiers.all { isBitstringGenerator(it) || !isListGenerator(it) }
        val collection = collection(intoForm, into)
        val uniq = uniqNode?.let { truthy(eval(it, start)) } == true
        val values = mutableListOf<OtpErlangObject>()

        comprehend(qualifiers, 0, start, Comprehended(inline, outer)) { scope ->
            val value = eval(body, scope)

            if (!uniq || values.none { ProgressManager.checkCanceled(); equal(it, value, true, run.level) }) {
                collection.take(value)
                values += value
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
                else -> decline("for into: %s", into!!)
            }
        }
    }

    /** How `elixir_erl_for` translates a `for`: as an Erlang comprehension or not, and the scope the `for` starts in. */
    private class Comprehended(val inline: Boolean, val outer: Frame)

    /** [emit] once per element the qualifiers from [index] reach, with a scope binding each generator's pattern. */
    private fun comprehend(qualifiers: List<ElixirAst>, index: Int, frame: Frame, comprehended: Comprehended, emit: (Frame) -> Unit) {
        ProgressManager.checkCanceled()

        if (index == qualifiers.size) return emit(frame)

        val qualifier = qualifiers[index]

        if (isListGenerator(qualifier)) {
            val (head, enumerable) = (qualifier as ElixirAst.Call).arguments!!
            val (patterns, guards) = clauseHead(ElixirAst.ListNode(head.meta, listOf(head)))

            for (element in natives.enumerate(eval(enumerable, frame))) {
                val scope = frame.copy()

                if (matches(patterns, listOf(element), scope, fresh = true) && guards(guards, scope)) {
                    comprehend(qualifiers, index + 1, scope, comprehended, emit)
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

            val value = eval(bitstring, frame)

            bits.generate(segments, value, frame, comprehended.inline, comprehended.outer) { scope ->
                comprehend(qualifiers, index + 1, scope, comprehended, emit)
            }
        } else if (truthy(eval(qualifier, frame))) {
            comprehend(qualifiers, index + 1, frame, comprehended, emit)
        }
    }

    private fun evalWith(args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        // From `Dbgi`, `expand_with/3` has merged the options into the last list.
        val (clauses, elements) = if (frame.source) withOptions(args, run.level) else splitOptions(args, false)
        val options = ElixirAst.ListNode(args.last().meta, elements)
        val scope = frame.copy()

        for (clause in clauses) {
            if (!isCall(clause, "<-", 2)) {
                eval(clause, scope)
                continue
            }

            val (head, right) = (clause as ElixirAst.Call).arguments!!
            val (patterns, guards) = clauseHead(ElixirAst.ListNode(head.meta, listOf(head)))
            val value = eval(right, scope)

            if (!(matches(patterns, listOf(value), scope) && guards(guards, scope))) {
                // Before 1.13 `expand_with/2` rewrites `var <- e` to `var = e`.
                if (!GENERATOR_RIGHT_SIDE_SCOPED.isSufficient(run.level) && isVariable(head)) {
                    error(tuple(atom("badmatch"), value))
                }

                val elses = arrows(optionOrNull(options, "else") ?: return value)
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
     * [matches] takes it.
     */
    private fun clauses(
        arrows: List<ElixirAst>,
        values: List<OtpErlangObject>,
        frame: Frame,
        fresh: Boolean = false,
    ): OtpErlangObject? {
        for (arrow in arrows) {
            val (head, body) = arrowParts(arrow)
            val (patterns, guards) = clauseHead(head)

            if (patterns.size != values.size) continue

            val scope = frame.copy()

            if (matches(patterns, values, scope, fresh) && guards(guards, scope)) return eval(body, scope)
        }

        return null
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
        val ERLANG = atom("erlang")
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
