package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.FunctionArityKeywordPair
import org.elixir_lang.model.psi.callback.Callback
import org.elixir_lang.model.psi.callback.CallbackImplementation
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.Import
import org.elixir_lang.psi.QuotableKeywordPair
import org.elixir_lang.psi.Unquote
import org.elixir_lang.psi.Use
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition

/**
 * Symbol reference from the **key** of a `name: arity` keyword pair
 * ([org.elixir_lang.model.psi.FunctionArityKeywordPair]) to the function/macro it names - powers "Go
 * To Declaration" (Ctrl+Click) and, since rename = find-usages + replace, in-place rename started from
 * the key.
 *
 * Resolution depends on the governing directive:
 *  - `import :only`/`:except` -> the matching `def`/`defmacro` in the imported module ([FunctionSymbol]).
 *  - `@compile :inline` / `@dialyzer` -> the matching `def`/`defmacro` in the enclosing module.
 *  - `defoverridable` -> the `@callback`/`@macrocallback` the named definition implements ([Callback]), see
 *    [CallbackImplementation]; this keeps the `defoverridable`
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
        named(occurrence).flatMap { UseSymbols.symbols(it, occurrence.name, occurrence.arity) }

    @RequiresReadLock
    private fun resolveCallbacks(occurrence: FunctionArityKeywordPair.Occurrence): Collection<Symbol> =
        named(occurrence)
            .asSequence()
            .filterIsInstance<Call>()
            .flatMap { CallbackImplementation.implementedCallbacks(it, within = occurrence.hostCall) }
            .filter { it.arity == occurrence.arity }
            .distinct()
            .toList()

    /** What the key of an [FunctionArityKeywordPair.Occurrence] can name, before its name and arity. */
    sealed interface Definitions {
        /**
         * What the key can name, possibly nothing: source clauses and delegations, or a `.beam` module's
         * [BeamCallDefinition]s, unmapped.
         */
        data class Found(val definitions: List<PsiElement>) : Definitions

        /** An `import` whose module is not found. */
        data object UnresolvedModule : Definitions
    }

    companion object {
        /**
         * The one function that decides what a key can name. At `import`'s `only:` and `except:` that is what the
         * import brings in ([Import.imports]), so a private definition names nothing, in source and in a `.beam`
         * alike; at `@compile inline:`, `@dialyzer` and `defoverridable` it is every definition of the enclosing module,
         * private ones included, and those a `use` or an `unquote` in it puts into it.
         */
        @RequiresReadLock
        fun definitions(occurrence: FunctionArityKeywordPair.Occurrence): Definitions {
            ThreadingAssertions.assertReadAccess()

            return when (occurrence.host) {
                FunctionArityKeywordPair.Host.COMPILE_INLINE,
                FunctionArityKeywordPair.Host.DEFOVERRIDABLE,
                FunctionArityKeywordPair.Host.DIALYZER ->
                    Definitions.Found(
                        CallDefinitionClause.enclosingModularMacroCall(occurrence.hostCall)
                            ?.let { CallDefinitionClause.modularChildCalls(it) + injectedChildCalls(it, occurrence.hostCall) }
                            .orEmpty()
                            .filter { DefinitionDeclaration.`is`(it) }
                    )

                FunctionArityKeywordPair.Host.IMPORT_ONLY,
                FunctionArityKeywordPair.Host.IMPORT_EXCEPT ->
                    Import.modulars(occurrence.hostCall)
                        .takeIf { it.isNotEmpty() }
                        ?.let { modulars -> Definitions.Found(modulars.flatMap { Import.importedDefinitions(it) }) }
                        ?: Definitions.UnresolvedModule
            }
        }

        /** What [occurrence]'s key names: each of [definitions] declared with its name at an arity covering its own. */
        @RequiresReadLock
        fun named(occurrence: FunctionArityKeywordPair.Occurrence): List<PsiElement> =
            (definitions(occurrence) as? Definitions.Found)
                ?.definitions
                .orEmpty()
                .filter { UseSymbols.declarations(it, occurrence.name, occurrence.arity).isNotEmpty() }

        /**
         * The calls the `use`s and `unquote`s written in [module] put into it, as a call to one of their functions sees
         * them.
         */
        @RequiresReadLock
        private fun injectedChildCalls(module: Call, entrance: Call): List<Call> {
            val injected = mutableListOf<Call>()
            val state = ResolveState.initial().put(ENTRANCE, entrance)
            val keepProcessing = { element: PsiElement, walkState: ResolveState ->
                if (element is Call && !Import.reachedThroughImport(walkState)) injected += element
                true
            }

            CallDefinitionClause.modularChildCalls(module).forEach { child ->
                when {
                    Use.`is`(child) -> Use.treeWalkUp(child, state, keepProcessing)
                    Unquote.`is`(child) -> Unquote.treeWalkUp(child, state, keepProcessing)
                }
            }

            return injected
        }
    }
}
