package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst

/** Told of each node [Expander] reaches, before any clause runs, and as it is left. */
fun interface ExpansionObserver {
    fun entering(node: ElixirAst, state: ExState, env: Env)

    /** [node]'s [expansion], as [Expander.expand] returns it. */
    fun left(node: ElixirAst, expansion: Expansion) {}

    /**
     * [node] dispatches as [dispatch], once its receiver is expanded and before its arguments are: a call of a
     * function, or of a macro whose expansion is modelled.
     */
    fun dispatched(node: ElixirAst, dispatch: Dispatch) {}

    companion object {
        val NONE = ExpansionObserver { _, _, _ -> }
    }
}
