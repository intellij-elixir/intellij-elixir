package org.elixir_lang.psi.scope

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.ResolveState
import com.intellij.psi.scope.PsiScopeProcessor
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.Named
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.hasDoBlockOrKeyword
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.keywordArgument
import org.elixir_lang.psi.impl.call.maybeModularNameToModulars
import org.elixir_lang.psi.impl.call.whileInStabBodyChildExpressions
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.impl.whileInChildExpressions
import org.elixir_lang.psi.scope.WhileIn.whileIn
import org.elixir_lang.psi.stub.type.call.Stub.isModular

abstract class Module : PsiScopeProcessor {
    override fun <T> getHint(hintKey: Key<T>): T? = null
    override fun handleEvent(event: PsiScopeProcessor.Event, associated: Any?) {}

    override fun execute(match: PsiElement, state: ResolveState): Boolean =
            if (match is Named) {
                execute(match, state)
            } else {
                true
            }

    /**
     * Decides whether `match` matches the criteria being searched for.  All other [.execute] methods
     * eventually end here or [.executeOnModularName]
     *
     * @return `true` to keep processing; `false` to stop processing.
     */
    protected abstract fun executeOnAliasedName(match: PsiNamedElement,
                                                aliasedName: String,
                                                state: ResolveState): Boolean

    /**
     * Decides whether `match` matches the criteria being searched for.  All other [.execute] methods
     * eventually end here or [.executeOnAliasedName]
     *
     * @return `true` to keep processing; `false` to stop processing.
     */
    protected abstract fun executeOnModularName(modular: Named, modularName: String, state: ResolveState): Boolean

    // `hasDoBlockOrKeyword` is a superset of `Case.isChild` for this purpose: whether to descend looking for
    // a nested `defmodule`, not whether `match` is specifically an ExUnit `describe`/`test` block. A nested
    // `defmodule` resolves by relative name from inside any macro block in real Elixir, and the syntactic
    // check removes a reference resolution - of `match` itself - that this module-name search performed
    // while walking *into* a nested module, which is one arm of #4055's "must the walk continue through
    // another tree" question (not itself a full fix of that class - see Issue3405Test).
    protected fun execute(match: Named, state: ResolveState): Boolean =
            when {
                isModular(match) -> executeOnModular(match, state)
                Use.`is`(match) -> Use.treeWalkUp(match, state, ::execute)
                Alias.`is`(match) -> executeOnAliasCall(match, state)
                Require.`is`(match) -> executeOnRequireCall(match, state)
                hasDoBlockOrKeyword(match) -> executeOnNestedModulars(match, state)
                else -> true
            }

    private fun aliasedName(element: ElixirAlias): String? = element.name

    protected fun aliasedName(element: PsiElement): String? =
            when (element) {
                is ElixirAlias -> aliasedName(element)
                is QualifiedAlias -> aliasedName(element)
                else -> null
            }

    private fun aliasedName(element: QualifiedAlias): String? {
        val children = element.children
        val operatorIndex = org.elixir_lang.psi.operation.Normalized.operatorIndex(children)
        val unqualified = org.elixir_lang.psi.operation.infix.Normalized.rightOperand(children, operatorIndex)

        return (unqualified as? PsiNamedElement)?.name
    }

    private fun executeOnAliasCall(aliasCall: Named, state: ResolveState): Boolean {
        val aliasCallState = state.putVisitedElement(aliasCall)
        val asKeywordValue = aliasCall.keywordArgument("as")

        return if (asKeywordValue != null) {
            asKeywordValue
                    .stripAccessExpression()
                    .let { executeOnAs(it, aliasCallState.put(ALIAS_CALL, aliasCall)) }
        } else {
            val finalArguments = aliasCall.finalArguments()

            if (finalArguments != null && finalArguments.isNotEmpty()) {
                executeOnAliasCallArgument(finalArguments[0], aliasCallState)
            } else {
                true
            }
        }
    }

    private fun executeOnAliasCallArgument(accessExpression: ElixirAccessExpression,
                                           state: ResolveState): Boolean =
            executeOnAliasCallArgument(accessExpression.children, state)

    private fun executeOnAliasCallArgument(multipleAliases: ElixirMultipleAliases,
                                           state: ResolveState): Boolean =
            executeOnMultipleAliasChildren(multipleAliases.children, state)


    protected fun executeOnAliasCallArgument(element: PsiElement?, state: ResolveState): Boolean =
            when (element) {
                // __MODULE__
                is Call -> executeOnAliasCallArgument(element, state)
                is ElixirAccessExpression -> executeOnAliasCallArgument(element, state)
                is QualifiableAlias -> executeOnAliasCallArgument(element, state)
                is QualifiedMultipleAliases -> executeOnAliasCallArgument(element, state)
                else -> true
            }

    private fun executeOnAliasCallArgument(call: Call, state: ResolveState): Boolean =
        if (call is PsiNamedElement) {
            val modulars = call.maybeModularNameToModulars()

            whileIn(modulars) { modular ->
                modular
                        .let{ it as? PsiNameIdentifierOwner }
                        ?.nameIdentifier
                        ?.let { it as QualifiableAlias }
                        ?.let { aliasedName(it) }
                        ?.let { aliasedName -> executeOnAliasedName(call as PsiNamedElement, aliasedName, state) }
                        ?: true
            }
        } else {
            true
        }

