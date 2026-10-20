package org.elixir_lang.model.psi.callback

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.Callback as CallbackElement

/**
 * Exposes the name of a `@callback`/`@macrocallback` as a declaration of the [Callback] symbol(s),
 * so the platform's "Declaration or Usages" flow treats the caret as a declaration (-> Show Usages)
 * rather than navigating.
 *
 * The declaration is anchored on the `@callback` module attribute element itself (the element this
 * provider is invoked with) - the platform requires `element === declaringElement`
 * (see platform `Declarations.declarationsInElement`) - with the name-identifier range shifted into
 * the attribute's coordinates.
 */
@Suppress("UnstableApiUsage")
internal class CallbackSymbolDeclarationProvider : PsiSymbolDeclarationProvider {
    @RequiresReadLock
    override fun getDeclarations(element: PsiElement, offsetInElement: Int): Collection<PsiSymbolDeclaration> {
        if (element !is AtUnqualifiedNoParenthesesCall<*>) return emptyList()
        val symbols = Callback.fromModuleAttribute(element)
        if (symbols.isEmpty()) return emptyList()
        val head = CallbackElement.head(element) ?: return emptyList()

        return CallDefinitionHead.nameElements(head).flatMap(UseSymbols::declarationRanges).flatMap { range ->
            val rangeInAttr = range.shiftLeft(element.textRange.startOffset)

            symbols.map { symbol ->
                object : PsiSymbolDeclaration {
                    override fun getDeclaringElement(): PsiElement = element
                    override fun getRangeInDeclaringElement(): TextRange = rangeInAttr
                    override fun getSymbol(): Symbol = symbol
                }
            }
        }
    }
}
