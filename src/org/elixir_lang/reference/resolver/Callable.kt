package org.elixir_lang.reference.resolver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Arity
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.call.qualification.qualifiedToModulars
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult
import org.elixir_lang.structure_view.element.Delegation

object Callable : ResolveCache.PolyVariantResolver<org.elixir_lang.reference.Callable> {
    override fun resolve(callable: org.elixir_lang.reference.Callable, incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        val element = callable.element
        val resolvedPrimaryArity = element.resolvedPrimaryArity() ?: 0

        return resolve(element, resolvedPrimaryArity, incompleteCode)
    }

    fun resolve(call: Call, resolvedPrimaryArity: Arity, incompleteCode: Boolean): Array<ResolveResult> {
        val all =
            if (!incompleteCode && resolvedPrimaryArity == (call.resolvedPrimaryArity() ?: 0)) {
                candidates(call)
            } else {
                resolveAll(call, resolvedPrimaryArity, incompleteCode)
            }
        val preferred = org.elixir_lang.reference.Resolver.preferred(call, incompleteCode, all)

        return expand(preferred).toTypedArray()
    }

    /**
     * Every declaration [call]'s name reaches, valid or not, each with how it was reached, walked once until the next
     * change: resolution and what asks about a call that resolved to nothing valid share it.
     */
    @RequiresReadLock
    fun candidates(call: Call): List<VisitedElementSetResolveResult> =
        CachedValuesManager.getCachedValue(call) {
            CachedValueProvider.Result.create(
                resolveAll(call, call.resolvedPrimaryArity() ?: 0, false),
                PsiModificationTracker.MODIFICATION_COUNT
            )
        }

    private fun expand(visitedElementSetResolveResultList: List<VisitedElementSetResolveResult>): List<PsiElementResolveResult> =
        visitedElementSetResolveResultList
            .flatMap { visitedElementSetResolveResult ->
                val visitedElementSet = visitedElementSetResolveResult.visitedElementSet
                val validResult = visitedElementSetResolveResult.isValidResult

                val pathResolveResultList =
                    visitedElementSet
                        .filter { visitedElement ->
                            visitedElement.let { it as? Call }?.let { visitedCall ->
                                Delegation.`is`(visitedCall) || Import.`is`(visitedCall) || Use.`is`(visitedCall)
                            } ?: false
                        }
                        .map { PsiElementResolveResult(it, validResult) }

                val terminalResolveResult = PsiElementResolveResult(
                    visitedElementSetResolveResult.element,
                    visitedElementSetResolveResult.isValidResult
                )

                listOf(terminalResolveResult) + pathResolveResultList
            }
            // deduplicate shared `defdelegate`, `import`, or `use`
            .groupBy { resolveResultKey(it) }
            .map { (_, resolveResults) ->
                resolveResults.maxByOrNull { if (it.isValidResult) 1 else 0 } ?: resolveResults.first()
            }
            .let(::deduplicateEquivalentResults)

    private fun deduplicateEquivalentResults(resolveResults: List<PsiElementResolveResult>): List<PsiElementResolveResult> {
        val deduplicated = mutableListOf<PsiElementResolveResult>()

        for (candidate in resolveResults) {
            val existingIndex = deduplicated.indexOfFirst { existing ->
                existing.element.manager.areElementsEquivalent(existing.element, candidate.element)
            }

            if (existingIndex == -1) {
                deduplicated.add(candidate)
            } else if (candidate.isValidResult && !deduplicated[existingIndex].isValidResult) {
                deduplicated[existingIndex] = candidate
            }
        }

        return deduplicated
    }

    // A compiled element's navigationElement is its mirror, and building that decompiles the whole module.
    private fun resolveResultKey(resolveResult: PsiElementResolveResult): Any {
        if (resolveResult.element is PsiCompiledElement) return resolveResult.element

        val element = resolveResult.element.navigationElement
        val filePath = element.containingFile?.virtualFile?.path ?: ""
        val range = element.textRange
        val startOffset = range?.startOffset ?: -1
        val endOffset = range?.endOffset ?: -1
        val elementType = PsiUtilCore.getElementType(element)?.toString() ?: ""

        return "$filePath#$startOffset:$endOffset:$elementType"
    }

    private fun resolveAll(element: Call, resolvedPrimaryArity: Arity, incompleteCode: Boolean) =
        /* DO NOT use `getName()` as it will return the NameIdentifier's text, which for `defmodule` is the Alias,
           not `defmodule` */
        element
            .functionName()
            ?.let { name -> resolve(element, name, resolvedPrimaryArity, incompleteCode) }
            ?: emptyList()

    private fun resolve(element: Call, name: String, resolvedPrimaryArity: Arity, incompleteCode: Boolean) =
        resolveInScope(element, name, resolvedPrimaryArity, incompleteCode)

    private fun resolveInScope(
        element: Call,
        name: String,
        resolvedPrimaryArity: Arity,
        incompleteCode: Boolean
    ): List<VisitedElementSetResolveResult> {
        return RecursionManager.doPreventingRecursion(element, true) {
            try {
                if (element is Qualified) {
                    resolveQualified(element, name, resolvedPrimaryArity, incompleteCode)
                } else {
                    resolveUnqualified(element, name, resolvedPrimaryArity, incompleteCode)
                }
            } catch (stackOverflowError: StackOverflowError) {
                Logger.error(
                    Callable::class.java,
                    "StackOverflowError when annotating Call",
                    element,
                    stackOverflowError
                )

                emptyList()
            }
        } ?: emptyList()
    }

    private fun resolveUnqualified(
        element: Call,
        name: String,
        resolvedPrimaryArity: Arity,
        incompleteCode: Boolean
    ): List<VisitedElementSetResolveResult> {
        val resolveResultList = mutableListOf<VisitedElementSetResolveResult>()

        // UnqualifiedNoArgumentsCall prevents `foo()` from being treated as a variable.
        // resolvedFinalArity prevents `|> foo` from being counted as 0-arity
        if (element is UnqualifiedNoArgumentsCall<*> && resolvedPrimaryArity == 0) {
            val variableResolveList = org.elixir_lang.psi.scope.variable.MultiResolve.resolveResultList(
                name,
                incompleteCode,
                element
            )

            resolveResultList.addAll(variableResolveList)
        }

        val callDefinitionClauseResolveResultList =
            org.elixir_lang.psi.scope.call_definition_clause.MultiResolve.resolveResults(
                name,
                resolvedPrimaryArity,
                incompleteCode,
                element
            )

        resolveResultList.addAll(callDefinitionClauseResolveResultList)

        return resolveResultList
    }

    private fun resolveQualified(
        element: Qualified,
        name: String,
        arity: Arity,
        incompleteCode: Boolean
    ): List<VisitedElementSetResolveResult> {
        val modulars = element.qualifiedToModulars()

        return if (modulars.isNotEmpty()) {
            val resolvableName = name.takeUnless { Unquote.isQualified(element, it) }

            modulars.flatMap { modular ->
                org.elixir_lang.psi.scope.call_definition_clause.MultiResolve.resolveResults(
                    resolvableName,
                    arity,
                    incompleteCode,
                    modular
                )
            }
        } else {
            emptyList()
        }
    }
}
