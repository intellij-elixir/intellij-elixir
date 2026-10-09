package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst

/**
 * Told of each node [Expander] reaches, before any clause runs, and as it is left. A node the expander built
 * ([org.elixir_lang.lowering.Meta.built]) is neither entered nor left, but its env is reported by [builtIn] and its
 * dispatches are reported.
 */
fun interface ExpansionObserver {
    fun entering(node: ElixirAst, state: ExState, env: Env)

    /** A node the expander built is reached in [env], which its dispatches are in. */
    fun builtIn(env: Env) {}

    /** [node]'s [expansion], as [Expander.expand] returns it. */
    fun left(node: ElixirAst, expansion: Expansion) {}

    /**
     * [node] dispatches as [dispatch], once its receiver is expanded and before its arguments are: a call of a
     * function, or of a macro whose expansion is modelled.
     */
    fun dispatched(node: ElixirAst, dispatch: Dispatch) {}

    /** [capture], a capture of a remote function, dispatches next: the `&`, which a built capture is not entered as. */
    fun capturing(capture: ElixirAst) {}

    /**
     * `quote` traces the import that [node], a quoted call, name or capture, finds for [name] in [module]: at its one
     * arity for [QuotedImportKind.IMPORTED_FUNCTION] and [QuotedImportKind.IMPORTED_MACRO], and at each arity [module]
     * imports it at, ascending, for [QuotedImportKind.IMPORTED_QUOTED]. A function's [module] and [name] are after
     * `elixir_rewrite:inline/3`, as a [Dispatch]'s are.
     */
    fun quotedImport(node: ElixirAst, kind: QuotedImportKind, module: String, name: String, arities: List<Int>) {}

    /** The struct [node] builds, updates or matches expands as [module]'s, with [keys] given as written. */
    fun structExpanded(node: ElixirAst, module: String, keys: List<String>) {}

    companion object {
        val NONE = ExpansionObserver { _, _, _ -> }
    }
}
