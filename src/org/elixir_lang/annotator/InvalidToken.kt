package org.elixir_lang.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.parser.isWordCharacter
import org.elixir_lang.psi.ElixirDecimalFloat
import org.elixir_lang.psi.ElixirDecimalFloatExponent
import org.elixir_lang.psi.ElixirDecimalFloatFractional
import org.elixir_lang.psi.ElixirDecimalFloatIntegral
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.WholeNumber
import org.elixir_lang.psi.letterThatStartsOnlyAnAtom
import org.elixir_lang.psi.rejectedNumber
import org.elixir_lang.psi.rejectedWordAt
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
                // A bad character belongs to no language, so annotators never see it; its parent reports it.
                rejectedFirstLetter(child)?.let(problems::add)
                if (child.nextSibling != null) acrossBoundary(child)?.let(problems::add)
            }
        }

        val inside = problems.filter { (range, _) -> element.textRange.contains(range) }
        if (inside.isEmpty() || Injection.of(element) == Injection.UNCOMPILED) return

        for ((range, message) in inside) {
            holder.error(range, message)
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

    private fun number(number: PsiElement): Pair<TextRange, String>? =
        rejectedNumber(number, ElixirLanguageLevelResolver.languageLevelFor(number))

    private fun isNumber(element: PsiElement): Boolean =
        element is ElixirDecimalFloat || (element is WholeNumber && !isInFloat(element))

    private fun word(leaf: PsiElement): Pair<TextRange, String>? {
        val text = leaf.containingFile.viewProvider.contents
        val start = leaf.textRange.startOffset

        if (continuesWord(text, start)) return null

        return rejectedWordAt(text, start) { ElixirLanguageLevelResolver.languageLevelFor(leaf) }
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

/**
 * A letter the lexer cannot start a word with: a non-ASCII uppercase letter outside an atom or keyword key, or one
 * Elixir restricts. [RejectedLetterErrorFilter] hides the parser's own error for it.
 */
internal fun rejectedFirstLetter(badCharacter: PsiElement): Pair<TextRange, String>? {
    if (badCharacter.node.elementType != TokenType.BAD_CHARACTER) return null
    val text = badCharacter.containingFile.viewProvider.contents
    val start = badCharacter.textRange.startOffset
    if (!startsOnlyAnAtom(Character.codePointAt(text, start)) || continuesWord(text, start)) return null

    return letterThatStartsOnlyAnAtom(text, start) { ElixirLanguageLevelResolver.languageLevelFor(badCharacter) }
}

