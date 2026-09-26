package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.call.Call

/**
 * Reference from the function head in `@spec foo(...) :: ...` to the declaration of `foo`: a `def`, `defdelegate` or
 * EEx function.
 *
 * This keeps `@spec`-to-definition navigation on the Symbol API path and lets usages of
 * `FunctionSymbol` distinguish specification usages from executable call sites.
 */
@Suppress("UnstableApiUsage")
class SpecFunctionReference(
    private val typeHeadCall: Call,
    private val moduleAttribute: AtUnqualifiedNoParenthesesCall<*>,
    private val rangeInElement: TextRange
) : PsiSymbolReference {
    override fun getElement(): PsiElement = typeHeadCall

    override fun getRangeInElement(): TextRange = rangeInElement

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> =
        org.elixir_lang.reference.CallDefinitionClause(typeHeadCall, moduleAttribute)
            .multiResolve(false)
            .filter { it.isValidResult }
            .mapNotNull { it.element as? Call }
            .flatMap { FunctionSymbol.fromDeclaration(it) }
            .distinct()
}
