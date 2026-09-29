package org.elixir_lang.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import org.elixir_lang.model.psi.function.RejectedCall
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call

internal class References : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
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
                val resolveResults = reference.multiResolve(false)

                if (resolveResults.isEmpty()) {
                    holder.registerProblem(element, doesNotResolve(element), ProblemHighlightType.ERROR)
                } else if (!resolveResults.any { it.isValidResult } && !expectOnlyInvalid(element)) {
                    holder.registerProblem(element, invalidMessage(element), ProblemHighlightType.ERROR)
                }
            }

            /**
             * A call at an arity nothing declares names the arities there are, as the compiler does; one whose name
             * nothing declares - candidates only start with it, as a misspelling's do - resolves to nothing.
             */
            private fun invalidMessage(element: PsiElement): String {
                val call = element as? Call ?: return INVALID
                val name = call.functionName() ?: return INVALID
                val arities = RejectedCall.named(call).flatMap { it.arities }.distinct().sorted()

                return if (arities.isNotEmpty()) {
                    "$INVALID; defined as ${arities.joinToString(", ") { "$name/$it" }}"
                } else {
                    doesNotResolve(call)
                }
            }

            /** A call names what its module exports near it, as the compiler does. */
            private fun doesNotResolve(element: PsiElement): String {
                val suggestions = (element as? Call)?.let { DidYouMean.suggestions(it) }.orEmpty()

                return if (suggestions.isEmpty()) {
                    "Does not resolve to anything"
                } else {
                    "Does not resolve to anything. Did you mean: ${suggestions.joinToString(", ")}?"
                }
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

private const val INVALID = "Only resolves to invalid results"
