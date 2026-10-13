package org.elixir_lang.model.psi.protocol

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.call.Call

/**
 * Symbol reference from a definition inside a `defimpl` to the protocol function it implements - powers "Go To
 * Declaration" from an implementation to its protocol specification.
 *
 * Resolution: the enclosing `defimpl` references exactly one protocol; resolve that protocol's
 * `defmodule` and match its definition clauses by [ProtocolImplementation].
 */
@Suppress("UnstableApiUsage")
class ProtocolImplReference(
    private val call: Call,
    private val rangeInElement: TextRange
) : PsiSymbolReference {
    override fun getElement(): PsiElement = call

    override fun getRangeInElement(): TextRange = rangeInElement

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> {
        if (!DefinitionDeclaration.`is`(call)) return emptyList()
        val declaration = DefinitionDeclaration.of(call, ResolveState.initial()) ?: return emptyList()

        // Walk up to the defimpl - confirmed present by ProtocolImplReferenceProvider
        val defimpl = CallDefinitionClause.enclosingModularMacroCall(call) ?: return emptyList()
        val protocols = mutableListOf<PsiElement>()
        Implementation.processProtocols(defimpl) { protocol -> protocols.add(protocol) }

        val results = mutableListOf<Symbol>()

        for (element in protocols) {
            ProgressManager.checkCanceled()
            if (element !is Call) continue
            CallDefinitionClause.modularChildCalls(element)
                .filter { CallDefinitionClause.`is`(it) }
                .forEach { protocolClause ->
                    ProtocolFunction.fromClause(protocolClause).forEach { pf ->
                        if (ProtocolImplementation.implementedBy(pf, declaration)) results += pf
                    }
                }
        }
        return results
    }
}
