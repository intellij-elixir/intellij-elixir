package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/** The heads of `elixir_clauses:guard/3`, which isn't a clause of `expand`. */
internal val GUARD_HEADS = listOf(
    Clause.Head("elixir_clauses", "guard", 1, "{'when',_,[_,_]}"),
    Clause.Head("elixir_clauses", "guard", 1, "_"),
)

/**
 * `elixir_clauses:guard/3`: [node] split at each `when`, each part expanded in turn in the context the caller set,
 * which is a guard's.
 */
internal fun guard(
    node: ElixirAst,
    state: ExState,
    env: Env,
    level: ElixirLanguageLevel,
    exports: Exports,
    structs: Structs,
): Expansion = guard(node, state, env, Run(level, ExpansionObserver.NONE, exports, structs))

internal fun guard(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion =
    if (isCall(node, "when", 2)) {
        val (left, right) = (node as ElixirAst.Call).arguments!!

        guard(left, state, env, run).then { s, e -> guard(right, s, e, run) }
    } else {
        Expander.expand(node, state, env, run)
    }
