package org.elixir_lang.code_insight.completion

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * What is typed in [element] before [offset], from the start of [name], the range in [element] naming it: nothing
 * where [offset] is before that.
 */
internal fun typedBefore(element: PsiElement, name: TextRange, offset: Int): String =
    element.text.substring(name.startOffset, maxOf(name.startOffset, offset - element.textRange.startOffset))
