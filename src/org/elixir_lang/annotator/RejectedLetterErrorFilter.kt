package org.elixir_lang.annotator

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLanguage

/**
 * Hides the parser's error for a letter no word can start with, which [InvalidToken] reports in Elixir's words. The
 * letter is the error's only child, or, when the parser reports what it expected there, the first leaf after an empty
 * error that is not whitespace.
 */
internal class RejectedLetterErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        if (!element.language.isKindOf(ElixirLanguage)) return true

        val letter = if (element.textLength == 0) {
            generateSequence(PsiTreeUtil.nextLeaf(element)) { PsiTreeUtil.nextLeaf(it) }
                .firstOrNull { it !is PsiWhiteSpace }
        } else {
            element.firstChild?.takeIf { it.nextSibling == null }
        } ?: return true

        return Injection.of(letter) == Injection.UNCOMPILED || rejectedFirstLetter(letter) == null
    }
}
