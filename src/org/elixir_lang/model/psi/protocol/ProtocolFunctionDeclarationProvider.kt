package org.elixir_lang.model.psi.protocol

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.CallDefinitionHead

/**
 * Exposes the name of a `def`/`defmacro` clause inside a `defprotocol` as a declaration
 * of the [ProtocolFunction] symbol(s), so the platform's "Declaration or Usages" flow
 * treats the caret as a declaration (-> Show Usages) rather than navigating.
 *
 * The declaration is anchored on the `CallDefinitionClause` element itself with the
 * name-identifier range shifted into the clause's coordinates.
 */
@Suppress("UnstableApiUsage")
internal class ProtocolFunctionDeclarationProvider : PsiSymbolDeclarationProvider {
    @RequiresReadLock
    override fun getDeclarations(element: PsiElement, offsetInElement: Int): Collection<PsiSymbolDeclaration> {
        val clause = generateSequence(element) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { CallDefinitionClause.`is`(it) }
            ?: return emptyList()
        val symbols = ProtocolFunction.fromClause(clause)
        if (symbols.isEmpty()) return emptyList()
        val head = CallDefinitionClause.head(clause) ?: return emptyList()

        return CallDefinitionHead.nameElements(head).flatMap(UseSymbols::declarationRanges).flatMap { range ->
            val rangeInDeclaringElement = range.shiftLeft(clause.textRange.startOffset)

            symbols.map { symbol ->
                object : PsiSymbolDeclaration {
                    override fun getDeclaringElement(): PsiElement = clause
                    override fun getRangeInDeclaringElement(): TextRange = rangeInDeclaringElement
                    override fun getSymbol(): Symbol = symbol
                }
            }
        }
    }
}
