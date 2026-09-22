package org.elixir_lang.model.psi.callback

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.macroChildCallSequence
import org.elixir_lang.psi.stub.index.ModularName

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
        if (CallableDeclaration.headBindingFormOf(call) != CallableDeclaration.Form.CLAUSE) return emptyList()
        val nameArity = CallDefinitionClause.nameArityInterval(call, ResolveState.initial()) ?: return emptyList()
        val macro = CallDefinitionClause.isMacro(call)
        val module = CallDefinitionClause.enclosingModularMacroCall(call) ?: return emptyList()

        val behaviourNames = BehaviourMembership.namesImplementedBy(module)
        if (behaviourNames.isEmpty()) return emptyList()

        val scope = GlobalSearchScope.allScope(call.project)
        val callbacks = mutableListOf<Symbol>()
        for (name in behaviourNames) {
            for (behaviourModule in StubIndex.getElements(ModularName.KEY, name, call.project, scope, NamedElement::class.java)) {
                ProgressManager.checkCanceled()
                if (behaviourModule !is Call) continue
                behaviourModule
                    .macroChildCallSequence()
                    .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
                    .filter { CallableDeclaration.syntacticFormOf(it) == CallableDeclaration.Form.CALLBACK }
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
