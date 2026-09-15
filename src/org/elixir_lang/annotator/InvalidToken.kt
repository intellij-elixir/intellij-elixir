package org.elixir_lang.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.ElixirDecimalFloat
import org.elixir_lang.psi.ElixirDecimalFloatExponent
import org.elixir_lang.psi.ElixirDecimalFloatFractional
import org.elixir_lang.psi.ElixirDecimalFloatIntegral
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.WholeNumber
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver

/**
 * Reports, as errors, the words and numbers that Elixir's tokenizer rejects and the plugin's lexer accepts.
 */
internal class InvalidToken : Annotator, DumbAware {
    /**
     * An annotation's range must lie inside the element being annotated, and a rejected word such as `foo@bar` is
     * several sibling elements, so each problem is reported from the smallest element holding all of it.
     */
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val problems = mutableListOf<Pair<TextRange, String>>()
        val firstChild = element.firstChild

        if (firstChild == null) {
            if (element.node.elementType in WORDS) word(element)?.let(problems::add)
        } else {
            if (isNumber(element)) number(element)?.let(problems::add)

            for (child in generateSequence(firstChild, PsiElement::getNextSibling)) {
                if (child.nextSibling != null) acrossBoundary(child)?.let(problems::add)
            }
        }

        val inside = problems.filter { (range, _) -> element.textRange.contains(range) }
        if (inside.isEmpty() || Injection.of(element) == Injection.UNCOMPILED) return

