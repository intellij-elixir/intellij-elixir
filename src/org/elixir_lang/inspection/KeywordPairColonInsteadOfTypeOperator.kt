package org.elixir_lang.inspection

import com.intellij.codeInspection.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.elixir_lang.local_quick_fix.ConvertKeywordPairToTypeOperation
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.ModuleAttribute.isTypeName
import org.elixir_lang.psi.QuotableKeywordList
import org.elixir_lang.psi.impl.identifierName
import org.jetbrains.annotations.Nls

internal class KeywordPairColonInsteadOfTypeOperator : LocalInspectionTool() {
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

                            if (argument is QuotableKeywordList) {
                                val quotableKeywordPairList = argument.quotableKeywordPairList()

                                if (quotableKeywordPairList.size == 1) {
                                    val quotableKeywordPair = quotableKeywordPairList[0]
                                    val keywordKey = quotableKeywordPair.keywordKey
                                    val keywordPairColon = keywordKey.nextSibling

                                    val localQuickFix = ConvertKeywordPairToTypeOperation(
                                            /* Can't be KEYWORD_PAIR_COLON because caret is never on the single
                                               character in editor mode, only to left or right */
                                            keywordPairColon
                                    )

                                    problemsHolder.registerProblem(
                                            keywordPairColon,
                                            "Type specifications separate the name from the definition using `::`, not `:`",
                                            ProblemHighlightType.ERROR,
                                            localQuickFix
                                    )
                                }
                            }
                        }

                    }
                }
        )

        return problemsHolder.resultsArray
    }

    @Nls
    override fun getDisplayName(): String = "Keyword pair colon (:) used in type spec instead of type operator (::)"

    @Nls
    override fun getGroupDisplayName(): String = "Elixir"
    override fun getShortName(): String = "KeywordPairColonInsteadOfTypeOperator"
    override fun isEnabledByDefault(): Boolean = true
}
