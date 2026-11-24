package org.elixir_lang.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiElementVisitor
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.QualifiedModule
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.qualification.Qualified

class UnresolvableModuleQualifier : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
        session: LocalInspectionToolSession
    ): PsiElementVisitor {
        // Only inspect files under Source or Test Source roots.  Files in unmarked
        // directories (e.g. config/, priv/) are not indexed as source and their module
        // resolution scope is narrower -- flagging errors there produces false positives.
        val virtualFile = holder.file.virtualFile
        if (virtualFile == null ||
            !ProjectRootManager.getInstance(holder.project).fileIndex.isInSourceContent(virtualFile)
        ) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        // Skip injected language fragments (e.g. Elixir code blocks inside @doc/@moduledoc
        // heredocs).  Module references in documentation examples are illustrative and
        // not expected to resolve.
        if (InjectedLanguageManager.getInstance(holder.project).isInjectedFragment(holder.file)) {
            return PsiElementVisitor.EMPTY_VISITOR
        }

        val source = sourceFor(Feature.REFERENCES_INSPECTION)

        return object : ElixirVisitor() {
            override fun visitMatchedQualifiedNoArgumentsCall(o: ElixirMatchedQualifiedNoArgumentsCall) =
                checkQualifier(o)

            override fun visitUnmatchedQualifiedNoArgumentsCall(o: ElixirUnmatchedQualifiedNoArgumentsCall) =
                checkQualifier(o)

            override fun visitMatchedQualifiedNoParenthesesCall(o: ElixirMatchedQualifiedNoParenthesesCall) =
                checkQualifier(o)

            override fun visitUnmatchedQualifiedNoParenthesesCall(o: ElixirUnmatchedQualifiedNoParenthesesCall) =
                checkQualifier(o)

            override fun visitMatchedQualifiedParenthesesCall(o: ElixirMatchedQualifiedParenthesesCall) =
                checkQualifier(o)

            override fun visitUnmatchedQualifiedParenthesesCall(o: ElixirUnmatchedQualifiedParenthesesCall) =
                checkQualifier(o)

            private fun checkQualifier(qualified: Qualified) {
                if (Unquote.isQualified(qualified)) {
                    return
                }

                val qualifier = qualified.qualifier() as? QualifiableAlias ?: return

                when (source.qualifiedModule(qualified)) {
                    QualifiedModule.Absent ->
                        holder.registerProblem(
                            qualifier,
                            "Module '${qualifier.text}' is not defined or aliased in this scope",
                            ProblemHighlightType.ERROR
                        )
                    QualifiedModule.Held, QualifiedModule.Unknown -> Unit
                }
            }
        }
    }
}
