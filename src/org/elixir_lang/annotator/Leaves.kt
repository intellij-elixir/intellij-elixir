package org.elixir_lang.annotator

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/** The first leaf after [element] that is neither whitespace, a comment nor blank. */
internal fun nextCodeLeaf(element: PsiElement): PsiElement? =
    generateSequence(PsiTreeUtil.nextLeaf(element)) { PsiTreeUtil.nextLeaf(it) }.firstOrNull(::isCode)

/** The last leaf before [element] that is neither whitespace, a comment nor blank. */
internal fun previousCodeLeaf(element: PsiElement): PsiElement? =
    generateSequence(PsiTreeUtil.prevLeaf(element)) { PsiTreeUtil.prevLeaf(it) }.firstOrNull(::isCode)

/** Whether a leaf after [start] and before [end] holds a newline that is not a line continuation. */
internal fun hasNewlineBetween(start: PsiElement, end: PsiElement): Boolean =
    generateSequence(PsiTreeUtil.nextLeaf(start)) { PsiTreeUtil.nextLeaf(it) }
        .takeWhile { it.textRange.startOffset < end.textRange.startOffset }
        .any { '\n' in withoutLineContinuations(it.text) }

private fun isCode(leaf: PsiElement): Boolean = leaf !is PsiWhiteSpace && leaf !is PsiComment && leaf.text.isNotBlank()
