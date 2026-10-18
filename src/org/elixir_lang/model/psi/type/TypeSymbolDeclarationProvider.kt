package org.elixir_lang.model.psi.type

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.Type as TypeElement

@Suppress("UnstableApiUsage")
internal class TypeSymbolDeclarationProvider : PsiSymbolDeclarationProvider {
    @RequiresReadLock
    override fun getDeclarations(element: PsiElement, offsetInElement: Int): Collection<PsiSymbolDeclaration> {
        val typeAttribute = generateSequence(element) { it.parent }
            .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
            .firstOrNull { TypeElement.`is`(it) }
            ?: return emptyList()
        val head = TypeElement.head(typeAttribute) ?: return emptyList()
        val at = if (element == typeAttribute) element.findElementAt(offsetInElement) else element
        if (at == null || !CallDefinitionHead.isOnName(head, at)) return emptyList()

        val symbols = TypeSymbol.fromTypeAttribute(typeAttribute)
        if (symbols.isEmpty()) return emptyList()

        return CallDefinitionHead.nameElements(head).flatMap(UseSymbols::declarationRanges).flatMap { range ->
            val rangeInDeclaringElement = range.shiftLeft(typeAttribute.textRange.startOffset)

            symbols.map { symbol ->
                object : PsiSymbolDeclaration {
                    override fun getDeclaringElement(): PsiElement = typeAttribute
                    override fun getRangeInDeclaringElement(): TextRange = rangeInDeclaringElement
                    override fun getSymbol(): Symbol = symbol
                }
            }
        }
    }
}
