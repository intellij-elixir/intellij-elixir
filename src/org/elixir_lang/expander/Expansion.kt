package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/** What [Expander.expand] gives a node. */
sealed class Expansion {
    /**
     * The state and env Elixir leaves after the node.
     *
     * @property value the term the node expands to
     */
    data class Expanded(val state: ExState, val env: Env, val value: Term) : Expansion()

    /**
     * Elixir's expander raises at [at], the node whose metadata it reports.
     *
     * @property kind the error's reason as atom text, such as `undefined_var`
     */
    data class Error(val kind: String, val at: ElixirAst) : Expansion()

    /** [at] reaches a clause, or a branch of one, that isn't ported. */
    data class Unported(val at: ElixirAst) : Expansion()
}
