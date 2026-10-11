package org.elixir_lang.model.psi.callback

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.call.Call

/**
 * Symbol reference from an implementing `def`/`defmacro` clause's name to the `@callback`(s) it
 * implements - powers "Go To Declaration" from an implementation to its behaviour callback.
 */
@Suppress("UnstableApiUsage")
class CallbackImplReference(
    private val call: Call,
    private val rangeInElement: TextRange
) : PsiSymbolReference {
    override fun getElement(): PsiElement = call

    override fun getRangeInElement(): TextRange = rangeInElement

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> = CallbackImplementation.implementedCallbacks(call)
}