    private fun executeOnAliasCallArgument(children: Array<PsiElement>, state: ResolveState): Boolean {
        var keepProcessing = true

        for (child in children) {
            keepProcessing = executeOnAliasCallArgument(child, state)

            if (!keepProcessing) {
                break
            }
        }

        return keepProcessing
    }

    private fun executeOnMultipleAliasChild(child: ElixirAccessExpression, state: ResolveState): Boolean =
        child.children.let { executeOnMultipleAliasChildren(it, state) }

    private fun executeOnMultipleAliasChild(child: PsiElement, state: ResolveState): Boolean =
            when (child) {
                is ElixirAccessExpression -> executeOnMultipleAliasChild(child, state)
                is QualifiableAlias -> executeOnMultipleAliasChild(child, state)
                else -> true
            }

    private fun executeOnMultipleAliasChild(element: QualifiableAlias, state: ResolveState): Boolean =
            executeOnMaybeAliasedName(element, aliasedName(element), state)

    private fun executeOnMultipleAliasChildren(elements: Array<PsiElement>, state: ResolveState): Boolean {
        var keepProcessing = true

        for (element in elements) {
            keepProcessing = executeOnMultipleAliasChild(element, state)

            if (!keepProcessing) {
                break
            }
        }

        return keepProcessing
    }

    private fun executeOnAliasCallArgument(qualifiableAlias: QualifiableAlias,
                                           state: ResolveState): Boolean =
            executeOnMaybeAliasedName(qualifiableAlias, aliasedName(qualifiableAlias), state)

    private fun executeOnAliasCallArgument(qualifiedMultipleAliases: QualifiedMultipleAliases,
                                           state: ResolveState): Boolean {
        val children = qualifiedMultipleAliases.children
        val operatorIndex = org.elixir_lang.psi.operation.Normalized.operatorIndex(children)
        val qualifier = org.elixir_lang.psi.operation.infix.Normalized.leftOperand(children, operatorIndex)
                ?.stripAccessExpression()?.let { it as? PsiNamedElement }
        val unqualified = org.elixir_lang.psi.operation.infix.Normalized.rightOperand(children, operatorIndex)

        return if (unqualified is ElixirMultipleAliases) {
            val unqualifiedState = if (qualifier != null) {
                state.put(MULTIPLE_ALIASES_QUALIFIER, qualifier)
            } else {
                state
            }

            executeOnAliasCallArgument(unqualified, unqualifiedState)
        } else {
            true
        }
    }

    private fun executeOnRequireCall(require: Call, state: ResolveState): Boolean =
        require.keywordArgument("as")?.stripAccessExpression()?.let {
            executeOnAs(it, state.putVisitedElement(require).put(REQUIRE_CALL, require))
        } ?: true

    private fun executeOnAs(asKeywordValue: PsiElement, state: ResolveState): Boolean =
            when (asKeywordValue) {
                is PsiNamedElement -> executeOnMaybeAliasedName(
                        asKeywordValue,
                        asKeywordValue.name,
                        state
                )
                else -> true
            }

    private fun executeOnMaybeAliasedName(named: PsiNamedElement,
                                          aliasedName: String?,
                                          state: ResolveState): Boolean =
            if (aliasedName != null) {
                executeOnAliasedName(named, aliasedName, state)
            } else {
                true
            }


    private fun executeOnModular(match: Named, state: ResolveState): Boolean =
            // No entrance means no file to have been injected into, so `match` cannot be its host.
            if (state.get(ENTRANCE)?.containingFile?.context == match) {
                executeOnViewModular(match, state)
            } else {
                val keepProcessing = match.name?.let {
                    executeOnModularName(match, it, state)
                } ?: true

                // descend in modular to check for nested modulars in case their relative name is being used
                keepProcessing && executeOnNestedModulars(match, state)
            }

    private fun executeOnNestedModulars(match: Named, state: ResolveState): Boolean =
            match.whileInStabBodyChildExpressions { childExpression ->
                if (childExpression is Call && isModular(childExpression)) {
                    execute(childExpression, state)
                } else {
                    true
                }
            }

    private fun executeOnViewModular(match: Named, state: ResolveState): Boolean =
        match
                .doBlock
                ?.stab
                ?.stabBody
                ?.let { executeOnViewModularExpression(it, state) }
                ?: true

    private fun executeOnViewModularExpression(expression: PsiElement, state: ResolveState): Boolean =
            when (expression) {
                is ElixirStabBody -> expression
                        .whileInChildExpressions(forward = false) { child ->
                            executeOnViewModularExpression(child, state)
                        }
                else -> execute(expression, state)
            }

    companion object {
        @JvmStatic
        val ALIAS_CALL = Key<Call>("ALIAS_CALL")
        val MULTIPLE_ALIASES_QUALIFIER = Key<PsiNamedElement>("MULTIPLE_ALIASES_QUALIFIER")
        val REQUIRE_CALL = Key<Call>("REQUIRE_CALL")
    }
}
