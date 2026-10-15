package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Use
import org.elixir_lang.model.psi.protocol.ProtocolFunction
import org.elixir_lang.psi.call.Call

/**
 * Symbol reference from a call-site expression to the [FunctionSymbol] (or [ProtocolFunction]) it
 * invokes - powers "Go To Declaration" (Ctrl+Click) from a call site to the matching `def`/`defmacro`.
 */
@Suppress("UnstableApiUsage")
class FunctionCallReference(
    private val call: Call,
    private val rangeInElement: TextRange
) : PsiSymbolReference {
    override fun getElement(): PsiElement = call

    override fun getRangeInElement(): TextRange = rangeInElement

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> = UseSymbols.of(Use.of(call))
}
