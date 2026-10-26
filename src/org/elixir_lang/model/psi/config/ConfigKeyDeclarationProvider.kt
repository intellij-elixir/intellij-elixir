package org.elixir_lang.model.psi.config

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirKeywordKey

/** A config line's atom key: `config/2`'s keyword key or `config/3`'s atom. Ctrl-Click there shows the key's reads. */
@Suppress("UnstableApiUsage")
internal class ConfigKeyDeclarationProvider : PsiSymbolDeclarationProvider {
    @RequiresReadLock
    override fun getDeclarations(element: PsiElement, offsetInElement: Int): Collection<PsiSymbolDeclaration> {
        if (element !is ElixirAtom && element !is ElixirKeywordKey) return emptyList()
        val set = Keys.setAt(element) ?: return emptyList()
        val key = (set.key as? WrittenKey.Atom)?.name ?: return emptyList()
        val symbol = ConfigKeySymbol(set.app, key)
        val range = Keys.range(element)

        return listOf(object : PsiSymbolDeclaration {
            override fun getDeclaringElement(): PsiElement = element

            override fun getRangeInDeclaringElement(): TextRange = range

            override fun getSymbol(): Symbol = symbol
        })
    }
}
