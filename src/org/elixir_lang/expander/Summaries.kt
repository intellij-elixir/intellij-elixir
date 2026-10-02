package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst

/** A macro whose expansion is modelled. */
internal fun interface Summary {
    /** What expanding [node], a call that dispatches as [dispatch], gives. */
    fun expand(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion
}

/** The summary registry: each modelled macro, by its receiver, name and arity. */
internal object Summaries {
    private val SUMMARIES: Map<Triple<String, String, Int>, Summary> = emptyMap()

    fun of(dispatch: Dispatch): Summary? = SUMMARIES[Triple(dispatch.receiver, dispatch.name, dispatch.arity)]
}

/**
 * A call of a macro, which [node] dispatches as [dispatch]: its summary's expansion, or [Expansion.Opaque] where the
 * macro has none. The observer is told of the dispatch only when it is modelled.
 */
internal fun macro(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val summary = Summaries.of(dispatch) ?: return Expansion.Opaque(node, dispatch)

    run.observer.dispatched(node, dispatch)

    return summary.expand(dispatch, node, state, env, run)
}
