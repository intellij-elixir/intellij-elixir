package org.elixir_lang.psi

import com.intellij.openapi.util.Key
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
import org.elixir_lang.psi.impl.literalName
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.impl.stripAccessExpression

/**
 * An `import` call
 */
object Import {
    /**
     * What an `import`'s options bring in, read once: the `name: arity` pairs `only:` lists, or its `:functions`,
     * `:macros` or `:sigils`; the pairs `except:` leaves out; and no name starting with `_` unless `only:` names it.
     * A kind and `except:` combine, as in Elixir; an `only:` list, which Elixir rejects alongside `except:`, wins.
     */
    class Filter private constructor(
        private val only: Map<Name, Set<Arity>>?,
        private val selector: Selector?,
        private val except: Map<Name, Set<Arity>>,
    ) {
        private enum class Selector { FUNCTIONS, MACROS, SIGILS }

        /** Whether [name] at [arity] is brought in; a [compileTime] that is not known rules out no selector. */
        fun admits(name: Name, arity: Arity, compileTime: Boolean?): Boolean =
            only?.let { arity in it[name].orEmpty() } ?: (admitsName(name, compileTime) && arity !in except[name].orEmpty())

        /** Whether [name] is brought in at any arity in [arityInterval]. */
        fun admits(name: Name, arityInterval: ArityInterval, compileTime: Boolean?): Boolean =
            when (val maximum = arityInterval.maximum) {
                // Pairs name finitely many arities, so only `only:`'s can rule out every one of an unbounded interval.
                null -> only?.let { pairs -> pairs[name].orEmpty().any { it >= arityInterval.minimum } }
                    ?: admitsName(name, compileTime)
                else -> (arityInterval.minimum..maximum).any { admits(name, it, compileTime) }
            }

        private fun admitsName(name: Name, compileTime: Boolean?): Boolean =
            !name.startsWith("_") &&
                when (selector) {
                    null -> true
                    Selector.FUNCTIONS -> compileTime != true
                    Selector.MACROS -> compileTime != false
                    Selector.SIGILS -> name.startsWith("sigil_")
                }

        companion object {
            private val EVERYTHING = Filter(null, null, emptyMap())

            /** The filter [importCall]'s options make. */
            fun of(importCall: Call): Filter =
                (importCall.finalArguments()?.getOrNull(1) as? QuotableKeywordList)
                    ?.quotableKeywordPairList()
                    ?.fold(EVERYTHING) { filter, pair ->
                        when {
                            pair.hasKeywordKey("except") ->
                                Filter(filter.only, filter.selector, aritiesByName(pair.keywordValue))
                            pair.hasKeywordKey("only") -> selectorOf(pair.keywordValue)
                                ?.let { Filter(null, it, filter.except) }
                                ?: Filter(aritiesByName(pair.keywordValue), null, emptyMap())
                            else -> filter
                        }
                    }
                    ?: EVERYTHING

            private fun selectorOf(value: PsiElement): Selector? =
                when ((value.stripAccessExpression() as? ElixirAtom)?.literalName()) {
                    "functions" -> Selector.FUNCTIONS
                    "macros" -> Selector.MACROS
                    "sigils" -> Selector.SIGILS
                    else -> null
                }

            /** The `name: arity` pairs of a keyword list, skipping what is not one, which the compiler rejects. */
            private fun aritiesByName(value: PsiElement): Map<Name, Set<Arity>> =
                (value.stripAccessExpression() as? ElixirList)
                    ?.children
                    ?.lastOrNull()
                    ?.let { it as? QuotableKeywordList }
                    ?.quotableKeywordPairList()
                    ?.mapNotNull { pair ->
                        FunctionArityKeywordPair.nameFromKey(pair.keywordKey)?.let { name ->
                            FunctionArityKeywordPair.arityFromValue(pair.keywordValue)?.let { arity -> name to arity }
                        }
                    }
                    ?.groupBy({ it.first }, { it.second })
                    ?.mapValues { (_, arities) -> arities.toSet() }
                    .orEmpty()
        }
    }

    /** The [Filter] of the `import` a declaration was reached through, applied to each name and arity it declares. */
    private val FILTER: Key<Filter> = Key.create("Import.FILTER")

    /** Whether the `import` [state] was reached through, if any, brings in [name] at some arity in [arityInterval]. */
    fun admits(state: ResolveState, name: Name, arityInterval: ArityInterval, compileTime: Boolean?): Boolean =
        state.get(FILTER)?.admits(name, arityInterval, compileTime) ?: true

    /** Whether [importCall] brings in [name] at [arity]. */
    fun admits(importCall: Call, name: Name, arity: Arity, compileTime: Boolean?): Boolean =
        Filter.of(importCall).admits(name, arity, compileTime)

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
                val filter = Filter.of(importCall)
                val importCallResolveState = resolveState.putVisitedElement(importCall).put(FILTER, filter)

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
        filter: Filter,
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
        filter: Filter,
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
        filter: Filter,
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
        filter: Filter,
        importedCall: Call,
        resolveState: ResolveState,
        keepProcessing: (Call, ResolveState) -> Boolean
    ): Boolean {
        val form = CallableDeclaration.formOf(importedCall, resolveState) ?: return true
        val declared = CallableDeclaration.Declared.Source(importedCall, form)

        val capabilities = declared.capabilities

        // `import` brings in only what another module may call.
        return if (capabilities?.public == true &&
            declared.definitions(resolveState).any {
                filter.admits(it.name, it.nameArityInterval().arityInterval, capabilities.compileTime)
            }) {
            keepProcessing(
                importedCall,
                resolveState.put(CallableDeclaration.CLASSIFIED, CallableDeclaration.Classified(importedCall, form))
            )
        } else {
            true
        }
    }

    private fun treeWalkUpImportedModularChildExpression(
        filter: Filter,
        importedCall: BeamCallDefinition,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean
    ): Boolean {
        val capabilities = CallableDeclaration.capabilitiesOf(importedCall, resolveState)
        val nameArityInterval = importedCall.nameArityInterval

        return if (capabilities?.public == true &&
            filter.admits(nameArityInterval.name, nameArityInterval.arityInterval, capabilities.compileTime)) {
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
