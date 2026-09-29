package org.elixir_lang.psi.scope

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.scope.PsiScopeProcessor
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirMatchedUnqualifiedNoArgumentsCall
import org.elixir_lang.psi.ElixirUnmatchedAtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.ModuleAttribute.isNonReferencing
import org.elixir_lang.psi.Use
import org.elixir_lang.psi.call.Call

abstract class ModuleAttribute : PsiScopeProcessor {
    override fun execute(element: PsiElement, state: ResolveState): Boolean =
            when (element) {
                is ElixirMatchedUnqualifiedNoArgumentsCall -> true
                is ElixirUnmatchedAtUnqualifiedNoParenthesesCall -> execute(element, state)
                is Call -> execute(element, state)
                else -> true
            }

    private fun execute(maybeDeclaration: ElixirUnmatchedAtUnqualifiedNoParenthesesCall, state: ResolveState): Boolean =
        isNonReferencing(maybeDeclaration.atIdentifier) || executeOnDeclaration(maybeDeclaration, state)

    private fun execute(call: Call, state: ResolveState): Boolean =
            if (Use.`is`(call)) {
                Use.treeWalkUp(call, state, ::execute)
            } else {
                // An attribute set under an `if` or the like is set on the module around it.
                CallDefinitionClause.moduleScopeCalls(call)
                    ?.let { moduleScopeCalls -> WhileIn.whileIn(moduleScopeCalls) { executeListed(it, state) } }
                    ?: true
            }

    /** A call [CallDefinitionClause.moduleScopeCalls] listed: what it holds is listed too, so it is not looked into. */
    private fun executeListed(call: Call, state: ResolveState): Boolean =
        when {
            call is ElixirUnmatchedAtUnqualifiedNoParenthesesCall -> execute(call, state)
            Use.`is`(call) -> Use.treeWalkUp(call, state, ::execute)
            else -> true
        }

    /**
     * Decides whether `declaration` matches the criteria being searched for.
     *
     * @return `true` to keep processing; `false` to stop processing.
     */
    protected abstract fun executeOnDeclaration(declaration: AtUnqualifiedNoParenthesesCall<*>,
                                                state: ResolveState): Boolean
}
