package org.elixir_lang.parser

import com.intellij.lang.ITokenTypeRemapper
import com.intellij.psi.tree.IElementType
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * The lexer reads letters directly after a number's digits as invalid digits of that number. Where Elixir ends the
 * number there instead, a word it allows in that position, as in `0b1and 2`, is given back its own token type.
 */
class WordAfterNumber(private val languageLevel: ElixirLanguageLevel) : ITokenTypeRemapper {
    override fun filter(source: IElementType, start: Int, end: Int, text: CharSequence): IElementType {
        val isDigit = DIGITS[source] ?: return source
        if (source == ElixirTypes.INVALID_DECIMAL_DIGITS && !languageLevel.endsDecimalNumberBeforeWord) return source

        val word = text.subSequence(start, end).toString()
        val type = WORDS[word] ?: return source

        return if (
            start > 0 &&
            isDigit(text[start - 1]) &&
            !continuesWord(text, end) &&
            (word != "not" || isFollowedByIn(text, end))
        ) {
            type
        } else {
            source
        }
    }

    private fun continuesWord(text: CharSequence, end: Int): Boolean {
        val next = text.getOrNull(end) ?: return false

        return Character.isLetterOrDigit(Character.codePointAt(text, end)) ||
            next in "_@?!" ||
            (next == ':' && text.getOrNull(end + 1) != ':')
    }

    /** `not in` may be split by spaces, tabs and escaped newlines, but not by a newline. */
    private fun isFollowedByIn(text: CharSequence, end: Int): Boolean {
        var offset = end

        while (true) {
            offset = when {
                text.getOrNull(offset) == ' ' || text.getOrNull(offset) == '\t' -> offset + 1
                text.startsWith("\\\n", offset) -> offset + 2
                text.startsWith("\\\r\n", offset) -> offset + 3
                else -> break
            }
        }

        return offset > end && text.startsWith("in", offset) && !continuesWord(text, offset + 2)
    }

    private companion object {
        val DIGITS: Map<IElementType, (Char) -> Boolean> = mapOf(
            ElixirTypes.INVALID_BINARY_DIGITS to { character -> character == '0' || character == '1' },
            ElixirTypes.INVALID_DECIMAL_DIGITS to { character -> character in '0'..'9' },
            ElixirTypes.INVALID_HEXADECIMAL_DIGITS to { character ->
                character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
            },
            ElixirTypes.INVALID_OCTAL_DIGITS to { character -> character in '0'..'7' },
        )

        val WORDS: Map<String, IElementType> = mapOf(
            "after" to ElixirTypes.AFTER,
            "and" to ElixirTypes.AND_WORD_OPERATOR,
            "catch" to ElixirTypes.CATCH,
            "do" to ElixirTypes.DO,
            "else" to ElixirTypes.ELSE,
            "end" to ElixirTypes.END,
            "in" to ElixirTypes.IN_OPERATOR,
            "not" to ElixirTypes.NOT_OPERATOR,
            "or" to ElixirTypes.OR_WORD_OPERATOR,
            "rescue" to ElixirTypes.RESCUE,
            "when" to ElixirTypes.WHEN_OPERATOR,
        )
    }
}
