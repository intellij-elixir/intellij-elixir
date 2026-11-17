package org.elixir_lang.psi

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.elixir_lang.annotator.codePointHexadecimal
import org.elixir_lang.annotator.column
import org.elixir_lang.annotator.erlangAtom
import org.elixir_lang.annotator.startsOnlyAnAtom
import org.elixir_lang.annotator.syntaxErrorBefore
import org.elixir_lang.annotator.unexpectedToken
import org.elixir_lang.annotator.unicode_security.UnicodeSecurityCheck
import org.elixir_lang.language_level.ElixirLanguageFeature.ALIAS_ERROR_COVERS_PUNCTUATION
import org.elixir_lang.language_level.ElixirLanguageFeature.BASED_NUMBER_CONTINUES_INTO_DIGITS
import org.elixir_lang.language_level.ElixirLanguageFeature.DECIMAL_NUMBER_ENDS_BEFORE_WORD
import org.elixir_lang.language_level.ElixirLanguageFeature.NORMALIZED_IDENTIFIERS
import org.elixir_lang.language_level.ElixirLanguageFeature.NUMBER_ERROR_QUOTES_THE_CHARACTER
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.parser.isBinaryDigit
import org.elixir_lang.parser.isDecimalDigit
import org.elixir_lang.parser.isFollowedByIn
import org.elixir_lang.parser.isHexadecimalDigit
import org.elixir_lang.parser.isOctalDigit
import org.elixir_lang.parser.isWordCharacter
import java.text.Normalizer

/** Whether [element] is a number of its own, which [rejectedNumber] judges: a float, or a whole number outside one. */
internal fun isNumber(element: PsiElement): Boolean =
    element is ElixirDecimalFloat || (element is WholeNumber && !isInFloat(element))

/** Whether [wholeNumber] is a part of a decimal float, not a number of its own. */
private fun isInFloat(wholeNumber: PsiElement): Boolean =
    wholeNumber.parent.let {
        it is ElixirDecimalFloatIntegral || it is ElixirDecimalFloatFractional || it is ElixirDecimalFloatExponent
    }

/**
 * Elixir reads a number's literal and then looks at the next character; the plugin's lexer instead folds letters
 * into the number, and reads `0o9` as octal where Elixir reads `0` followed by `o9`.
 */
internal fun rejectedNumber(number: PsiElement, languageLevel: ElixirLanguageLevel): Pair<TextRange, String>? {
    val text = number.containingFile.viewProvider.contents
    val start = number.textRange.startOffset
    val baseEnd = baseEnd(text, start)

    val decimalStart = if (baseEnd == null) {
        start
    } else {
        val next = text.getOrNull(baseEnd) ?: return null

        when {
            isDecimalDigit(next) && BASED_NUMBER_CONTINUES_INTO_DIGITS.isSufficient(languageLevel) -> baseEnd
            isDecimalDigit(next) -> {
                val digitsEnd = decimalEnd(text, baseEnd)

                return rejectedWord(text, start, digitsEnd, languageLevel)
                    ?: (TextRange(start, wordEnd(text, digitsEnd)) to
                        syntaxErrorBefore("\"${text.substring(baseEnd, digitsEnd)}\""))
            }
            next.isAsciiLetter() || next == '_' -> return afterLiteral(text, start, baseEnd, languageLevel)
            else -> return null
        }
    }

    val end = decimalEnd(text, decimalStart)
    val next = text.getOrNull(end)
    val decimal = text.substring(decimalStart, end)

    // Elixir casts the float before it looks at what follows, and `list_to_float` refuses one past a double's range.
    if (decimalStart == start && '.' in decimal && floatValue(decimal).isInfinite()) {
        return TextRange(start, end) to "invalid float number $decimal"
    }

    if (next != null && (next.isAsciiLetter() || next == '_')) {
        val wordEnd = wordEnd(text, end)
        val range = TextRange(start, wordEnd)

        return when {
            NUMBER_ERROR_QUOTES_THE_CHARACTER.isSufficient(languageLevel) ->
                range to "invalid character \"$next\" after number $decimal. If you intended to write a number, make " +
                    "sure to separate the number from the character (using comma, space, etc). If you meant to write " +
                    "a function name or a variable, note that identifiers in Elixir cannot start with numbers. " +
                    "Unexpected token: $next"
            !DECIMAL_NUMBER_ENDS_BEFORE_WORD.isSufficient(languageLevel) ->
                range to "invalid character $next after number $decimal. If you intended to write a number, make sure " +
                    "to add the proper punctuation character after the number (space, comma, etc). If you meant to " +
                    "write an identifier, note that identifiers in Elixir cannot start with numbers. Unexpected " +
                    "token: $next"
            else -> afterLiteral(text, start, end, languageLevel)
        }
    }

    return if (decimalStart != start) {
        TextRange(start, end) to syntaxErrorBefore("\"$decimal\"")
    } else {
        null
    }
}

