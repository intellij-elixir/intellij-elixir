package org.elixir_lang.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import org.elixir_lang.annotator.Injection
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.RejectedCall
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.reference.CallDefinitionClause
import org.elixir_lang.reference.Callable
import org.elixir_lang.reference.CaptureNameArity
import org.elixir_lang.reference.resolver.CallDefinitionClause as CallDefinitionClauseResolver
import org.elixir_lang.reference.resolver.Callable as CallableResolver
import org.elixir_lang.reference.resolver.CaptureNameArity as CaptureNameArityResolver

class References : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
        if (Injection.of(holder.file) == Injection.UNCOMPILED) return PsiElementVisitor.EMPTY_VISITOR

        return object : ElixirVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is ElixirAlias -> visitAlias(element)
                    is Call -> visitCall(element)
                }
            }

            override fun visitAlias(alias: ElixirAlias) {
                alias.reference?.let { reference -> registerProblem(alias, reference) }
            }

            override fun visitMatchedAtOperation(o: ElixirMatchedAtOperation) = visitAtOperation(o)
            override fun visitUnmatchedAtOperation(o: ElixirUnmatchedAtOperation) = visitAtOperation(o)

            private fun visitAtOperation(atOperation: AtOperation) {
                atOperation.reference?.let { reference -> registerProblem(atOperation, reference) }
            }

            override fun visitUnmatchedQualifiedNoArgumentsCall(qualifiedNoArgumentsCall: ElixirUnmatchedQualifiedNoArgumentsCall) =
                    visitQualifiedNoArgumentsCall(qualifiedNoArgumentsCall)

            override fun visitMatchedQualifiedNoArgumentsCall(qualifiedNoArgumentsCall: ElixirMatchedQualifiedNoArgumentsCall) =
                    visitQualifiedNoArgumentsCall(qualifiedNoArgumentsCall)

            override fun visitMatchedQualifiedNoParenthesesCall(qualifiedNoParenthesesCall: ElixirMatchedQualifiedNoParenthesesCall) {
                visitQualifiedNoParenthesesCall(qualifiedNoParenthesesCall)
            }

            override fun visitUnmatchedQualifiedNoParenthesesCall(qualifiedNoParenthesesCall: ElixirUnmatchedQualifiedNoParenthesesCall) {
                visitQualifiedNoParenthesesCall(qualifiedNoParenthesesCall)
            }

            private fun visitCall(call: Call) {
                call.reference?.let { reference -> registerProblem(call, reference) }
            }

            private fun visitQualifiedNoArgumentsCall(qualifiedNoArgumentsCall: QualifiedNoArgumentsCall<*>) {
                when (qualifiedNoArgumentsCall.qualifier()) {
                    // Can't resolve keys or fields of a module attribute or assign
                    is AtOperation,
                        // Can't resolve key or fields of a capture
                    is ElixirCaptureNumericOperation,
                        // Can't resolve field or key on a parenthetical expression
                    is ElixirParentheticalStab,
                        // Can't resolve field directly off a struct like `%User{}.name`
                    is ElixirStructOperation,
                        // Can't resolve keys or fields of a variable
                    is UnqualifiedNoArgumentsCall<*>,
                        // Can't resolve keys or fields of a call output
                    is UnqualifiedParenthesesCall<*>,
                        // Can't resolve a chain of keys or fields
                    is QualifiedNoArgumentsCall<*>,
                        // Can't resolve a field or key coming out of an access lookup
                    is QualifiedBracketOperation,
                        // Can't resolve keys or fields on the output of a function call
                    is QualifiedParenthesesCall<*> -> Unit
                    else -> visitCall(qualifiedNoArgumentsCall)
                }
            }

            private fun visitQualifiedNoParenthesesCall(qualifiedNoParenthesesCall: QualifiedNoParenthesesCall<*>) {
                when (qualifiedNoParenthesesCall.qualifier()) {
                    // Can't resolve function chained of another call.
                    is QualifiedParenthesesCall<*> -> Unit
                    else -> visitCall(qualifiedNoParenthesesCall)
                }
            }

            private fun registerProblem(element: PsiElement, reference: PsiReference) {
                if (reference is PsiPolyVariantReference) {
                    registerProblem(element, reference)
                } else {
                    val resolved = reference.resolve()

                    if (resolved == null) {
                        holder.registerProblem(element, "Does not resolve to anything", ProblemHighlightType.ERROR)
                    }
                }
            }

            private fun registerProblem(element: PsiElement, reference: PsiPolyVariantReference) {
                val answer = answer(reference)
                val resolveResults = answer?.results() ?: reference.multiResolve(false).toList()

                if (resolveResults.isEmpty()) {
                    holder.registerProblem(element, "Does not resolve to anything", ProblemHighlightType.ERROR)
                } else if (!resolveResults.any { it.isValidResult } && !expectOnlyInvalid(element)) {
                    holder.registerProblem(
                        element,
                        "Only resolves to invalid results${arities(answer)}",
                        ProblemHighlightType.ERROR
                    )
                }
            }

            /** What [reference] resolves to, a call's candidates read from the source this inspection is given. */
            private fun answer(reference: PsiPolyVariantReference): CallableResolver.Answer? =
                when (reference) {
                    is Callable -> CallableResolver.answer(reference.element, false, Feature.REFERENCES_INSPECTION)
                    is CaptureNameArity -> CaptureNameArityResolver.answer(reference, false, Feature.REFERENCES_INSPECTION)
                    is CallDefinitionClause ->
                        CallDefinitionClauseResolver.answer(reference, false, Feature.REFERENCES_INSPECTION)
                    else -> null
                }

            /** The arities [answer]'s name is defined at, which the compiler's hint lists for a call it rejects. */
            private fun arities(answer: CallableResolver.Answer?): String {
                val use = answer?.use ?: return ""
                val remote = UseSymbols.remote(use)
                val entries = RejectedCall.named(answer.projection, Found::candidate, remote, UseSymbols::admits)
                    ?.let(DidYouMean::arities)
                    ?.takeIf { it.isNotEmpty() }
                    ?: return ""

                return "; defined as " + (if (remote) DidYouMean.suggestions(entries) else entries).joinToString(", ")
            }

            private fun expectOnlyInvalid(element: PsiElement): Boolean =
                    when (element) {
                        is QualifiedParenthesesCall<*> -> expectOnlyInvalid(element)
                        else -> false
                    }

            private fun expectOnlyInvalid(qualifiedParenthesesCall: QualifiedParenthesesCall<*>): Boolean =
                    when (qualifiedParenthesesCall.qualifier()) {
                        // Variable
                        is UnqualifiedNoArgumentsCall<*>,
                            // Key or field
                        is QualifiedNoArgumentsCall<*>,
                            // Call
                        is UnqualifiedParenthesesCall<*>,
                            // Call
                        is QualifiedParenthesesCall<*> -> true
                        else -> Unquote.isQualified(qualifiedParenthesesCall)
                    }
        }
    }
}
