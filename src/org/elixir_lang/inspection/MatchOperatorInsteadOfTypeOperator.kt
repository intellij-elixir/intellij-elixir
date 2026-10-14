package org.elixir_lang.inspection

import com.intellij.codeInspection.*
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.elixir_lang.local_quick_fix.ConvertMatchToTypeOperation
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.ModuleAttribute.isTypeName
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.impl.operatorTokenNode
import org.elixir_lang.psi.operation.Infix
import org.elixir_lang.psi.operation.Match
import org.jetbrains.annotations.Nls

internal class MatchOperatorInsteadOfTypeOperator : LocalInspectionTool() {
    override fun checkFile(file: PsiFile,
                           manager: InspectionManager,
                           isOnTheFly: Boolean): Array<ProblemDescriptor> {
        val problemsHolder = ProblemsHolder(manager, file, isOnTheFly)

        file.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        // See org.elixir_lang.annotator.ModuleAttribute.annotate for path of checks
                        if (element is AtUnqualifiedNoParenthesesCall<*>) {
                            visitAtUnqualifiedNoParenthesesCall(element)
                        }

                        super.visitElement(element)
                    }

                    private fun visitAtUnqualifiedNoParenthesesCall(
                            atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>
                    ) {
                        val atIdentifier = atUnqualifiedNoParenthesesCall.atIdentifier
                        val identifier = atIdentifier.identifierName()

                        if (isTypeName(identifier)) {
                            val argument = atUnqualifiedNoParenthesesCall.argument

                            if (argument is Match) {
                                val infix = argument as Infix
                                val operator = infix.operator()
                                val elementStartOffset = operator.textOffset

                                val astNode = operator.operatorTokenNode()
                                val nodeStartOffset = astNode.startOffset
                                val nodeTextLength = astNode.textLength
                                val relativeStart = nodeStartOffset - elementStartOffset
                                val relativeTextRange = TextRange(
                                        relativeStart,
                                        relativeStart + nodeTextLength
                                )

                                val localQuickFix = ConvertMatchToTypeOperation()

                                problemsHolder.registerProblem(
                                        operator,
                                        "Type specifications separate the name from the definition using `::`, not `=`",
                                        ProblemHighlightType.ERROR,
                                        relativeTextRange,
                                        localQuickFix
                                )
                            }
                        }

                    }
                }
        )

        return problemsHolder.resultsArray
    }

    @Nls
    override fun getDisplayName(): String = "Match operator (=) used in type spec instead of type operator (::)"

    @Nls
    override fun getGroupDisplayName(): String = "Elixir"

    override fun getShortName(): String = "MatchOperatorInsteadOfTypeOperator"
    override fun isEnabledByDefault(): Boolean = true
}
