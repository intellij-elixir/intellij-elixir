package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.FunctionArityKeywordPair
import org.elixir_lang.model.psi.callback.Callback
import org.elixir_lang.model.psi.callback.CallbackImplementation
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Import
import org.elixir_lang.psi.QuotableKeywordPair
import org.elixir_lang.psi.call.Call
import org.elixir_lang.reference.resolver.Module as ModuleResolver
import org.elixir_lang.structure_view.element.Delegation
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.structure_view.element.Callback as CallbackElement

/**
 * Symbol reference from the **key** of a `name: arity` keyword pair
 * ([org.elixir_lang.model.psi.FunctionArityKeywordPair]) to the function/macro it names - powers "Go
 * To Declaration" (Ctrl+Click) and, since rename = find-usages + replace, in-place rename started from
 * the key.
 *
 * Resolution depends on the governing directive:
 *  - `import :only`/`:except` -> the matching `def`/`defmacro` in the imported module ([FunctionSymbol]).
 *  - `@compile :inline` / `@dialyzer` -> the matching `def`/`defmacro` in the enclosing module.
 *  - `defoverridable` -> the `@callback`/`@macrocallback` it makes overridable ([Callback]), resolved
 *    through the behaviour(s) in scope (see [CallbackImplementation]); this keeps the `defoverridable`
 *    entry renaming in lock-step with the callback, its default `def`, and every override.
 */
@Suppress("UnstableApiUsage")
class FunctionArityKeywordPairReference(
    private val host: Call,
    private val rangeInElement: TextRange,
    private val pair: QuotableKeywordPair
) : PsiSymbolReference {
    override fun getElement(): PsiElement = host

    override fun getRangeInElement(): TextRange = rangeInElement

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> {
        val occurrence = FunctionArityKeywordPair.classify(pair) ?: return emptyList()
        return when (occurrence.host) {
            FunctionArityKeywordPair.Host.DEFOVERRIDABLE -> resolveCallbacks(occurrence)
            else -> resolveFunctions(occurrence)
        }
    }

    @RequiresReadLock
    private fun resolveFunctions(occurrence: FunctionArityKeywordPair.Occurrence): Collection<Symbol> =
        when (val definitions = definitions(occurrence)) {
            is Definitions.Found ->
                definitions.definitions
                    .mapNotNull { definitionClause(it) }
                    .flatMap { FunctionSymbol.fromClause(it) }
                    .filter { it.name == occurrence.name && it.arity == occurrence.arity }

            Definitions.UnresolvedModule,
            Definitions.Callbacks -> emptyList()
        }

    @RequiresReadLock
    private fun resolveCallbacks(occurrence: FunctionArityKeywordPair.Occurrence): Collection<Symbol> {
        val behaviourNames = CallbackImplementation.behaviourNames(occurrence.hostCall)
        if (behaviourNames.isEmpty()) return emptyList()

        val callbacks = mutableListOf<Symbol>()
        for (name in behaviourNames) {
            for (behaviourModule in ModuleResolver.resolvePreferred(host, name, incompleteCode = false, inScope = false).map { it.element }) {
                ProgressManager.checkCanceled()
                if (behaviourModule !is Call) continue
                CallDefinitionClause.modularChildCalls(behaviourModule)
                    .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
                    .filter { CallbackElement.`is`(it) }
                    .forEach { attr ->
                        Callback.fromModuleAttribute(attr).forEach { callback ->
                            if (callback.name == occurrence.name && callback.arity == occurrence.arity) {
                                callbacks += callback
                            }
                        }
                    }
            }
        }
        return callbacks
    }

    /** What the key of an [FunctionArityKeywordPair.Occurrence] can name, before its name and arity. */
    sealed interface Definitions {
        /**
         * What the key can name, possibly nothing: source clauses and delegations, or a `.beam` module's
         * [BeamCallDefinition]s, unmapped.
         */
        data class Found(val definitions: List<PsiElement>) : Definitions

        /** An `import` whose module is not found. */
        data object UnresolvedModule : Definitions

        /** A `defoverridable` key, which names callbacks, not definitions. */
        data object Callbacks : Definitions
    }

    companion object {
        /**
         * The one function that decides what a key can name. At `import`'s `only:` and `except:` that is what the
         * import brings in ([Import.imports]), so a private definition names nothing, in source and in a `.beam`
         * alike; at `@compile inline:` and `@dialyzer` it is every definition of the enclosing module, private ones
         * included.
         */
        @RequiresReadLock
        fun definitions(occurrence: FunctionArityKeywordPair.Occurrence): Definitions {
            ThreadingAssertions.assertReadAccess()

            return when (occurrence.host) {
                FunctionArityKeywordPair.Host.COMPILE_INLINE,
                FunctionArityKeywordPair.Host.DIALYZER ->
                    Definitions.Found(
                        CallDefinitionClause.enclosingModularMacroCall(occurrence.hostCall)
                            ?.let { CallDefinitionClause.modularChildCalls(it) }
                            .orEmpty()
                            .filter { CallDefinitionClause.`is`(it) || Delegation.`is`(it) }
                    )

                FunctionArityKeywordPair.Host.IMPORT_ONLY,
                FunctionArityKeywordPair.Host.IMPORT_EXCEPT ->
                    Import.modulars(occurrence.hostCall)
                        .takeIf { it.isNotEmpty() }
                        ?.let { modulars -> Definitions.Found(modulars.flatMap { Import.importedDefinitions(it) }) }
                        ?: Definitions.UnresolvedModule

                FunctionArityKeywordPair.Host.DEFOVERRIDABLE -> Definitions.Callbacks
            }
        }
    }
}
