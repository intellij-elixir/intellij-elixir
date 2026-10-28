package org.elixir_lang.language_level

import org.elixir_lang.language_level.ElixirLanguageFeature.MAYBE_RESERVED

/** `erl_scan:f_reserved_word/1`'s words, reserved on every OTP. */
private val RESERVED_WORDS = setOf(
    "after", "and", "andalso", "band", "begin", "bnot", "bor", "bsl", "bsr", "bxor", "case", "catch", "cond", "div",
    "end", "fun", "if", "let", "not", "of", "or", "orelse", "receive", "rem", "try", "when", "xor"
)

/** The `maybe_expr` feature's keywords, reserved where it is on. */
private val MAYBE_EXPR_KEYWORDS = setOf("maybe", "else")

/** Whether `erl_scan:reserved_word/1` holds for [text] on [level]'s OTP: no atom unquoted, and quoted when printed. */
internal fun erlScanReservedWord(text: String, level: ElixirLanguageLevel): Boolean =
    text in RESERVED_WORDS || text in MAYBE_EXPR_KEYWORDS && MAYBE_RESERVED.isSufficient(level)
