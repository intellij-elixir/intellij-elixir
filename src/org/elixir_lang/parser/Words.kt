package org.elixir_lang.parser

import org.elixir_lang.language_level.ElixirLanguageFeature.RESERVED_WORD_BEFORE_TYPE_OPERATOR
import org.elixir_lang.language_level.ElixirLanguageLevel

/** A code point Elixir's tokenizer continues a word with: `_`, a letter, a digit or a combining mark. */
fun isWordCharacter(codePoint: Int): Boolean =
    codePoint == '_'.code ||
        Character.isLetterOrDigit(codePoint) ||
        Character.getType(codePoint).let {
            it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
        }

/** Whether the word ending at [end] goes on. From 1.12 a `::` after a reserved word leaves the word as it is. */
fun continuesWordAfter(text: CharSequence, end: Int, languageLevel: ElixirLanguageLevel): Boolean {
    if (end >= text.length) return false
    val codePoint = Character.codePointAt(text, end)

    return isWordCharacter(codePoint) ||
        codePoint == '@'.code ||
        codePoint == '?'.code ||
        codePoint == '!'.code ||
        (
            codePoint == ':'.code &&
                (text.getOrNull(end + 1) != ':' || !RESERVED_WORD_BEFORE_TYPE_OPERATOR.isSufficient(languageLevel))
        )
}

/** `not in` may be split by spaces, tabs and escaped newlines, but not by a newline. */
fun isFollowedByIn(text: CharSequence, end: Int, languageLevel: ElixirLanguageLevel): Boolean {
    var offset = end

    while (true) {
        offset = when {
            text.getOrNull(offset) == ' ' || text.getOrNull(offset) == '\t' -> offset + 1
            text.startsWith("\\\n", offset) -> offset + 2
            text.startsWith("\\\r\n", offset) -> offset + 3
            else -> break
        }
    }

    return offset > end && text.startsWith("in", offset) && !continuesWordAfter(text, offset + 2, languageLevel)
}
