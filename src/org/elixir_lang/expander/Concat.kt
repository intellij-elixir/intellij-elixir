package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.PINNED_BINARY_SEGMENT_INFERS_SIZE
import org.elixir_lang.lowering.ElixirAst

/** `Kernel.<>/2`: one `<<>>` of every operand of the chain, each but a binary as `::binary`. */
internal val CONCAT = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val (left, right) = node.arguments!!
        val operands = listOf(left) + concatenations(right)
        val s = Synthetic(node.meta)
        val parts = operands.mapIndexed { index, operand ->
            when (operand) {
                is ElixirAst.Literal.Binary -> operand
                is ElixirAst.Literal, is ElixirAst.ListNode -> return Summary.Output.Raised("concat_not_binary")
                else -> {
                    val argument = if (index < operands.lastIndex && env.context == Env.Context.MATCH) {
                        expandArgument(operand, state, env, run) { stopped -> return stopped }
                            .takeUnless { isUnknownSize(it, run) } ?: return Summary.Output.Raised("concat_unknown_size")
                    } else {
                        operand
                    }

                    s.call("::", listOf(argument, s.variable("binary", "nil")))
                }
            }
        }

        return Summary.Output.Built(s.call("<<>>", parts))
    }
}

/** `extract_concatenations/2`'s operands of [node]: every local `<>` on the right flattened. */
private fun concatenations(node: ElixirAst): List<ElixirAst> =
    if (isCall(node, "<>", 2)) {
        val (left, right) = (node as ElixirAst.Call).arguments!!

        listOf(left) + concatenations(right)
    } else {
        listOf(node)
    }

/** A left operand in a match whose size can't be known: a variable written in source, or before [PINNED_BINARY_SEGMENT_INFERS_SIZE] one pinned. */
private fun isUnknownSize(node: ElixirAst, run: Run): Boolean =
    isSourceVariable(node) && !isDirBinary(node) ||
        !PINNED_BINARY_SEGMENT_INFERS_SIZE.isSufficient(run.level) && isCall(node, "^", 1) &&
        isSourceVariable((node as ElixirAst.Call).arguments!!.single())

/** `{Var, _, nil}` with an atom `Var`. */
private fun isSourceVariable(node: ElixirAst): Boolean =
    node is ElixirAst.Call && node.arguments == null && node.context == ElixirAst.VariableContext.Nil &&
        node.callee is ElixirAst.Literal.Atom
