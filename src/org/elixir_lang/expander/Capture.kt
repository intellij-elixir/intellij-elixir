package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.CAPTURE_ARGUMENT_BELOW_ONE_IS_INVALID_ARITY
import org.elixir_lang.language_level.ElixirLanguageFeature.CAPTURE_ARGUMENT_COUNTER
import org.elixir_lang.language_level.ElixirLanguageFeature.CAPTURE_ARGUMENT_IN_ELIXIR_FN_CONTEXT
import org.elixir_lang.language_level.ElixirLanguageFeature.CAPTURE_ARGUMENT_POSITION_META
import org.elixir_lang.language_level.ElixirLanguageFeature.CAPTURE_REPORTED_AT_CALL
import org.elixir_lang.language_level.ElixirLanguageFeature.REMOTE_CAPTURE_REPORTED_AT_CALL
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term
import java.math.BigInteger

/** The heads of `elixir_fn:capture/4` and `escape/3`, which aren't clauses of `expand`. */
internal val CAPTURE_HEADS = listOf(
    captureHead("{'/',_,[{{'.',_,[_,V1]},_,[]},V2]} when is_atom(V1), is_integer(V2)"),
    captureHead("{'/',_,[{V1,_,V2},V3]} when is_atom(V1), is_integer(V3), is_atom(V2)"),
    captureHead("{{'.',_,[_,V1]},_,V2} when is_atom(V1), is_list(V2)"),
    captureHead("{{'.',_,[_]},_,V1} when is_list(V1)"),
    captureHead("{'__block__',_,[_]}"),
    captureHead("{'__block__',_,_}"),
    captureHead("{V1,_,V2} when is_atom(V1), is_list(V2)"),
    captureHead("{_,_}"),
    captureHead("V1 when is_list(V1)"),
    captureHead("V1 when is_integer(V1)"),
    captureHead("_"),
    escapeHead("{'&',_,[V1]} when is_integer(V1), V1 > 0"),
    escapeHead("{'&',_,[V1]} when is_integer(V1)"),
    escapeHead("{'&',_,_}"),
    escapeHead("{_,_,_}"),
    escapeHead("{_,_}"),
    escapeHead("V1 when is_list(V1)"),
    escapeHead("_"),
)

/**
 * `elixir_expand:expand_fn_capture/4` through `elixir_fn:capture/4`, for [node], `&` of one argument: a capture of a
 * named function, or else the `fn` Elixir rewrites it to.
 */