/** The double Elixir's `list_to_float` reads from a float literal's text. */
internal fun floatValue(literal: String): Double = literal.replace("_", "").toDouble()

private fun afterLiteral(
    text: CharSequence,
    numberStart: Int,
    wordStart: Int,
    languageLevel: ElixirLanguageLevel
): Pair<TextRange, String>? {
    rejectedWord(text, numberStart, wordStart, languageLevel)?.let { return it }

    val wordEnd = wordEnd(text, wordStart)
    val word = text.substring(wordStart, wordEnd)

    return when {
        text.getOrNull(wordEnd) == ':' && text.getOrNull(wordEnd + 1)?.let(::isElixirSpace) == true ->
            TextRange(numberStart, wordEnd + 1) to
                if (word == "do") {
                    "unexpected keyword: do:. In case you wanted to write a \"do\" expression, you must either use " +
                        "do-blocks or separate the keyword argument with comma."
                } else {
                    syntaxErrorBefore("'$word:'")
                }
        word in KEYWORDS || (word == "not" && isFollowedByIn(text, wordEnd, languageLevel)) -> null
        else -> TextRange(numberStart, wordEnd) to syntaxErrorBefore(erlangAtom(word, languageLevel))
    }
}

/** Elixir tokenizes the word after a number before parsing, so a word it rejects reports its own error. */
private fun rejectedWord(
    text: CharSequence,
    numberStart: Int,
    wordStart: Int,
    languageLevel: ElixirLanguageLevel
): Pair<TextRange, String>? {
    val first = text.getOrNull(wordStart) ?: return null
    if (!first.isAsciiLetter() && first != '_') return null

    return rejectedWordAt(text, wordStart) { languageLevel }?.let { (range, message) ->
        TextRange(numberStart, maxOf(range.endOffset, wordEnd(text, wordStart))) to message
    }
}

/**
 * The checks of Elixir's tokenizer on a word, in its order: a keyword with a space after the colon is left alone,
 * so `[foo@bar: 1]` is valid while `foo@bar:1` is only a missing space.
 */
internal fun rejectedWordAt(
    text: CharSequence,
    start: Int,
    languageLevel: () -> ElixirLanguageLevel,
): Pair<TextRange, String>? {
    val end = wordEnd(text, start)
    if (end == start) return null

    val word = text.substring(start, end)
    // The lexer, like Elixir's tokenizer, makes `@` before `/` an identifier, as in `&@/1`.
    if (!Character.isLetter(word.codePointAt(0)) && word[0] != '_') return null

    val range = TextRange(start, end)

    if (text.getOrNull(end) == ':') {
        when (text.getOrNull(end + 1)) {
            ' ', '\t', '\r', '\n' -> return null
            null, ':' -> Unit
            else -> return TextRange(start, end + 1) to "keyword argument must be followed by space after: $word:"
        }
    }

    val first = word.codePointAt(0)
    // The lexer rejects the uppercase ones itself, but not the titlecase ones.
    if (startsOnlyAnAtom(first)) return letterThatStartsOnlyAnAtom(text, start, languageLevel)

    val kind = if (word[0] in 'A'..'Z') "alias" else "identifier"

    return when {
        '@' in word -> range to invalidCharacter('@'.code, kind, word)
        word == "__aliases__" || word == "__block__" -> range to "reserved token: $word"
        kind == "alias" -> aliasProblem(word, languageLevel())?.let { range to it }
        else -> null
    }
}

/**
 * Elixir puts a word in NFC before checking it from 1.14; before that `VersionedSyntax` reports it not being in
 * NFC.
 */
