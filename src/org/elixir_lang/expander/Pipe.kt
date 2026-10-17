package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.language_level.ElixirLanguageFeature.ELLIPSIS_NULLARY_CALL
import org.elixir_lang.language_level.ElixirLanguageFeature.FROM_BRACKETS_ON_BRACKETED_EXPRESSION
import org.elixir_lang.language_level.ElixirLanguageFeature.PIPE_ONE_OPERAND
import org.elixir_lang.language_level.ElixirLanguageFeature.PIPE_RIGHT_UNPIPED
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta

/** `Kernel.|>/2`: `Macro.pipe/3` folded over `Macro.unpipe/1`. */
internal val PIPE = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val (left, right) = node.arguments!!
        val operands = when {
            PIPE_RIGHT_UNPIPED.isSufficient(run.level) -> listOf(left) + unpipe(right)
            PIPE_ONE_OPERAND.isSufficient(run.level) -> listOf(left, right)
            else -> unpipe(left) + unpipe(right)
        }
        var piped = operands.first()

        for (call in operands.drop(1)) {
            ProgressManager.checkCanceled()

            piped = when (val output = pipe(piped, call, run.level)) {
                is Summary.Output.Built -> output.ast
                else -> return output
            }
        }

        return Summary.Output.Built(piped)
    }
}

/** `Macro.unpipe/1`: the operands of every local `|>` in [node], in order. */
private fun unpipe(node: ElixirAst): List<ElixirAst> {
    ProgressManager.checkCanceled()

    return if (isCall(node, "|>", 2)) (node as ElixirAst.Call).arguments!!.flatMap(::unpipe) else listOf(node)
}

/** `Macro.pipe/3` at position 0: [expr] put first in [call]'s arguments. */
private fun pipe(expr: ElixirAst, call: ElixirAst, level: ElixirLanguageLevel): Summary.Output =
    when (call) {
        is ElixirAst.Call -> pipeCall(expr, call, level)
        is ElixirAst.Block -> Summary.Output.Built(ElixirAst.Block(call.meta, listOf(expr) + call.expressions))
        is ElixirAst.Placeholder ->
            if (call.stopsExpansion) Summary.Output.Unported(call) else Summary.Output.Raised("pipe_bad_target")
        is ElixirAst.Value -> pipeValue(expr, call)
        is ElixirAst.Alias, is ElixirAst.Tuple, is ElixirAst.Literal, is ElixirAst.ListNode ->
            Summary.Output.Raised("pipe_bad_target")
    }

/**
 * `Macro.pipe/3` into [value]: a 3-tuple whose last element is an atom or a list is built into a call as any other is,
 * and expanding it raises.
 */
private fun pipeValue(expr: ElixirAst, value: ElixirAst.Value): Summary.Output {
    val tuple = (value.term as? OtpErlangTuple)?.takeIf { it.arity() == 3 } ?: return Summary.Output.Raised("pipe_bad_target")
    val piped = when (val args = tuple.elementAt(2)) {
        is OtpErlangAtom -> OtpErlangList(expr.toOtp())
        is OtpErlangList -> OtpErlangList(arrayOf(expr.toOtp(), *args.elements()), args.lastTail)
        // A list of bytes.
        is OtpErlangString -> OtpErlangList(arrayOf(expr.toOtp(), *OtpErlangList(args.stringValue()).elements()))
        else -> return Summary.Output.Raised("pipe_bad_target")
    }

    return Summary.Output.Built(ElixirAst.Value(value.meta, OtpErlangTuple(arrayOf(tuple.elementAt(0), tuple.elementAt(1), piped))))
}

private fun pipeCall(expr: ElixirAst, call: ElixirAst.Call, level: ElixirLanguageLevel): Summary.Output {
    val name = (call.callee as? ElixirAst.Literal.Atom)?.name
    val arguments = call.arguments ?: return Summary.Output.Built(ElixirAst.Call(call.meta, call.callee, listOf(expr)))

    return when {
        name == "&" || name == "%{}" || name == "<<>>" -> Summary.Output.Raised("pipe_bad_target")
        (name == "unquote" || name == "unquote_splicing") && arguments.isEmpty() -> Summary.Output.Raised("pipe_special_form")
        name == "fn" -> Summary.Output.Raised("pipe_fn")
        (name == "+" || name == "-") && arguments.size == 1 -> Summary.Output.Raised("pipe_unary")
        FROM_BRACKETS_ON_BRACKETED_EXPRESSION.isSufficient(level) && isAccessGet(call) -> {
            val dot = call.callee as ElixirAst.Call

            if (dot.meta.keys.any(::isFromBrackets)) {
                Summary.Output.Raised("pipe_from_brackets")
            } else {
                // `{op, meta, ...}`, where `op` is the `.`'s arguments, as Elixir builds it.
                Summary.Output.Built(ElixirAst.Call(dot.meta, ElixirAst.ListNode(dot.meta, dot.arguments!!), listOf(expr) + arguments))
            }
        }
        name != null && (name in UNARY_OPERATORS || name == "..." && ELLIPSIS_NULLARY_CALL.isSufficient(level)) ->
            Summary.Output.Raised("pipe_operator_arity")
        name != null && name in BINARY_OPERATORS -> Summary.Output.Raised("pipe_operator_arity")
        else -> Summary.Output.Built(ElixirAst.Call(call.meta, call.callee, listOf(expr) + arguments))
    }
}

/** `{{_, Meta, [Access, get]}, _, [First, Second]}`, the call bracket access lowers to. */
private fun isAccessGet(call: ElixirAst.Call): Boolean {
    val dotArguments = (call.callee as? ElixirAst.Call)?.arguments ?: return false

    return call.arguments?.size == 2 &&
        dotArguments.map { (it as? ElixirAst.Literal.Atom)?.name } == listOf("Elixir.Access", "get")
}

private fun isFromBrackets(key: Meta.Key): Boolean =
    key is Meta.Key.Entry && key.name == "from_brackets" && (key.value as? Meta.Value.Atom)?.name == "true"

/** `Macro.operator?(name, 1)`, but `...`, which joins it at [ELLIPSIS_NULLARY_CALL]. */
private val UNARY_OPERATORS = setOf("&", "!", "^", "not", "+", "-", "~~~", "@")

/**
 * `Macro.operator?(name, 2)`. `**` joins it at the `POWER_OPERATOR` that first parses it, and `//`, an operator only
 * on 1.12 and 1.13, never parses outside `..//`.
 */
private val BINARY_OPERATORS = setOf(
    "<-", "\\\\", "when", "::", "|", "=", "||", "|||", "or", "&&", "&&&", "and", "==", "!=", "=~", "===", "!==", "<",
    "<=", ">=", ">", "|>", "<<<", ">>>", "<~", "~>", "<<~", "~>>", "<~>", "<|>", "in", "^^^", "++", "--", "..", "<>",
    "+++", "---", "+", "-", "*", "/", ".", "**",
)
