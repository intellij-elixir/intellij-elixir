package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/**
 * The term [node], a directive's argument, expands to in [env] at [level]: an alias through [env]'s aliases, an atom,
 * integer or binary, and lists and two-element tuples of these. A variable is [Term.Other]. `null` for anything
 * else, whose expansion isn't known here.
 */
internal fun directiveValue(node: ElixirAst, env: Env, level: ElixirLanguageLevel): Term? =
    when (node) {
        is ElixirAst.Alias -> aliasesModule(node, env, level)?.let { Term.Atom(it) }
        is ElixirAst.Literal.Atom -> Term.Atom(node.name)
        is ElixirAst.Literal.Integer -> Term.Integer(node.value)
        is ElixirAst.Literal.Binary -> Term.Binary(node.bytes)
        is ElixirAst.ListNode ->
            node.elements
                .takeUnless { elements -> elements.lastOrNull()?.let { isCall(it, "|", 2) } == true }
                ?.map { directiveValue(it, env, level) ?: return null }
                ?.let { Term.List(it) }
        is ElixirAst.Tuple ->
            node.elements.takeIf { it.size == 2 }?.let { (first, second) ->
                Term.Pair(
                    directiveValue(first, env, level) ?: return null,
                    directiveValue(second, env, level) ?: return null,
                )
            }
        is ElixirAst.Call -> Term.Other.takeIf { isVariable(node) && !isEnvironmentName(node) }
        is ElixirAst.Literal.Float, is ElixirAst.Block, is ElixirAst.Placeholder -> null
    }