private fun aliasProblem(word: String, languageLevel: ElixirLanguageLevel): String? {
    val inNfc = Normalizer.isNormalized(word, Normalizer.Form.NFC)
    if (!inNfc && !NORMALIZED_IDENTIFIERS.isSufficient(languageLevel)) return null
    val alias = if (inNfc) word else Normalizer.normalize(word, Normalizer.Form.NFC)
    val nonAscii = alias.codePoints().filter { it > 127 }.findFirst()
    val punctuation = alias.last().takeIf { it == '?' || it == '!' }

    return when {
        !nonAscii.isPresent && punctuation == null -> null
        ALIAS_ERROR_COVERS_PUNCTUATION.isSufficient(languageLevel) ->
            invalidCharacter(
                alias.codePoints().filter { it < 'A'.code || it > 127 }.findFirst().asInt,
                "alias (only ASCII characters, without punctuation, are allowed)",
                alias
            )
        nonAscii.isPresent -> invalidCharacter(nonAscii.asInt, "alias (only ASCII characters are allowed)", alias)
        else -> invalidCharacter(punctuation!!.code, "alias", alias)
    }
}

/**
 * Words that may directly follow a based number, and before 1.12 any number, as in `1and 2`; `not` only as `not in`.
 */
private val KEYWORDS = setOf("after", "and", "catch", "do", "else", "end", "in", "or", "rescue", "when")

private fun isElixirSpace(character: Char): Boolean =
    character == ' ' || character == '\t' || character == '\r' || character == '\n'

private fun baseEnd(text: CharSequence, start: Int): Int? {
    if (text.getOrNull(start) != '0') return null

    val isDigit: (Char) -> Boolean = when (text.getOrNull(start + 1)) {
        'x' -> ::isHexadecimalDigit
        'o' -> ::isOctalDigit
        'b' -> ::isBinaryDigit
        else -> return null
    }

    return if (text.getOrNull(start + 2)?.let(isDigit) == true) digitsEnd(text, start + 2, isDigit) else null
}

private fun decimalEnd(text: CharSequence, start: Int): Int {
    var end = digitsEnd(text, start, ::isDecimalDigit)

    if (text.getOrNull(end) == '.' && text.getOrNull(end + 1)?.let(::isDecimalDigit) == true) {
        end = digitsEnd(text, end + 1, ::isDecimalDigit)

        if (text.getOrNull(end) == 'e' || text.getOrNull(end) == 'E') {
            val digits = if (text.getOrNull(end + 1) == '+' || text.getOrNull(end + 1) == '-') end + 2 else end + 1

            if (text.getOrNull(digits)?.let(::isDecimalDigit) == true) {
                end = digitsEnd(text, digits, ::isDecimalDigit)
            }
        }
    }

    return end
}

private fun digitsEnd(text: CharSequence, start: Int, isDigit: (Char) -> Boolean): Int {
    var end = start

    while (end < text.length) {
        end = when {
            isDigit(text[end]) -> end + 1
            text[end] == '_' && text.getOrNull(end + 1)?.let(isDigit) == true -> end + 2
            else -> return end
        }
    }

    return end
}

/** Elixir keeps reading a word through `@`, and ends it after a `?` or `!`. */
private fun wordEnd(text: CharSequence, start: Int): Int {
    var end = start

    while (end < text.length) {
        val codePoint = Character.codePointAt(text, end)

        end = when {
            codePoint == '?'.code || codePoint == '!'.code -> return end + 1
            codePoint == '@'.code || isWordCharacter(codePoint) -> end + Character.charCount(codePoint)
            else -> return end
        }
    }

    return end
}

private fun invalidCharacter(codePoint: Int, kind: String, word: String): String {
    val character = String(Character.toChars(codePoint))

    return "invalid character \"$character\" (code point U+${codePointHexadecimal(codePoint)}) in $kind: $word"
}

/** Elixir reads the word through `@` and reports that first, unless the letter is one no word may start with. */
internal fun letterThatStartsOnlyAnAtom(
    text: CharSequence,
    start: Int,
    languageLevel: () -> ElixirLanguageLevel,
): Pair<TextRange, String> {
    val codePoint = Character.codePointAt(text, start)
    val word = text.substring(start, wordEnd(text, start))
    val message = if ('@' in word && !UnicodeSecurityCheck.rejectsFirst(codePoint, languageLevel())) {
        invalidCharacter('@'.code, "atom", word)
    } else {
        unexpectedToken(codePoint, column(text, start))
    }

    return TextRange(start, start + Character.charCount(codePoint)) to message
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
