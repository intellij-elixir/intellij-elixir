package org.elixir_lang.model.psi.callback

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.reference.resolver.Module as ModuleResolver
import org.elixir_lang.structure_view.element.Callback as CallbackElement

/**
 * Symbol reference from an implementing `def`/`defmacro` clause's name to the `@callback`(s) it
 * implements - powers "Go To Declaration" from an implementation to its behaviour callback.
 *
 * Resolution (inverse of the forward implementation search): the enclosing module implements
 * behaviour(s) B (via [BehaviourMembership]); resolve each B to its `defmodule` and match its
 * `@callback`/`@macrocallback` declarations by name/arity/kind.
 */
@Suppress("UnstableApiUsage")
class CallbackImplReference(
    private val call: Call,
    private val rangeInElement: TextRange
) : PsiSymbolReference {
    override fun getElement(): PsiElement = call

    override fun getRangeInElement(): TextRange = rangeInElement

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> {
        if (!CallDefinitionClause.`is`(call)) return emptyList()
        val nameArity = CallDefinitionClause.nameArityInterval(call, ResolveState.initial()) ?: return emptyList()
        val macro = CallDefinitionClause.capabilities(call)?.quotesArguments == true
        val module = CallDefinitionClause.enclosingModularMacroCall(call) ?: return emptyList()

        val behaviourNames = BehaviourMembership.namesImplementedBy(module)
        if (behaviourNames.isEmpty()) return emptyList()

        val callbacks = mutableListOf<Symbol>()
        for (name in behaviourNames) {
            for (behaviourModule in ModuleResolver.resolvePreferred(call, name, incompleteCode = false, inScope = false).map { it.element }) {
                ProgressManager.checkCanceled()
                if (behaviourModule !is Call) continue
                CallDefinitionClause.modularChildCalls(behaviourModule)
                    .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
                    .filter { CallbackElement.`is`(it) }
                    .forEach { attr ->
                        Callback.fromModuleAttribute(attr).forEach { callback ->
                            if (callback.name == nameArity.name &&
                                callback.arity in nameArity.arityInterval &&
                                callback.macro == macro
                            ) {
                                callbacks += callback
                            }
                        }
                    }
            }
        }
        return callbacks
    }
}
