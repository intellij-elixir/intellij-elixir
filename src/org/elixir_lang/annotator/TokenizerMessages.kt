package org.elixir_lang.annotator

/** Elixir's column: code points since the start of the line, a tab counting as one, from 1. */
internal fun column(text: CharSequence, offset: Int): Int {
    var start = offset

    while (start > 0 && text[start - 1] != '\n') start--

    return Character.codePointCount(text, start, offset) + 1
}

/** Erlang's `~4.16.0B` fills a code point that needs more than four hexadecimal digits with stars. */
internal fun codePointHexadecimal(codePoint: Int): String = if (codePoint > 0xFFFF) "****" else "%04X".format(codePoint)

/** What Elixir's tokenizer reports for a character that cannot stand where it does. */
internal fun unexpectedToken(codePoint: Int, column: Int): String {
    val character = String(Character.toChars(codePoint))

    return "unexpected token: \"$character\" (column $column, code point U+${codePointHexadecimal(codePoint)})"
}

/** Elixir starts only an atom or a keyword key with a non-ASCII uppercase or titlecase letter. */
internal fun startsOnlyAnAtom(codePoint: Int): Boolean =
    codePoint > 127 &&
        Character.getType(codePoint).let {
            it == Character.UPPERCASE_LETTER.toInt() || it == Character.TITLECASE_LETTER.toInt()
        }
