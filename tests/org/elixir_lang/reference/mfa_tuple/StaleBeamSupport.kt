package org.elixir_lang.reference.mfa_tuple

import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret

/**
 * The files Go to Declaration at the caret offers, in order: `mod.ex` for the project's source, `Elixir.Mod.beam` for a
 * compiled copy of the module left over from an older build.
 */
internal fun CodeInsightTestFixture.gotoDeclarationFilesAtCaret(): List<String> =
    gotoDeclarationTargetsAtCaret().orEmpty().map { it.destination.fileName() }

internal fun PsiElement?.fileName(): String =
    this?.containingFile?.let { it.originalFile.virtualFile?.name ?: it.name } ?: "<no file>"
