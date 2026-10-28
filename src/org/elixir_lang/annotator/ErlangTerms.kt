package org.elixir_lang.annotator

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.erlScanReservedWord

/** A lowercase Latin-1 word, which Erlang prints as an atom without quotes. */
private val BARE_ATOM = Regex("[a-zß-öø-ÿ][A-Za-z0-9_@À-ÖØ-öø-ÿ]*")

/** How Erlang prints [text] as an atom in Elixir's parser errors. */
internal fun erlangAtom(text: String, languageLevel: ElixirLanguageLevel): String =
    if (BARE_ATOM.matches(text) && !erlScanReservedWord(text, languageLevel)) {
        text
    } else {
        "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }

internal fun syntaxErrorBefore(token: String): String = "syntax error before: $token"
