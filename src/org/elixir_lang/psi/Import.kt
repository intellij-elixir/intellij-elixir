package org.elixir_lang.psi

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.isAncestor
import com.intellij.usageView.UsageViewNodeTextLocation
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.Function
import org.elixir_lang.Arity
import org.elixir_lang.Name
import org.elixir_lang.NameArityInterval
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.model.psi.FunctionArityKeywordPair
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.IMPORT
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.stabBodyChildExpressions
import org.elixir_lang.psi.impl.hasKeywordKey
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.structure_view.element.CallDefinitionHead

/**
 * An `import` call
 */
object Import {
    /**
     * Whether `call` is an `import Module` or `import Module, opts` call
     */
    @JvmStatic
    fun `is`(call: Call): Boolean = call.isCalling(KERNEL, IMPORT) && call.resolvedFinalArity() in 1..2

    @JvmStatic
    fun treeWalkUp(
        importCall: Call,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean {
        var accumulatedKeepProcessing = true

        // don't descend back into `import` when the entrance is the alis to the `import` like `MyAlias` in
        // `import MyAlias`.
        if (!importCall.isAncestor(resolveState.get(ENTRANCE))) {
            val modulars = modulars(importCall)

            if (modulars.isNotEmpty()) {
                val importCallResolveState = resolveState.putVisitedElement(importCall)
                val filter = importCallFilter(importCall)

                for (modular in modulars) {
                    val childResolveState = importCallResolveState.putVisitedElement(modular)

                    accumulatedKeepProcessing =
                        treeWalkUpImportedModular(modular, filter, childResolveState, keepProcessing)

                    if (!accumulatedKeepProcessing) {
                        break
                    }
                }
            }
        }

        return accumulatedKeepProcessing
    }

    private fun treeWalkUpImportedModular(
        importedModular: PsiElement,
        filter: (NameArityInterval) -> Boolean,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean =
        when (importedModular) {
            is Call -> treeWalkUpImportedModular(importedModular, filter, resolveState, keepProcessing)
            is BeamModule -> treeWalkUpImportedModular(importedModular, filter, resolveState, keepProcessing)
            else -> true
        }

    private fun treeWalkUpImportedModular(
        importedModular: Call,
        filter: (NameArityInterval) -> Boolean,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean =
        importedModular
            .stabBodyChildExpressions()
            ?.filterIsInstance<Call>()
            ?.filter { !resolveState.hasBeenVisited(it) }
            ?.map { treeWalkUpImportedModularChildExpression(filter, it, resolveState, keepProcessing) }
            ?.takeWhile { it }
            ?.lastOrNull()
            ?: true

    private fun treeWalkUpImportedModular(
        importedModular: BeamModule,
        filter: (NameArityInterval) -> Boolean,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean =
        importedModular
            .callDefinitions()
            .map { treeWalkUpImportedModularChildExpression(filter, it, resolveState, keepProcessing) }
            .takeWhile { it }
            .lastOrNull()
            ?: true

    private fun treeWalkUpImportedModularChildExpression(
        filter: (NameArityInterval) -> Boolean,
        importedCall: Call,
        resolveState: ResolveState,
        keepProcessing: (Call, ResolveState) -> Boolean
    ): Boolean {
        ProgressManager.checkCanceled()

        return when (CallableDeclaration.headBindingFormOf(importedCall)) {
            CallableDeclaration.Form.CLAUSE -> {
                CallDefinitionClause.nameArityInterval(importedCall, resolveState)?.let { nameArityInterval ->
                    if (filter(nameArityInterval)) {
                        keepProcessing(importedCall, resolveState)
                    } else {
                        true
                    }
                }
            }
            CallableDeclaration.Form.DELEGATION -> {
                CallableDeclaration.delegationHead(importedCall)?.let { head ->
                    CallDefinitionHead.nameArityInterval(head, resolveState)?.let { headNameArityInterval ->
                        if (filter(headNameArityInterval)) {
                            keepProcessing(importedCall, resolveState)
                        } else {
                            true
                        }
                    }
                }
            }
            else -> null
        }
            ?: true
    }

    private fun treeWalkUpImportedModularChildExpression(
        filter: (NameArityInterval) -> Boolean,
        importedCall: BeamCallDefinition,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean {
        ProgressManager.checkCanceled()

        val nameArityInterval = importedCall.nameArityInterval

        return if (filter(nameArityInterval)) {
            keepProcessing(importedCall, resolveState)
        } else {
            true
        }
    }

    fun elementDescription(call: Call, location: ElementDescriptionLocation): String? =
        when {
            location === UsageViewTypeLocation.INSTANCE -> "import"
            location === UsageViewNodeTextLocation.INSTANCE -> call.text
            else -> null
        }


    private fun aritiesByNameFromNameByArityKeywordList(list: ElixirList): Map<Name, List<Arity>> {
        val aritiesByName = mutableMapOf<Name, MutableList<Int>>()

        val children = list.children

        if (children.isNotEmpty()) {
            (children.last() as? QuotableKeywordList)?.let { quotableKeywordList ->
                for (quotableKeywordPair in quotableKeywordList.quotableKeywordPairList()) {
                    val name = keywordKeyToName(quotableKeywordPair.keywordKey)
                    val arity = keywordValueToArity(quotableKeywordPair.keywordValue)

                    if (name != null && arity != null) {
                        aritiesByName.computeIfAbsent(name) { mutableListOf() }.add(arity)
                    }
                }
            }
        }

        return aritiesByName
    }

    private fun aritiesByNameFromNameByArityKeywordList(element: PsiElement): Map<String, List<Int>> =
        (element.stripAccessExpression() as? ElixirList)?.let {
            aritiesByNameFromNameByArityKeywordList(it)
        } ?: emptyMap()

    /**
     * A function that returns `true` for name arity intervals that are imported by `importCall`
     *
     * @param importCall `import` call
     */
    private fun importCallFilter(importCall: Call): (NameArityInterval) -> Boolean {
        val finalArguments = importCall.finalArguments()

        return if (finalArguments != null && finalArguments.size >= 2) {
            optionsNameArityIntervalFilter(finalArguments[1])
        } else {
            TRUE
        }
    }

    private val TRUE: (NameArityInterval) -> Boolean = { true }

    /**
     * A [Function] that returns `true` for call definition clauses that are imported by `importCall`
     *
     * @param options options (second argument) to an `import Module, ...` call.
     */
    private fun optionsNameArityIntervalFilter(options: PsiElement?): (NameArityInterval) -> Boolean {
        var filter = TRUE

        if (options != null && options is QuotableKeywordList) {
            for (quotableKeywordPair in options.quotableKeywordPairList()) {
                /* although using both `except` and `only` is invalid semantically, support it to handle transient code
                   and take the final option as the filter in that state */
                if (quotableKeywordPair.hasKeywordKey("except")) {
                    filter = exceptNameArityIntervalFilter(quotableKeywordPair.keywordValue)
                } else if (quotableKeywordPair.hasKeywordKey("only")) {
                    filter = onlyNameArityIntervalFilter(quotableKeywordPair.keywordValue)
                }
            }
        }

        return filter
    }

    private fun exceptNameArityIntervalFilter(element: PsiElement): (NameArityInterval) -> Boolean {
        val only = onlyNameArityIntervalFilter(element)
        return { nameArityInterval -> !only(nameArityInterval) }
    }

    private fun keywordKeyToName(keywordKey: Quotable): String? =
        FunctionArityKeywordPair.nameFromKey(keywordKey)

    private fun keywordValueToArity(keywordValue: Quotable): Int? =
        FunctionArityKeywordPair.arityFromValue(keywordValue)

    private fun onlyNameArityIntervalFilter(element: PsiElement): (NameArityInterval) -> Boolean {
        val aritiesByName = aritiesByNameFromNameByArityKeywordList(element)

        return { nameArityInterval ->
            aritiesByName[nameArityInterval.name]?.let { arities ->
                arities.any { arity -> nameArityInterval.arityInterval.contains(arity) }
            } ?: false
        }
    }

    /**
     * The modular that is imported by `importCall`.
     * @param importCall a [Call] where [is] is `true`.
     * @return `defmodule`, `defimpl`, or `defprotocol` imported by `importCall`.  It can be
     * `null` if Alias passed to `importCall` cannot be resolved.
     */
    private fun modulars(importCall: Call): Set<PsiNamedElement> =
        importCall
            .finalArguments()
            ?.firstOrNull()
            ?.maybeModularNameToModulars(maxScope = importCall.parent, useCall = null, incompleteCode = false)
            ?: emptySet()


}
