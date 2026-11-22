package org.elixir_lang.code

/** Elixir's reserved words, none of which is a plain identifier. */
internal val RESERVED_WORDS: Set<String> = setOf(
    "true", "false", "nil",
    "when", "and", "or", "not", "in",
    "fn", "do", "end", "catch", "rescue", "after", "else"
)

internal fun escapeElixirVariableKeyword(text: String): String =
    if (text in RESERVED_WORDS) {
        "erlangVariable${text.replaceFirstChar { it.uppercase() }}"
    } else {
        text
    }