internal fun expandCapture(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion =
    capture(node, node.arguments!!.single(), state, env, run)

/** Whether a capture's arguments are `&1` to `&N` in order: `check_sequential_and_not_empty/1`, or `&fun/arity`'s. */
private enum class Arguments { ARITY, SEQUENTIAL, NON_SEQUENTIAL }

private fun capture(amp: ElixirAst.Call, arg: ElixirAst, state: ExState, env: Env, run: Run): Expansion {
    val call = arg as? ElixirAst.Call
    val dot = (call?.callee as? ElixirAst.Call)
        ?.takeIf { (it.callee as? ElixirAst.Literal.Atom)?.name == "." }
        ?.arguments
    val name = (call?.callee as? ElixirAst.Literal.Atom)?.name
    val atCall = captureAt(amp, arg, run)

    return when {
        isCall(arg, "/", 2) && isRemoteFunction((arg as ElixirAst.Call).arguments!![0]) && isInteger(arg.arguments!![1]) -> {
            val (function, arity) = arg.arguments
            val remote = function as ElixirAst.Call

            argumentsFromArity(amp, arity)?.let { args ->
                val call = ElixirAst.Call(arityCallMeta(amp, remote, run), remote.callee, args)

                captureRequire(amp, call, Arguments.ARITY, state, env, run)
            } ?: Expansion.Error("invalid_arity_for_capture", amp)
        }
        isCall(arg, "/", 2) && isVariable((arg as ElixirAst.Call).arguments!![0]) && isInteger(arg.arguments!![1]) -> {
            val (function, arity) = arg.arguments
            val variable = function as ElixirAst.Call
            val functionName = (variable.callee as ElixirAst.Literal.Atom).name

            argumentsFromArity(amp, arity)?.let { args ->
                val call = ElixirAst.Call(arityCallMeta(amp, variable, run), variable.callee, args)

                val at = captureAt(amp, call, run)

                captureImport(amp, at, call, functionName, args, state, env, run, Arguments.ARITY)
            } ?: Expansion.Error("invalid_arity_for_capture", amp)
        }
        dot?.size == 2 && dot[1] is ElixirAst.Literal.Atom && call.arguments != null ->
            captureRequire(amp, call, arguments(call.arguments), state, env, run)
        dot?.size == 1 && call.arguments != null -> captureExpr(amp, call, Arguments.NON_SEQUENTIAL, state, env, run)
        arg is ElixirAst.Block && arg.expressions.size == 1 -> capture(amp, arg.expressions.single(), state, env, run)
        arg is ElixirAst.Block -> Expansion.Error("block_expr_in_capture", amp)
        name != null && call.arguments != null -> captureImport(amp, atCall, call, name, call.arguments, state, env, run)
        arg is ElixirAst.Alias -> captureImport(amp, atCall, arg, "__aliases__", arg.segments, state, env, run)
        arg is ElixirAst.Tuple && arg.elements.size != 2 -> captureImport(amp, atCall, arg, "{}", arg.elements, state, env, run)
        // `{left, right}` becomes `{'{}', Meta, [left, right]}` with the `&`'s metadata.
        arg is ElixirAst.Tuple -> captureImport(amp, amp, arg, "{}", arg.elements, state, env, run)
        arg is ElixirAst.ListNode -> captureExpr(amp, arg, arguments(arg.elements), state, env, run)
        arg is ElixirAst.Literal.Integer -> Expansion.Error("capture_arg_outside_of_capture", amp)
        arg is ElixirAst.Placeholder -> Expansion.Unported(arg)
        else -> Expansion.Error("invalid_args_for_capture", amp)
    }
}

/**
 * `capture_import/4`: `import_function/4` looks up a call of sequential arguments.
 *
 * @param at where the capture's errors are reported
 */
private fun captureImport(
    amp: ElixirAst,
    at: ElixirAst,
    expr: ElixirAst,
    name: String,
    args: List<ElixirAst>,
    state: ExState,
    env: Env,
    run: Run,
    arguments: Arguments = arguments(args),
): Expansion =
    if (arguments == Arguments.NON_SEQUENTIAL) {
        captureExpr(at, expr, arguments, state, env, run)
    } else {
        importFunction(amp, expr, name, args.size, state, env, run)
            ?: captureExpr(at, expr, arguments, state, env, run)
    }

/**
 * `elixir_dispatch:import_function/4` for a capture of [call], and then `expand_fn_capture/4`'s answer, or `null` for
 * Elixir's `false`: a macro or a special form, whose capture is an `fn` that calls it.
 */
private fun importFunction(
    amp: ElixirAst,
    call: ElixirAst,
    name: String,
    arity: Int,
    state: ExState,
    env: Env,
    run: Run,
): Expansion? {
    if (hasQuotedImport(call.meta)) return Expansion.Unported(amp)

    return when (val match = findImportByNameArity(name, arity, emptyList(), env)) {
        is ImportMatch.Function -> {
            importedFunction(call, match.receiver, name, arity, run)

            Expansion.Expanded(state, env, NODE)
        }
        is ImportMatch.Macro -> null
        is ImportMatch.Ambiguous -> Expansion.Error("ambiguous_call", call)
        ImportMatch.None ->
            when {
                specialForm(name, arity, run.level) -> null
                // `elixir_def:local_for/5` reads the module's definitions, which aren't modelled.
                env.function != null -> Expansion.Unported(amp)
                else -> {
                    val module = env.module ?: "nil"

                    run.observer.dispatched(call, Dispatch(Dispatch.Kind.LOCAL_FUNCTION, module, name, arity))

                    Expansion.Error("undefined_local_capture", amp)
                }
            }
    }
}

/**
 * `capture_require/4` for [call], a remote call: the module part, unless it has an `&N`, is expanded first. A module
 * part that expands to a variable needs no lookup, and one that expands to an atom needs `require_function/5`.
 */
private fun captureRequire(
    amp: ElixirAst,
    call: ElixirAst.Call,
    arguments: Arguments,
    state: ExState,
    env: Env,
    run: Run,
): Expansion {
    val module = ((call.callee as ElixirAst.Call).arguments!!)[0]
    val escape = Escape(run, env.module).apply { escape(module) }

    escape.error?.let { return it }

    return if (escape.variables.isNotEmpty()) {
        captureExpr(captureAt(amp, call, run), call, arguments, state, env, run, escape)
    } else {
        Expander.expand(module, state, env, run).thenValue { s, e, value ->
            val at = captureAt(amp, call, run, plainRemote = true)
            // The `fn` calls the expanded module part. Only an atom's is known without the expanded tree, so any other
            // module part is expanded again in the `fn`.
            val expanded = (value as? Term.Atom)
                ?.let { withModule(call, ElixirAst.Literal.Atom(module.meta, it.name)) }
                ?: call

            when {
                arguments == Arguments.NON_SEQUENTIAL -> captureExpr(at, expanded, arguments, s, e, run)
                value == VARIABLE_NODE -> Expansion.Expanded(s, e, NODE)
                value is Term.Atom ->
                    requireFunction(amp, call, value.name, s, e, run) ?: captureExpr(at, expanded, arguments, s, e, run)
                else -> captureExpr(at, call, arguments, s, e, run)
            }
        }
    }
}

/**
 * `elixir_dispatch:require_function/5` for a capture of [call] on [receiver], and then `expand_fn_capture/4`'s answer,
 * or `null` for Elixir's `false`: a macro, whose capture is an `fn` that calls it.
 */
private fun requireFunction(
    amp: ElixirAst,
    call: ElixirAst.Call,
    receiver: String,
    state: ExState,
    env: Env,
    run: Run,
): Expansion? {
    val name = ((call.callee as ElixirAst.Call).arguments!![1] as ElixirAst.Literal.Atom).name
    val arity = call.arguments!!.size
    val required = receiver in env.requires

    when (isMacro(receiver, name, arity, required, run)) {
        null -> return Expansion.Unported(amp)
        // Unrequired, Elixir reads the macros only of a module already loaded on its node.
        true -> return if (required) null else Expansion.Unported(amp)
        false -> Unit
    }

    val (inlinedReceiver, inlinedName) = inline(receiver, name, arity, run.level) ?: (receiver to name)

    run.observer.dispatched(call, Dispatch(Dispatch.Kind.REMOTE_FUNCTION, inlinedReceiver, inlinedName, arity))

    return Expansion.Expanded(state, env, NODE)
}

/** [call], a remote call, with [module] as its module part. */
private fun withModule(call: ElixirAst.Call, module: ElixirAst): ElixirAst.Call {
    val dot = call.callee as ElixirAst.Call

    return ElixirAst.Call(
        call.meta,
        ElixirAst.Call(dot.meta, dot.callee, listOf(module, dot.arguments!![1]), dot.context),
        call.arguments,
        call.context,
    )
}

/** The metadata of the call `&f/a` or `&M.f/a` captures, built from [name], `f` or `M.f`. */
private fun arityCallMeta(amp: ElixirAst, name: ElixirAst, run: Run): Meta =
    if (REMOTE_CAPTURE_REPORTED_AT_CALL.isSufficient(run.level)) name.meta else amp.meta

/**
 * Where a capture of [call] reports its errors: [call] from 1.16, or from 1.14.0-rc.1 for a [plainRemote] call, one
 * whose module part has no `&N`; [amp] before.
 */
private fun captureAt(amp: ElixirAst, call: ElixirAst, run: Run, plainRemote: Boolean = false): ElixirAst {
    val entry = if (plainRemote) REMOTE_CAPTURE_REPORTED_AT_CALL else CAPTURE_REPORTED_AT_CALL

    return if (entry.isSufficient(run.level)) call else amp
}

/**
 * `capture_expr/6`: [expr] with each `&N` replaced by the variable for `N`, as the body of an `fn` whose parameters are
 * those variables in order, which must be `&1` to the highest `&N`.
 *
 * @param at where the capture's errors are reported, and the `fn`'s metadata
 * @param escape holding the variables of [expr]'s module part, when `capture_require/4` escaped it first
 */
private fun captureExpr(
    at: ElixirAst,
    expr: ElixirAst,
    arguments: Arguments,
    state: ExState,
    env: Env,
    run: Run,
    escape: Escape = Escape(run, env.module),
): Expansion {
    val body = escape.escape(expr)

    escape.error?.let { return it }

    val positions = escape.variables.keys.toList()

    return when {
        positions.isEmpty() && arguments == Arguments.NON_SEQUENTIAL -> Expansion.Error("invalid_args_for_capture", at)
        positions.withIndex().any { (index, position) -> position != (index + 1).toBigInteger() } ->
            Expansion.Error("capture_arg_without_predecessor", at)
        else -> {
            val clause = ElixirAst.Call(
                at.meta,
                ElixirAst.Literal.Atom(at.meta, "->"),
                listOf(ElixirAst.ListNode(at.meta, escape.variables.values.toList()), body)
            )

            expandFn(ElixirAst.Call(at.meta, ElixirAst.Literal.Atom(at.meta, "fn"), listOf(clause)), state, env, run)
        }
    }
}

/**
 * `escape/3`: a node with each `&N` replaced by the variable for `N`, and the first error an `&` in it gives.
 *
 * @param module the module whose hygiene counter each variable takes
 */
private class Escape(private val run: Run, private val module: String?) {
    /** The variable for each `N`, at its first `&N`, by `N`. */
    val variables = sortedMapOf<BigInteger, ElixirAst>()
    var error: Expansion.Error? = null
        private set

    fun escape(node: ElixirAst): ElixirAst =
        if (error != null) {
            node
        } else {
            when (node) {
                is ElixirAst.Call ->
                    if ((node.callee as? ElixirAst.Literal.Atom)?.name == "&") {
                        argument(node)
                    } else {
                        ElixirAst.Call(node.meta, escape(node.callee), node.arguments?.map(::escape), node.context)
                    }
                is ElixirAst.Tuple -> ElixirAst.Tuple(node.meta, node.elements.map(::escape))
                is ElixirAst.ListNode -> ElixirAst.ListNode(node.meta, node.elements.map(::escape))
                is ElixirAst.Block -> ElixirAst.Block(node.meta, node.expressions.map(::escape))
                is ElixirAst.Alias -> ElixirAst.Alias(node.meta, node.segments.map(::escape))
                is ElixirAst.Literal, is ElixirAst.Placeholder -> node
            }
        }

    private fun argument(node: ElixirAst.Call): ElixirAst {
        val position = (node.arguments?.singleOrNull() as? ElixirAst.Literal.Integer)?.value

        error = when {
            position == null -> Expansion.Error("nested_capture", node)
            position.signum() <= 0 ->
                Expansion.Error(
                    if (CAPTURE_ARGUMENT_BELOW_ONE_IS_INVALID_ARITY.isSufficient(run.level)) {
                        "invalid_arity_for_capture"
                    } else {
                        "unallowed_capture_arg"
                    },
                    node
                )
            else -> return variables.getOrPut(position) { variable(node, position) }
        }

        return node
    }

    /**
     * The variable for `N`: `&N` in the `nil` context, which no source variable can be named, until each takes the next
     * hygiene counter of [module] instead.
     */
    private fun variable(node: ElixirAst.Call, position: BigInteger): ElixirAst =
        if (CAPTURE_ARGUMENT_COUNTER.isSufficient(run.level)) {
            val counter = Meta.Key.Entry("counter", counterMetaValue(run.counters.next(module)))
            val capture = Meta.Key.Entry("capture", Meta.Value.Integer(position.toLong()))
                .takeIf { CAPTURE_ARGUMENT_POSITION_META.isSufficient(run.level) }
            val keys = listOfNotNull(counter, capture) + node.meta.keys
            val meta = node.meta.let { Meta(it.origin, it.start, it.end, keys, it.built) }

            if (CAPTURE_ARGUMENT_IN_ELIXIR_FN_CONTEXT.isSufficient(run.level)) {
                val name = ElixirAst.Literal.Atom(node.meta, "_&")

                ElixirAst.Call(meta, name, null, ElixirAst.VariableContext.Atom("elixir_fn"))
            } else {
                ElixirAst.Call(meta, ElixirAst.Literal.Atom(node.meta, "capture"), null)
            }
        } else {
            ElixirAst.Call(node.meta, ElixirAst.Literal.Atom(node.meta, "&$position"), null)
        }
}

/** `args_from_arity/3`: `&1` to `&arity`, or `null` where [arity] is outside 0 to 255. */
private fun argumentsFromArity(amp: ElixirAst, arity: ElixirAst): List<ElixirAst>? {
    val value = (arity as ElixirAst.Literal.Integer).value

    return if (value.signum() >= 0 && value <= 255.toBigInteger()) {
        (1..value.toInt()).map { position ->
            ElixirAst.Call(amp.meta, ElixirAst.Literal.Atom(amp.meta, "&"), listOf(ElixirAst.Literal.Integer(amp.meta, position.toBigInteger())))
        }
    } else {
        null
    }
}

/** `check_sequential_and_not_empty/1`: [args] are `&1` to `&N` in order, and there is at least one. */
private fun arguments(args: List<ElixirAst>): Arguments =
    if (args.isNotEmpty() && args.withIndex().all { (index, arg) -> isCaptureArgument(arg, index + 1) }) {
        Arguments.SEQUENTIAL
    } else {
        Arguments.NON_SEQUENTIAL
    }

private fun isCaptureArgument(node: ElixirAst, position: Int): Boolean =
    isCall(node, "&", 1) &&
        ((node as ElixirAst.Call).arguments!!.single() as? ElixirAst.Literal.Integer)?.value == position.toBigInteger()

/** `{{'.', _, [_, Fun]}, _, []}` with an atom `Fun`: a remote call of no arguments. */
private fun isRemoteFunction(node: ElixirAst): Boolean {
    val dot = (node as? ElixirAst.Call)?.callee as? ElixirAst.Call ?: return false

    return (dot.callee as? ElixirAst.Literal.Atom)?.name == "." &&
        dot.arguments?.size == 2 &&
        dot.arguments[1] is ElixirAst.Literal.Atom &&
        node.arguments?.isEmpty() == true
}

private fun isInteger(node: ElixirAst) = node is ElixirAst.Literal.Integer

private fun captureHead(pattern: String) = Clause.Head("elixir_fn", "capture", 2, pattern)

private fun escapeHead(pattern: String) = Clause.Head("elixir_fn", "escape", 1, pattern)
