package org.elixir_lang.code_insight.completion.provider

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.elixir_lang.code_insight.completion.insert_handler.QualifiedName
import org.elixir_lang.code_insight.completion.qualifiedLookupElements
import org.elixir_lang.declaration.CompletionPosition
import org.elixir_lang.psi.operation.capture.NonNumeric
import org.elixir_lang.reference.CaptureNameArity

class CallDefinitionClause : CompletionProvider<CompletionParameters>() {
    /** The copy is read first: its dummy identifier terminates the qualified name. */
    private fun positionAfterDot(parameters: CompletionParameters): CompletionPosition? =
        positionAfterDotAt(parameters.position) ?: positionAfterDotAt(parameters.originalPosition)

    /** Bare, the copy's dummy makes `Mod.` a qualified alias; with a prefix typed it is a call. */
    private fun positionAfterDotAt(position: PsiElement?): CompletionPosition? =
        position?.parent?.parent?.let(CompletionPosition::AfterDot)?.takeIf { it.module != null }

    /** A capture's name, `&Mod.<caret>/arity`, is offered by its [CaptureNameArity] reference, not as a call. */
    private fun isCaptureName(position: PsiElement?): Boolean =
        position?.parent?.parent?.let { qualifiedName ->
            (PsiTreeUtil.getParentOfType(qualifiedName, NonNumeric::class.java)?.reference as? CaptureNameArity)
                ?.nameElement == qualifiedName
        } ?: false

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        resultSet: CompletionResultSet
    ) {
        if (isCaptureName(parameters.originalPosition) || isCaptureName(parameters.position)) return

        positionAfterDot(parameters)?.let { position ->
            val modularsResultSet = if (resultSet.prefixMatcher.prefix.endsWith(".")) {
                resultSet.withPrefixMatcher("")
            } else {
                resultSet
            }

            modularsResultSet.addAllElements(qualifiedLookupElements(position, QualifiedName.CALL))
        }
    }
}
