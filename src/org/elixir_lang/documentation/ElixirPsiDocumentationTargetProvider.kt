package org.elixir_lang.documentation

import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.psi.PsiElement

internal class ElixirPsiDocumentationTargetProvider : PsiDocumentationTargetProvider {
    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? =
        ElixirDocumentationTarget.of(element, originalElement)
}
