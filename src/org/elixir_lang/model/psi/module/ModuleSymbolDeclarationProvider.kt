package org.elixir_lang.model.psi.module

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.Module

@Suppress("UnstableApiUsage")
internal class ModuleSymbolDeclarationProvider : PsiSymbolDeclarationProvider {
    @RequiresReadLock
    override fun getDeclarations(element: PsiElement, offsetInElement: Int): Collection<PsiSymbolDeclaration> {
        val moduleCall = Module.enclosing(element) ?: return emptyList()

        val nameElement = ModuleSymbol.moduleNameElement(moduleCall) ?: return emptyList()
        // Fire when: (a) caret is on a descendant of the module name alias, OR
        // (b) the platform passes the declaring Call itself with an offset within the module name.
        val isInNameElement = when {
            PsiTreeUtil.isAncestor(nameElement, element, false) -> true
            element == moduleCall -> {
                val offsetInFile = element.textRange.startOffset + offsetInElement
                nameElement.textRange.containsOffset(offsetInFile)
            }
            else -> false
        }
        if (!isInNameElement) return emptyList()

        val symbol = ModuleSymbol.fromModular(moduleCall) ?: return emptyList()
        val rangeInDeclaringElement = nameElement.textRange.shiftLeft(moduleCall.textRange.startOffset)

        return listOf(object : PsiSymbolDeclaration {
            override fun getDeclaringElement(): PsiElement = moduleCall
            override fun getRangeInDeclaringElement(): TextRange = rangeInDeclaringElement
            override fun getSymbol(): Symbol = symbol
        })
    }
}
