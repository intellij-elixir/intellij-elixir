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
import org.elixir_lang.expander.ModuleClauses
import org.elixir_lang.expander.ModuleExports
import org.elixir_lang.expander.Run
import org.elixir_lang.expander.Summary
import org.elixir_lang.expander.Variable
import org.elixir_lang.expander.aliasesModule
import org.elixir_lang.expander.forOptions
import org.elixir_lang.expander.isBitstringGenerator
import org.elixir_lang.expander.isCall
import org.elixir_lang.expander.isListGenerator
import org.elixir_lang.expander.isUnderscore
import org.elixir_lang.expander.isVariable
import org.elixir_lang.expander.isVariableNamed
import org.elixir_lang.expander.keyOf
import org.elixir_lang.expander.lineOf
import org.elixir_lang.expander.metaValue
import org.elixir_lang.expander.orGuards
import org.elixir_lang.expander.splitOptions
import org.elixir_lang.expander.variable
import org.elixir_lang.expander.whenArguments
import org.elixir_lang.expander.withOptions
import org.elixir_lang.language_level.ElixirLanguageFeature.FIELD_OF_NON_MAP_RAISES_BADKEY
import org.elixir_lang.language_level.ElixirLanguageFeature.FN_CLAUSE_ERROR_IS_FUNCTION_CLAUSE
import org.elixir_lang.language_level.ElixirLanguageFeature.GENERATOR_RIGHT_SIDE_SCOPED
import org.elixir_lang.language_level.ElixirLanguageFeature.FIELD_OF_NON_MAP_RAISES_BADMAP
import org.elixir_lang.language_level.ElixirLanguageFeature.RESCUED_ERROR_MESSAGE_FROM_ERROR_INFO
import org.elixir_lang.language_level.ElixirLanguageFeature.RESCUE_EXCEPTION_KEY_PRESENT
import org.elixir_lang.language_level.ElixirLanguageFeature.RESCUE_LIST_STACKTRACE_FOR_ANY
import org.elixir_lang.language_level.ElixirLanguageFeature.STRUCT_UPDATE_IS_MAP_UPDATE
import org.elixir_lang.language_level.ElixirLanguageFeature.WITH_ELSE_CLAUSE_ERROR
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.lowering.ParserOptions
import java.util.IdentityHashMap

/** A call of a macro with no summary, answered by evaluating the macro's clauses. */
internal object Interpreter {
    /** The summary of [node], which dispatches as [dispatch], from its macro's clauses, or `null` where it declines. */
    fun summary(dispatch: Dispatch, node: ElixirAst.Call, env: Env, run: Run): Summary? {
        val output = try {
            Evaluation(dispatch, node, env, run).output()
        } catch (_: Declined) {
            return null
        } catch (_: StackOverflowError) {
            // The BEAM's stack grows as a body recurses, where the thread's is a few hundred calls deep.
            return null
        }

        return object : Summary.Rewrite() {
            override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run) = output
        }
    }
}

/**
 * A variable as a [Frame] binds it: from `Dbgi`, the version its expansion gave it, which `elixir_erl_var` keys it by
 * alone, or none where it has none; from source, for now, its name.
 */
internal sealed interface Binding {
    data class Version(val version: Long?) : Binding

    data class Name(val variable: Variable) : Binding
}

/**
 * The bindings of one clause of [module]'s, the env a source clause's body is read in, and the stacktrace a `rescue` or
 * `catch` body reads.
 */
internal class Frame(
    val module: ModuleClauses,
    val env: Env?,
    val vars: HashMap<Binding, OtpErlangObject> = HashMap(),
    val stacktrace: OtpErlangObject? = null,
    val guard: Boolean = false,
) {
    val source get() = module.source == ModuleClauses.Source.SOURCE

    /** Where it isn't `null`, each variable a match binds in this frame, as a `for` filter's are read. */
    var binds: MutableSet<Binding>? = null

    /** [node]'s binding: from `Dbgi`, the `version:` its expansion gave it; from source, its name. */
    fun binding(node: ElixirAst): Binding =
        if (source) {
            Binding.Name(variable(node))
        } else {
            Binding.Version((metaValue(node.meta, "version") as? Meta.Value.Integer)?.value)
        }

    fun copy(stacktrace: OtpErlangObject? = this.stacktrace) = Frame(module, env, HashMap(vars), stacktrace, guard)

    /** This frame's bindings, read as a guard reads them. */
    fun guarded() = Frame(module, env, vars, stacktrace, true)
}

