package org.elixir_lang.parser

import com.intellij.lang.ITokenTypeRemapper
import com.intellij.psi.tree.IElementType
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.language_level.ElixirLanguageFeature.DECIMAL_NUMBER_ENDS_BEFORE_WORD
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * The lexer reads letters directly after a number's digits as invalid digits of that number. Where Elixir ends the
 * number there instead, a word it allows in that position, as in `0b1and 2`, is given back its own token type.
 */
class WordAfterNumber(private val languageLevel: ElixirLanguageLevel) : ITokenTypeRemapper {
    override fun filter(source: IElementType, start: Int, end: Int, text: CharSequence): IElementType {
        val isDigit = DIGITS[source] ?: return source
        if (
            source == ElixirTypes.INVALID_DECIMAL_DIGITS &&
            !DECIMAL_NUMBER_ENDS_BEFORE_WORD.isSufficient(languageLevel)
        ) {
            return source
        }

        val word = text.subSequence(start, end).toString()
        val type = WORDS[word] ?: return source

        return if (
            start > 0 &&
            isDigit(text[start - 1]) &&
            !continuesWordAfter(text, end, languageLevel) &&
            (word != "not" || isFollowedByIn(text, end, languageLevel))
        ) {
            type
        } else {
            source
        }
    }

    private companion object {
        val DIGITS: Map<IElementType, (Char) -> Boolean> = mapOf(
            ElixirTypes.INVALID_BINARY_DIGITS to ::isBinaryDigit,
            ElixirTypes.INVALID_DECIMAL_DIGITS to ::isDecimalDigit,
            ElixirTypes.INVALID_HEXADECIMAL_DIGITS to ::isHexadecimalDigit,
            ElixirTypes.INVALID_OCTAL_DIGITS to ::isOctalDigit,
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
