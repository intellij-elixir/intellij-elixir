package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/** What [Expander.expand] gives a node. */
sealed class Expansion {
    /**
     * The state and env Elixir leaves after the node.
     *
     * @property value the term the node expands to
     * @property ast the node `elixir_expand:expand/3` returns, where the run asked for it
     * @property variable the variable the node expands to, where [value] is [VARIABLE_NODE]
     */
    data class Expanded(
        val state: ExState,
        val env: Env,
        val value: Term,
        val ast: ExpandedAst,
        val variable: ElixirAst? = null,
    ) : Expansion()

    /**
     * Elixir's expander raises at [at], the node whose metadata it reports.
     *
     * @property kind the error's reason as atom text, such as `undefined_var`, or `null` for a raise the evaluator
     *   computed whose exception isn't known
     */
    data class Error(val kind: String?, val at: ElixirAst) : Expansion()

    /** [at] reaches a clause, or a branch of one, that isn't ported. */
    data class Unported(val at: ElixirAst) : Expansion()

    /** [at] calls a macro, as [dispatch] finds it, whose expansion isn't modelled. */
    data class Opaque(val at: ElixirAst, val dispatch: Dispatch) : Expansion()
}

/** The node `elixir_expand:expand/3` returns for a node, built by the clause that expands it. */
sealed class ExpandedAst {
    class Built(val node: ElixirAst) : ExpandedAst()

    /** The run didn't ask for the node. */
    data object NotAsked : ExpandedAst()
}
