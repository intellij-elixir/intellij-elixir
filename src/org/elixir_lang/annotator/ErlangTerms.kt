package org.elixir_lang.annotator

import org.elixir_lang.language_level.ElixirLanguageFeature.MAYBE_RESERVED
import org.elixir_lang.language_level.ElixirLanguageLevel

/** A lowercase Latin-1 word, which Erlang prints as an atom without quotes. */
private val BARE_ATOM = Regex("[a-zß-öø-ÿ][A-Za-z0-9_@À-ÖØ-öø-ÿ]*")

/** `erl_scan`'s reserved words, which Erlang prints quoted. */
private val RESERVED_WORDS = setOf(
    "after", "and", "andalso", "band", "begin", "bnot", "bor", "bsl", "bsr", "bxor", "case", "catch", "cond", "div",
    "end", "fun", "if", "let", "not", "of", "or", "orelse", "receive", "rem", "try", "when", "xor"
)

/** How Erlang prints [text] as an atom in Elixir's parser errors; the OTP running Elixir reserves `maybe` from 27. */
internal fun erlangAtom(text: String, languageLevel: ElixirLanguageLevel): String =
    if (
        BARE_ATOM.matches(text) &&
        text !in RESERVED_WORDS &&
        !(text == "maybe" && MAYBE_RESERVED.isSufficient(languageLevel))
    ) {
        text
    } else {
        "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }

internal fun syntaxErrorBefore(token: String): String = "syntax error before: $token"