internal class Evaluation(
    private val dispatch: Dispatch,
    private val call: ElixirAst.Call,
    private val env: Env,
    val run: Run,
) {
    /** Each term made from an argument's node, so the output keeps the nodes the macro passed through. */
    private val nodes = IdentityHashMap<OtpErlangObject, ElixirAst>()

    /** The `{M, F, Arity, []}` of each call being evaluated, innermost first. */
    private val stack = ArrayDeque<OtpErlangTuple>()

    /** The atoms the evaluation has seen, which `binary_to_existing_atom` finds. */
    val atoms = HashSet<String>()

    private val natives = Natives(this)

    private val bits = Bits(this)

    fun output(): Summary.Output {
        val module = run.clausesOf(dispatch.receiver) ?: decline("no clauses of ${dispatch.receiver}")
        val definition = module.definitions[NameArity(dispatch.name, dispatch.arity)]
            ?.takeIf { it.kind.macro }
            ?: decline("${dispatch.receiver} has no macro ${dispatch.name}/${dispatch.arity}")
        val args = call.arguments.orEmpty().map(::termOf)
        val function = ModuleExports.macroExport(NameArity(dispatch.name, args.size))
        val result = try {
            invoke(module, definition, function.name, args, function.arity)
        } catch (thrown: Thrown) {
            if (thrown.kind != "error") decline("the body ${thrown.kind}s")

            return Summary.Output.Raised(exceptionModule(thrown))
        }

        return Summary.Output.Built(
            ElixirAst.fromOtp(result, call.meta.origin, nodes::get) ?: decline("the result isn't quoted")
        )
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

                val frame = Frame(module, clause.env)

                if (matches(clause.args, args, frame) && guards(clause.guards, frame)) return eval(clause.body, frame)
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

    internal fun eval(node: ElixirAst, frame: Frame): OtpErlangObject =
        when (node) {
            is ElixirAst.Literal.Atom -> atom(node.name).also { atoms += node.name }
            is ElixirAst.Literal.Integer -> integer(node.value)
            // The lowering reads a float past a double's range, which Elixir's tokenizer refuses, as infinite.
            is ElixirAst.Literal.Float ->
                OtpErlangDouble(node.value.takeIf(Double::isFinite) ?: decline("a float past a double's range"))
            is ElixirAst.Literal.Binary -> OtpErlangBinary(node.bytes)
            is ElixirAst.ListNode -> evalList(node.elements, frame)
            is ElixirAst.Tuple -> OtpErlangTuple(node.elements.map { eval(it, frame) }.toTypedArray())
            is ElixirAst.Block -> block(node.expressions, frame)
            is ElixirAst.Alias -> {
                val env = frame.env?.takeIf { frame.source } ?: decline("an alias in debug info")

                atom(aliasesModule(node, env, run.level) ?: decline("an alias not known here"))
            }
            is ElixirAst.Placeholder -> decline("a placeholder")
            is ElixirAst.Call -> evalCall(node, frame)
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
        frame.vars[frame.binding(node)]?.let { return it }

        return when (name) {
            "__CALLER__" -> caller()
            "__STACKTRACE__" -> frame.stacktrace ?: decline("__STACKTRACE__ outside a rescue or catch")
            "__MODULE__" if frame.source -> atom(frame.module.module)
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

        if (args.isEmpty() && isFieldReceiver(left, frame)) return field(eval(left, frame), name, node.meta, frame)

        val receiver = eval(left, frame)

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
            "<<>>" -> if (frame.source) decline("a source bitstring") else bits.build(args, frame)
            "__block__" -> block(args, frame)
            "case" if args.size == 2 -> {
                val value = eval(args[0], frame)

                clauses(arrows(doOption(args[1])), listOf(value), frame) ?: error(tuple(atom("case_clause"), value))
            }
            "cond" if args.size == 1 -> cond(arrows(doOption(args[0])), frame)
            "fn" -> fn(args, frame)
            "&" -> capture(args.single(), frame)
            "try" -> evalTry(args.single(), frame)
            "for" -> evalFor(args, frame)
            "with" -> evalWith(args, frame)
            "quote" ->
                if (frame.source) {
                    eval(frame.module.quotes.of(node) ?: decline("a quote whose escaped block wasn't built"), frame)
                } else {
                    decline("a quote in debug info")
                }
            "receive" if args.size == 1 -> decline("receive")
            "super" -> decline("super")
            // Outside a pattern, only in the size `expand_specs/7` infers for a pin: `byte_size(^x)`.
            "^" if args.size == 1 -> eval(args.single(), frame)
            else -> local(name, args, frame)
        }

    /** A call of a function of the clause's own module. */
    private fun local(name: String, args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val definition = frame.module.definitions[NameArity(name, args.size)]
            ?.takeIf { !frame.source || !it.kind.macro }
            ?: decline("calls $name/${args.size}, which needs expansion")

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

    /** `%Name{pairs}` from debug info, its defaults escaped among its pairs, or `%Name{value | pairs}`. */
    private fun struct(args: List<ElixirAst>, frame: Frame): OtpErlangObject {
        if (frame.source) decline("a source struct")

        val (nameNode, mapNode) = args
        val name = eval(nameNode, frame)
        val mapArgs = (mapNode as? ElixirAst.Call)?.takeIf { isCall(it, "%{}", it.arguments?.size ?: -1) }?.arguments
            ?: decline("a struct whose fields aren't a map")
        val update = mapArgs.singleOrNull()?.takeIf { isCall(it, "|", 2) } as ElixirAst.Call?

        if (update == null) return mapOf(listOf(STRUCT to name) + pairs(mapArgs, frame), run.level)

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

        return Closure(arity) { values -> clauses(arrows, values, closed) ?: noClause(closed, values) }
    }

    /** What an `fn` of [closed] raises where none of its clauses matches [values]. */
    private fun noClause(closed: Frame, values: List<OtpErlangObject>): Nothing {
        if (!FN_CLAUSE_ERROR_IS_FUNCTION_CLAUSE.isSufficient(run.level)) decline("an fn clause error")

        // Its name is the compiler's, from its fun counter and what it inlines.
        error(atom("function_clause"), OtpErlangTuple(arrayOf(atom(closed.module.module), Unknown, list(values), EMPTY)))
    }

    private fun capture(target: ElixirAst, frame: Frame): OtpErlangObject {
        val (function, arityNode) = (target as? ElixirAst.Call)?.takeIf { isCall(it, "/", 2) }?.arguments
            ?: decline("a capture other than name/arity")
        val arity = (arityNode as? ElixirAst.Literal.Integer)?.value?.toInt() ?: decline("a capture's arity")
        val functionCall = function as? ElixirAst.Call ?: decline("a capture's function")
        val callee = functionCall.callee

        if (isCall(callee, ".", 2)) {
            val (receiver, name) = (callee as ElixirAst.Call).arguments!!
            val module = eval(receiver, frame) as? OtpErlangAtom ?: decline("a capture's module")

            return OtpErlangExternalFun(module.atomValue(), (name as? ElixirAst.Literal.Atom)?.name ?: decline("a capture's name"), arity)
        }

        val name = (callee as? ElixirAst.Literal.Atom)?.name ?: decline("a capture's name")
        val module = frame.module
        val definition = module.definitions[NameArity(name, arity)]
            ?.takeIf { !frame.source || !it.kind.macro }
            ?: decline("a capture of $name/$arity, which needs expansion")

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
        if (erlangAliases.isEmpty()) return matches(listOf(left), listOf(reason), scope)

        return matches(listOf(left), listOf(normalize(reason, rescueStacktrace(reason, thrown.stacktrace, erlangAliases))), scope)
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
        val (qualifiers, elements) = forOptions(args)
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
        // `[]` or `{bin, _, []}`, which both `""` and `<<>>` translate to.
        val intoForm = intoFormOf(intoNode)
        val inline = (uniqNode as? ElixirAst.Literal.Atom)?.name != "true" &&
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

    /**
     * [emit] once per element the qualifiers from [index] reach, with a scope binding each generator's pattern.
     * [rebound] holds the variables the generators before [index] bound.
     */
    private fun comprehend(
        qualifiers: List<ElixirAst>,
        index: Int,
        frame: Frame,
        comprehended: Comprehended,
        rebound: Set<Binding> = emptySet(),
        emit: (Frame) -> Unit,
    ) {
        ProgressManager.checkCanceled()

        if (index == qualifiers.size) return emit(frame)

        val qualifier = qualifiers[index]

        if (isListGenerator(qualifier)) {
            val (head, enumerable) = (qualifier as ElixirAst.Call).arguments!!
            val (patterns, guards) = clauseHead(ElixirAst.ListNode(head.meta, listOf(head)))

            for (element in natives.enumerate(eval(enumerable, rightSide(frame)))) {
                val scope = frame.copy()
                val bound = matching(patterns, listOf(element), scope)

                if (bound != null && guards(guards, scope)) {
                    comprehend(qualifiers, index + 1, scope, comprehended, rebound + bound, emit)
                }
            }
        } else if (isBitstringGenerator(qualifier)) {
            if (frame.source) decline("a source bitstring generator")

            // Expanded, it is `<<(<<segments>>) <- bitstring>>`.
            val (left, bitstring) = ((qualifier as ElixirAst.Call).arguments!!.single() as ElixirAst.Call).arguments!!
            val segments = (left as? ElixirAst.Call)?.takeIf { isCall(it, "<<>>", it.arguments?.size ?: -1) }?.arguments
                ?: decline("a bitstring generator that isn't expanded")

            val value = eval(bitstring, rightSide(frame))

            bits.generate(segments, value, frame, comprehended.inline, comprehended.outer, rebound) { scope, bound ->
                comprehend(qualifiers, index + 1, scope, comprehended, rebound + bound, emit)
            }
        } else {
            // A filter's match gives its variables new names, as a generator's pattern does.
            val bound = HashSet<Binding>().also { frame.binds = it }
            val kept = try {
                truthy(eval(qualifier, frame))
            } finally {
                frame.binds = null
            }

            if (kept) comprehend(qualifiers, index + 1, frame, comprehended, rebound + bound, emit)
        }
    }

    /** The scope a generator's right side is evaluated in: from [GENERATOR_RIGHT_SIDE_SCOPED], its bindings are dropped. */
    private fun rightSide(frame: Frame): Frame = if (GENERATOR_RIGHT_SIDE_SCOPED.isSufficient(run.level)) frame.copy() else frame

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
                val elses = optionOrNull(options, "else") ?: return value
                val error = if (WITH_ELSE_CLAUSE_ERROR.isSufficient(run.level)) "else_clause" else "with_clause"

                return clauses(arrows(elses), listOf(value), frame) ?: error(tuple(atom(error), value))
            }
        }

        return eval(doOption(options), scope)
    }

    /** The body of the first of [arrows] whose head matches [values], evaluated, or `null` where none does. */
    private fun clauses(arrows: List<ElixirAst>, values: List<OtpErlangObject>, frame: Frame): OtpErlangObject? {
        for (arrow in arrows) {
            val (head, body) = arrowParts(arrow)
            val (patterns, guards) = clauseHead(head)

            if (patterns.size != values.size) continue

            val scope = frame.copy()

            if (matches(patterns, values, scope) && guards(guards, scope)) return eval(body, scope)
        }

        return null
    }

    /** Whether each of [patterns] matches its value, binding their variables in [frame] where all do. */
    private fun matches(patterns: List<ElixirAst>, values: List<OtpErlangObject>, frame: Frame): Boolean =
        matching(patterns, values, frame) != null

    /** The variables [patterns] bind matching [values], now bound in [frame], or `null` where they don't match. */
    private fun matching(patterns: List<ElixirAst>, values: List<OtpErlangObject>, frame: Frame): Set<Binding>? {
        val pending = HashMap<Binding, OtpErlangObject>()

        if (!patterns.indices.all { match(patterns[it], values[it], frame, pending) }) return null

        frame.vars.putAll(pending)
        frame.binds?.addAll(pending.keys)

        return pending.keys
    }

    /** Whether [pattern] matches [value], binding its variables in [pending]. */
    internal fun match(pattern: ElixirAst, value: OtpErlangObject, frame: Frame, pending: HashMap<Binding, OtpErlangObject>): Boolean {
        if (isVariable(pattern)) {
            if (isUnderscore(pattern)) return true

            val binding = frame.binding(pattern)

            if (binding == Binding.Version(null)) decline("binds a variable with no version")

            // A version already bound is the same Erlang variable, so it is matched, not bound again.
            val bound = pending[binding]
                ?: (binding as? Binding.Version)?.let(frame.vars::get)
                ?: return true.also { pending[binding] = value }

            return equal(bound, value, true, run.level)
        }
        seen(value)

        return when (pattern) {
            is ElixirAst.Literal, is ElixirAst.Alias -> equal(eval(pattern, frame), value, true, run.level)
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
        pending: HashMap<Binding, OtpErlangObject>,
    ): Boolean =
        value is OtpErlangTuple && value.arity() == patterns.size &&
            patterns.indices.all { match(patterns[it], value.elementAt(it), frame, pending) }

    private fun matchList(
        patterns: List<ElixirAst>,
        value: OtpErlangObject,
        frame: Frame,
        pending: HashMap<Binding, OtpErlangObject>,
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
        pending: HashMap<Binding, OtpErlangObject>,
    ): Boolean {
        val args = pattern.arguments ?: decline("a pattern call")

        return when ((pattern.callee as? ElixirAst.Literal.Atom)?.name) {
            "^" -> {
                val pinned = args.single() as? ElixirAst.Call ?: decline("a pin of a non-variable")

                equal(frame.vars[frame.binding(pinned)] ?: decline("a pin of an unbound variable"), value, true, run.level)
            }
            "=" -> args.all { match(it, value, frame, pending) }
            "{}" -> matchTuple(args, value, frame, pending)
            "%{}" -> matchMap(args, value, frame, pending)
            "%" -> {
                if (frame.source) decline("a source struct pattern")

                val (name, fields) = args
                val struct = (value as? OtpErlangMap)?.get(STRUCT)?.let(::seen) ?: return false

                if (isVariable(name) && struct !is OtpErlangAtom) return false

                match(name, struct, frame, pending) &&
                    matchMap((fields as? ElixirAst.Call)?.arguments ?: decline("a struct pattern's fields"), value, frame, pending)
            }
            "<<>>" -> if (frame.source) decline("a source bitstring pattern") else bits.match(args, value, frame, pending)
            else -> decline("a pattern ${pattern.callee}")
        }
    }

    private fun matchMap(
        args: List<ElixirAst>,
        value: OtpErlangObject,
        frame: Frame,
        pending: HashMap<Binding, OtpErlangObject>,
    ): Boolean {
        val map = value as? OtpErlangMap ?: return false

        return args.flatMap { arg -> (arg as? ElixirAst.ListNode)?.elements ?: listOf(arg) }.all { pair ->
            val (key, valuePattern) = (pair as? ElixirAst.Tuple)?.elements?.takeIf { it.size == 2 } ?: decline("a map pattern pair")
            val keyValue = if (isCall(key, "^", 1)) {
                frame.vars[frame.binding((key as ElixirAst.Call).arguments!!.single())]
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
            val value = try {
                eval(guard, frame.guarded())
            } catch (_: Thrown) {
                FALSE
            }

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
        const val ERLANG_ERROR = "Elixir.ErlangError"
        val REQUIRES_STACKTRACE = listOf("Elixir.FunctionClauseError", "Elixir.UndefinedFunctionError", "Elixir.KeyError")
        val REQUIRES_STACKTRACE_FROM_ERROR_INFO = listOf("Elixir.ArgumentError", "Elixir.SystemLimitError")
    }
}

/** `is_list/1`, true of an improper list too. */
private fun isList(term: OtpErlangObject) = term is OtpErlangList || term is OtpErlangString
