package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.FunctionArityKeywordPair
import org.elixir_lang.model.psi.callback.BehaviourMembership
import org.elixir_lang.model.psi.callback.Callback
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.QuotableKeywordPair
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.macroChildCallSequence
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.stub.index.ModularName

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
 *    through the behaviour(s) in scope (see [BehaviourMembership]); this keeps the `defoverridable`
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
    private fun resolveFunctions(occurrence: FunctionArityKeywordPair.Occurrence): Collection<Symbol> {
        val modulars: Collection<Call> = when (occurrence.host) {
            FunctionArityKeywordPair.Host.COMPILE_INLINE,
            FunctionArityKeywordPair.Host.DIALYZER ->
                listOfNotNull(CallDefinitionClause.enclosingModularMacroCall(occurrence.hostCall))

            FunctionArityKeywordPair.Host.IMPORT_ONLY,
            FunctionArityKeywordPair.Host.IMPORT_EXCEPT ->
                occurrence.hostCall
                    .finalArguments()
                    ?.firstOrNull()
                    ?.maybeModularNameToModulars(maxScope = occurrence.hostCall.parent, useCall = null, incompleteCode = false)
                    ?.filterIsInstance<Call>()
                    ?: emptyList()

            FunctionArityKeywordPair.Host.DEFOVERRIDABLE -> emptyList()
        }

        return modulars.flatMap { modular ->
            modular
                .macroChildCallSequence()
                .flatMap { FunctionSymbol.fromDeclaration(it) }
                .filter { it.name == occurrence.name && it.arity == occurrence.arity }
                .toList()
        }
    }

    @RequiresReadLock
    private fun resolveCallbacks(occurrence: FunctionArityKeywordPair.Occurrence): Collection<Symbol> {
        val behaviourNames = defoverridableBehaviourNames(occurrence.hostCall)
        if (behaviourNames.isEmpty()) return emptyList()

        val scope = GlobalSearchScope.allScope(host.project)
        val callbacks = mutableListOf<Symbol>()
        for (name in behaviourNames) {
            for (behaviourModule in StubIndex.getElements(ModularName.KEY, name, host.project, scope, NamedElement::class.java)) {
                ProgressManager.checkCanceled()
                if (behaviourModule !is Call) continue
                behaviourModule
                    .macroChildCallSequence()
                    .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
                    .filter { CallableDeclaration.isForm(it, CallableDeclaration.Form.CALLBACK) }
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

    /**
     * Behaviour module names in scope for a `defoverridable` [hostCall]: either the module(s) injected
     * by the enclosing `__using__` definer (default-implementation case) plus that definer's own
     * module, or - for a plain `defoverridable` directly in a module - the behaviours that module
     * implements.
     */
    @RequiresReadLock
    private fun defoverridableBehaviourNames(hostCall: Call): Set<String> {
        val usingDefiner = generateSequence(hostCall.parent) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { call ->
                CallableDeclaration.isForm(call, CallableDeclaration.Form.CLAUSE) &&
                    CallDefinitionClause.nameArityInterval(call, ResolveState.initial())?.name == "__using__"
            }

        return if (usingDefiner != null) {
            val definingModule = CallDefinitionClause.enclosingModularMacroCall(usingDefiner)
            val names = linkedSetOf<String>()
            if (definingModule != null) {
                BehaviourMembership.moduleName(definingModule)?.let { names += it }
                names += BehaviourMembership.namesInjectedByDefiner(usingDefiner, definingModule)
            }
            names
        } else {
            CallDefinitionClause.enclosingModularMacroCall(hostCall)
                ?.let { BehaviourMembership.namesImplementedBy(it) }
                ?: emptySet()
        }
    }
}
