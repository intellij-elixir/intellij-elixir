package org.elixir_lang.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.annotator.unicode_security.UnicodeSecurityCheck
import org.elixir_lang.psi.Body
import org.elixir_lang.psi.ElixirInterpolation
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.language_level.ElixirLanguageLevelResolver

/**
 * Reports, as errors, the Unicode that the module's Elixir rejects for security reasons.
 */
internal class UnicodeSecurity : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.firstChild != null) return

        val injection = Injection.of(element)
        if (injection == Injection.UNCOMPILED) return

        val text = element.node?.chars ?: return
        val elementType = element.node.elementType

        val problems = when {
            elementType == ElixirTypes.IDENTIFIER_TOKEN || elementType == ElixirTypes.ATOM_FRAGMENT ->
                if (text.all { it.code <= 127 }) {
                    return
                } else {
                    listOfNotNull(UnicodeSecurityCheck.inIdentifier(text, ElixirLanguageLevelResolver.languageLevelFor(element)))
                }

            // A template's characters are also the text of its host sigil, which reports them.
            injection == Injection.TEMPLATE || !UnicodeSecurityCheck.hasBidiOrLineBreak(text) -> return

            element is PsiComment ->
                UnicodeSecurityCheck.inComment(text, ElixirLanguageLevelResolver.languageLevelFor(element))

            PsiTreeUtil.getParentOfType(element, Body::class.java, ElixirInterpolation::class.java) is Body ->
                UnicodeSecurityCheck.inQuoted(text, ElixirLanguageLevelResolver.languageLevelFor(element))

            else -> return
        }

        val offset = element.textRange.startOffset

        for (problem in problems) {
            holder.newAnnotation(HighlightSeverity.ERROR, problem.message)
                .range(problem.range.shiftRight(offset))
                .apply { problem.tooltip?.let { tooltip(it) } }
                .create()
        }
    }
}
