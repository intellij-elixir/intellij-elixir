package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst

/**
 * `elixir_fn:expand/4`: each clause of `fn` from the state before it, its head as a pattern, and every clause of one
 * arity.
 */
internal fun expandFn(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val clauses = node.arguments!!

    val fnHead: HeadExpansion = { arrow, args, headState, headEnv -> head(arrow, args, headState, headEnv, run) }

    return mapfold(clauses, state, env) { each, s, _ ->
        val args = (each as? ElixirAst.Call)?.arguments?.takeIf { it.size == 2 }?.first() as? ElixirAst.ListNode

        when {
            // Elixir has no clause for it.
            args == null -> Expansion.Unported(each)
            args.elements.any { isNamedCall(it, "\\\\") } -> Expansion.Error("defaults_in_args", node)
            else -> clauseFrom(node, fnHead, each, s, env, run)
        }
    }.then { s, e ->
        val arities = clauses.map { arity((it as ElixirAst.Call).arguments!!.first() as ElixirAst.ListNode) }.distinct()

        if (arities.size == 1) {
            Expansion.Expanded(s, e, NODE).endConstruct(env, run)
        } else {
            Expansion.Error("clauses_with_different_arities", node)
        }
    }
}

/** `elixir_fn:fn_arity/1`: a guard isn't an argument. */
private fun arity(args: ElixirAst.ListNode): Int =
    args.elements.singleOrNull()?.let(::whenArguments)?.let { it.size - 1 } ?: args.elements.size