        for ((range, message) in inside) {
            holder.newAnnotation(HighlightSeverity.ERROR, message).range(range).create()
        }
    }

    /** A problem that starts inside [child] and runs past its end, into a later sibling. */
    private fun acrossBoundary(child: PsiElement): Pair<TextRange, String>? {
        val childRange = child.textRange
        val boundary = childRange.endOffset
        val text = child.containingFile.viewProvider.contents
        if (boundary >= text.length || text[boundary].isWhitespace()) return null

        val wordStart = wordStartBefore(text, boundary)
        val word = if (wordStart in childRange.startOffset until boundary) {
            child.findElementAt(wordStart - childRange.startOffset)
                ?.takeIf { it.textRange.startOffset == wordStart && it.node.elementType in WORDS }
                ?.let(::word)
        } else {
            null
        }
        val problem = word ?: numberEndingAt(child)?.let(::number)

        return problem?.takeIf { (range, _) -> range.startOffset >= childRange.startOffset && range.endOffset > boundary }
    }

    private fun numberEndingAt(child: PsiElement): PsiElement? {
        var element: PsiElement? = PsiTreeUtil.getDeepestLast(child)
        var number: PsiElement? = null

        while (element != null && element.textRange.endOffset == child.textRange.endOffset) {
            if (isNumber(element)) number = element
            if (element == child) break
            element = element.parent
        }

        return number
    }

    private fun isNumber(element: PsiElement): Boolean =
        element is ElixirDecimalFloat || (element is WholeNumber && !isInFloat(element))

    private fun word(leaf: PsiElement): Pair<TextRange, String>? {
        val text = leaf.containingFile.viewProvider.contents
        val start = leaf.textRange.startOffset

        if (continuesWord(text, start)) return null

        return word(text, start) { ElixirLanguageLevelResolver.languageLevelFor(leaf) }
    }

    /**
     * The checks of Elixir's tokenizer on a word, in its order: a keyword with a space after the colon is left alone,
     * so `[foo@bar: 1]` is valid while `foo@bar:1` is only a missing space.
     */
    private fun word(
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

        val kind = if (word[0] in 'A'..'Z') "alias" else "identifier"

        return when {
            '@' in word -> range to invalidCharacter('@'.code, kind, word)
            word == "__aliases__" || word == "__block__" -> range to "reserved token: $word"
            kind == "alias" -> alias(word, languageLevel())?.let { range to it }
            else -> null
        }
    }

    private fun alias(alias: String, languageLevel: ElixirLanguageLevel): String? {
        val nonAscii = alias.codePoints().filter { it > 127 }.findFirst()
        val punctuation = alias.last().takeIf { it == '?' || it == '!' }

        return when {
            !nonAscii.isPresent && punctuation == null -> null
            languageLevel >= ElixirLanguageLevel.V1_14 ->
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
     * Elixir reads a number's literal and then looks at the next character; the plugin's lexer instead folds letters
     * into the number, and reads `0o9` as octal where Elixir reads `0` followed by `o9`.
     */
    private fun number(number: PsiElement): Pair<TextRange, String>? {
        val text = number.containingFile.viewProvider.contents
        val start = number.textRange.startOffset
        val languageLevel = ElixirLanguageLevelResolver.languageLevelFor(number)
        val baseEnd = baseEnd(text, start)

        val decimalStart = if (baseEnd == null) {
            start
        } else {
            val next = text.getOrNull(baseEnd) ?: return null

            when {
                next.isAsciiDigit() && languageLevel >= ElixirLanguageLevel.V1_12 -> baseEnd
                next.isAsciiDigit() -> {
                    val digitsEnd = decimalEnd(text, baseEnd)

                    return rejectedWord(text, start, digitsEnd, languageLevel)
                        ?: (TextRange(start, wordEnd(text, digitsEnd)) to
                            "syntax error before: \"${text.substring(baseEnd, digitsEnd)}\"")
                }
                next.isAsciiLetter() || next == '_' -> return afterLiteral(text, start, baseEnd, languageLevel)
                else -> return null
            }
        }

        val end = decimalEnd(text, decimalStart)
        val next = text.getOrNull(end)

        if (next != null && (next.isAsciiLetter() || next == '_')) {
            val wordEnd = wordEnd(text, end)
            val decimal = text.substring(decimalStart, end)
            val range = TextRange(start, wordEnd)

            return when {
                languageLevel >= ElixirLanguageLevel.V1_14 ->
                    range to "invalid character \"$next\" after number $decimal. If you intended to write a number, make " +
                        "sure to separate the number from the character (using comma, space, etc). If you meant to write " +
                        "a function name or a variable, note that identifiers in Elixir cannot start with numbers. " +
                        "Unexpected token: $next"
                languageLevel >= ElixirLanguageLevel.V1_12 ->
                    range to "invalid character $next after number $decimal. If you intended to write a number, make sure " +
                        "to add the proper punctuation character after the number (space, comma, etc). If you meant to " +
                        "write an identifier, note that identifiers in Elixir cannot start with numbers. Unexpected " +
                        "token: $next"
                else -> afterLiteral(text, start, end, languageLevel)
            }
        }

        return if (decimalStart != start) {
            TextRange(start, end) to "syntax error before: \"${text.substring(decimalStart, end)}\""
        } else {
            null
        }
    }

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
                        "syntax error before: '$word:'"
                    }
            word in KEYWORDS || (word == "not" && isFollowedByIn(text, wordEnd)) -> null
            else -> TextRange(numberStart, wordEnd) to "syntax error before: ${atom(word, languageLevel)}"
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

        return word(text, wordStart) { languageLevel }?.let { (range, message) ->
            TextRange(numberStart, maxOf(range.endOffset, wordEnd(text, wordStart))) to message
        }
    }

    private fun isInFloat(wholeNumber: PsiElement): Boolean =
        wholeNumber.parent.let {
            it is ElixirDecimalFloatIntegral || it is ElixirDecimalFloatFractional || it is ElixirDecimalFloatExponent
        }
}

private val WORDS = TokenSet.create(
    ElixirTypes.IDENTIFIER_TOKEN,
    ElixirTypes.ALIAS_TOKEN,
    ElixirTypes.AFTER,
    ElixirTypes.AND_WORD_OPERATOR,
    ElixirTypes.CATCH,
    ElixirTypes.DO,
    ElixirTypes.ELSE,
    ElixirTypes.END,
    ElixirTypes.FALSE,
    ElixirTypes.FN,
    ElixirTypes.IN_OPERATOR,
    ElixirTypes.NIL,
    ElixirTypes.NOT_OPERATOR,
    ElixirTypes.OR_WORD_OPERATOR,
    ElixirTypes.RESCUE,
    ElixirTypes.TRUE,
    ElixirTypes.WHEN_OPERATOR,
)

/**
 * Words that may directly follow a based number, and before 1.12 any number, as in `1and 2`; `not` only as `not in`.
 */
private val KEYWORDS = setOf("after", "and", "catch", "do", "else", "end", "in", "or", "rescue", "when")

