package org.elixir_lang.code

import org.elixir_lang.Macro

internal val RESERVED_VARIABLE_KEYWORDS: Set<String> =
    Macro.KEYWORD_BLOCK_KEYWORDS.toSet() + setOf("end", "fn", "in", "when", "and", "or", "not", "true", "false", "nil")

internal fun escapeElixirVariableKeyword(text: String): String =
    if (text in RESERVED_VARIABLE_KEYWORDS) {
        "erlangVariable${text.replaceFirstChar { it.uppercase() }}"
    } else {
        text
    }
