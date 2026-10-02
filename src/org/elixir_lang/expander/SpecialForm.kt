package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.CURSOR_RAISES
import org.elixir_lang.language_level.ElixirLanguageLevel

/** `elixir_import:special_form/2`: whether [name]/[arity] is a special form, which an `import` can't bring in. */
internal fun specialForm(name: String, arity: Int, level: ElixirLanguageLevel): Boolean =
    when (name) {
        "__cursor__" -> CURSOR_RAISES.isSufficient(level)
        in ANY_ARITY -> true
        else -> SPECIFIC_ARITY[name]?.contains(arity) == true
    }

private val ANY_ARITY = setOf("__aliases__", "__block__", "->", "<<>>", "{}", "%{}", "fn", "super", "for", "with")

private val SPECIFIC_ARITY = mapOf(
    "&" to setOf(1),
    "^" to setOf(1),
    "=" to setOf(2),
    "%" to setOf(2),
    "|" to setOf(2),
    "." to setOf(2),
    "::" to setOf(2),
    "alias" to setOf(1, 2),
    "require" to setOf(1, 2),
    "import" to setOf(1, 2),
    "__ENV__" to setOf(0),
    "__CALLER__" to setOf(0),
    "__STACKTRACE__" to setOf(0),
    "__MODULE__" to setOf(0),
    "__DIR__" to setOf(0),
    "quote" to setOf(1, 2),
    "unquote" to setOf(1),
    "unquote_splicing" to setOf(1),
    "cond" to setOf(1),
    "case" to setOf(2),
    "try" to setOf(1),
    "receive" to setOf(1),
)
