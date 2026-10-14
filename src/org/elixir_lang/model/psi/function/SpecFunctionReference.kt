package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.call.Call

/**
 * Reference from the function head in `@spec foo(...) :: ...` to the defining `def foo(...)`.
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
        definitions().flatMap { FunctionSymbol.fromDefinition(it) }.distinct()

    /** The clauses and `defdelegate`s the head names, which [resolveReference] maps to the functions they define. */
    @RequiresReadLock
    fun definitions(): List<Call> =
        org.elixir_lang.reference.CallDefinitionClause(typeHeadCall, moduleAttribute)
            .multiResolve(false)
            .filter { it.isValidResult }
            .mapNotNull { it.element as? Call }
            .filter { DefinitionDeclaration.`is`(it) }
}
