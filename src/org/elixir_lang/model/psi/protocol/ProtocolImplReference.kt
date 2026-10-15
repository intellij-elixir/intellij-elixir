package org.elixir_lang.model.psi.protocol

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.DefinitionDeclaration
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

        return ProtocolImplementation.membersImplementedBy(call)
    }
}
