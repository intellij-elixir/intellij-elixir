package org.elixir_lang.model.psi.protocol

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.call.Call

/**
 * Symbol reference from a `def`/`defmacro` clause inside a `defimpl` to the matching
 * `def`/`defmacro` in the protocol - powers "Go To Declaration" from an implementation
 * to its protocol specification.
 *
 * Resolution: the enclosing `defimpl` references exactly one protocol; resolve that protocol's
 * `defmodule` and match its definition clauses by name/arity/kind.
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
        if (!CallDefinitionClause.`is`(call)) return emptyList()
        val nameArity = CallDefinitionClause.nameArityInterval(call, ResolveState.initial()) ?: return emptyList()
        val macro = CallDefinitionClause.capabilities(call)?.quotesArguments == true

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
                        if (pf.name == nameArity.name &&
                            pf.arity in nameArity.arityInterval &&
                            pf.macro == macro
                        ) results += pf
                    }
                }
        }
        return results
    }
}
