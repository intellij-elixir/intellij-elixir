package org.elixir_lang.expander

import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.admits
import org.elixir_lang.language_level.ElixirLanguageFeature.CURSOR_RAISES
import org.elixir_lang.language_level.ElixirLanguageLevel

/** `elixir_import:special_form/2`: whether [name]/[arity] is a special form, which an `import` can't bring in. */
internal fun specialForm(name: String, arity: Int, level: ElixirLanguageLevel): Boolean =
    specialFormArity(name, level)?.admits(arity) == true

/** The arities `elixir_import:special_form/2` takes [name] at, `null` where it is no special form. */
internal fun specialFormArity(name: String, level: ElixirLanguageLevel): ArityKnowledge? =
    when (name) {
        "__cursor__" -> ArityKnowledge.Open(0).takeIf { CURSOR_RAISES.isSufficient(level) }
        in ANY_ARITY -> ArityKnowledge.Open(0)
        else -> SPECIFIC_ARITY[name]
    }

private val ANY_ARITY = setOf("__aliases__", "__block__", "->", "<<>>", "{}", "%{}", "fn", "super", "for", "with")

private val SPECIFIC_ARITY: Map<String, ArityKnowledge> = mapOf(
    "&" to ArityKnowledge.Exact(1),
    "^" to ArityKnowledge.Exact(1),
    "=" to ArityKnowledge.Exact(2),
    "%" to ArityKnowledge.Exact(2),
    "|" to ArityKnowledge.Exact(2),
    "." to ArityKnowledge.Exact(2),
    "::" to ArityKnowledge.Exact(2),
    "alias" to ArityKnowledge.Range(1, 2),
    "require" to ArityKnowledge.Range(1, 2),
    "import" to ArityKnowledge.Range(1, 2),
    "__ENV__" to ArityKnowledge.Exact(0),
    "__CALLER__" to ArityKnowledge.Exact(0),
    "__STACKTRACE__" to ArityKnowledge.Exact(0),
    "__MODULE__" to ArityKnowledge.Exact(0),
    "__DIR__" to ArityKnowledge.Exact(0),
    "quote" to ArityKnowledge.Range(1, 2),
    "unquote" to ArityKnowledge.Exact(1),
    "unquote_splicing" to ArityKnowledge.Exact(1),
    "cond" to ArityKnowledge.Exact(1),
    "case" to ArityKnowledge.Exact(2),
    "try" to ArityKnowledge.Exact(1),
    "receive" to ArityKnowledge.Exact(1),
)