/** Erlang leaves an atom unquoted when it is a lowercase Latin-1 word. */
private val UNQUOTED_ATOM =
    Regex("[a-z\u00DF-\u00F6\u00F8-\u00FF][A-Za-z0-9_@\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u00FF]*")

private fun isElixirSpace(character: Char): Boolean =
    character == ' ' || character == '\t' || character == '\r' || character == '\n'

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

    return offset > end &&
        text.startsWith("in", offset) &&
        text.getOrNull(offset + 2)?.let { isWordCharacter(it.code) || it in "?!:@" } != true
}

/** `erl_scan`'s reserved words, which Erlang prints quoted. */
private val ERLANG_RESERVED_WORDS = setOf(
    "after", "and", "andalso", "band", "begin", "bnot", "bor", "bsl", "bsr", "bxor", "case", "catch", "cond", "div", "end",
    "fun", "if", "let", "not", "of", "or", "orelse", "receive", "rem", "try", "when", "xor"
)

private fun baseEnd(text: CharSequence, start: Int): Int? {
    if (text.getOrNull(start) != '0') return null

    val isDigit: (Char) -> Boolean = when (text.getOrNull(start + 1)) {
        'x' -> { character -> character.isAsciiDigit() || character in 'a'..'f' || character in 'A'..'F' }
        'o' -> { character -> character in '0'..'7' }
        'b' -> { character -> character == '0' || character == '1' }
        else -> return null
    }

    return if (text.getOrNull(start + 2)?.let(isDigit) == true) digitsEnd(text, start + 2, isDigit) else null
}

private fun decimalEnd(text: CharSequence, start: Int): Int {
    var end = digitsEnd(text, start, Char::isAsciiDigit)

    if (text.getOrNull(end) == '.' && text.getOrNull(end + 1)?.isAsciiDigit() == true) {
        end = digitsEnd(text, end + 1, Char::isAsciiDigit)

        if (text.getOrNull(end) == 'e' || text.getOrNull(end) == 'E') {
            val digits = if (text.getOrNull(end + 1) == '+' || text.getOrNull(end + 1) == '-') end + 2 else end + 1

            if (text.getOrNull(digits)?.isAsciiDigit() == true) {
                end = digitsEnd(text, digits, Char::isAsciiDigit)
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

private fun wordStartBefore(text: CharSequence, end: Int): Int {
    var start = end

    if (start > 0 && (text[start - 1] == '?' || text[start - 1] == '!')) start--

    while (start > 0) {
        val codePoint = Character.codePointBefore(text, start)
        if (codePoint != '@'.code && !isWordCharacter(codePoint)) break
        start -= Character.charCount(codePoint)
    }

    // A module attribute's `@` precedes the word rather than belonging to it.
    while (start < end && text[start] == '@') start++

    return start
}

/** The lexer splits `foo@bar` into `foo`, `@` and `bar`, so `bar` is not the start of a word. */
private fun continuesWord(text: CharSequence, start: Int): Boolean {
    var offset = start

    while (offset > 0 && text[offset - 1] == '@') offset--

    return offset > 0 && isWordCharacter(Character.codePointBefore(text, offset))
}

private fun isWordCharacter(codePoint: Int): Boolean =
    codePoint == '_'.code ||
        Character.isLetterOrDigit(codePoint) ||
        Character.getType(codePoint).let {
            it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
        }

/** Erlang's `~4.16.0B` fills a code point that needs more than four digits with stars. */
private fun invalidCharacter(codePoint: Int, kind: String, word: String): String {
    val hexadecimal = if (codePoint > 0xFFFF) "****" else "%04X".format(codePoint)

    return "invalid character \"${String(Character.toChars(codePoint))}\" (code point U+$hexadecimal) in $kind: $word"
}

/**
 * How Elixir's parser prints a word it stopped before: as an Erlang atom. Elixir 1.20 requires Erlang/OTP 27, which
 * reserves `maybe`; before 1.20 that depends on the OTP release, so it is left unquoted.
 */
private fun atom(word: String, languageLevel: ElixirLanguageLevel): String =
    if (
        word.matches(UNQUOTED_ATOM) &&
        word !in ERLANG_RESERVED_WORDS &&
        !(languageLevel >= ElixirLanguageLevel.V1_20 && word == "maybe")
    ) {
        word
    } else {
        "'$word'"
    }

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
