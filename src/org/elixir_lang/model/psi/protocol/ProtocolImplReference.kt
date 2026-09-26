package org.elixir_lang.model.psi.protocol

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.NamedElement
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.macroChildCallList
import org.elixir_lang.psi.stub.index.ModularName

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
        val macro = CallableDeclaration.isCompileTime(call)

        // Walk up to the defimpl - confirmed present by ProtocolImplReferenceProvider
        val defimpl = CallDefinitionClause.enclosingModularMacroCall(call) ?: return emptyList()
        val protocolName = Implementation.protocolName(defimpl) ?: return emptyList()

        val scope = GlobalSearchScope.allScope(call.project)
        val results = mutableListOf<Symbol>()

        for (element in StubIndex.getElements(ModularName.KEY, protocolName, call.project, scope, NamedElement::class.java)) {
            ProgressManager.checkCanceled()
            if (element !is Call) continue
            element.macroChildCallList()
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
