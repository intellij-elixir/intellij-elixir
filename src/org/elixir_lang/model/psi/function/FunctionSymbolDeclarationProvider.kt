package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Form
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.scope.call_definition_clause.Declarations

/**
 * Exposes the name of each function a declaring form declares - a `def`/`defp`/`defmacro`/`defmacrop` clause, a
 * `defdelegate` head, and the name an EEx `function_from_*` or a `Mix.Generator` embed declares - as a declaration of
 * its [FunctionSymbol], so the platform's "Declaration or Usages" flow treats the caret as a declaration (-> Show
 * Usages) rather than navigating.
 *
 * Clauses inside a `defprotocol` are owned by [org.elixir_lang.model.psi.protocol.ProtocolFunction] (guarded in [FunctionSymbol.fromClause]).
 * The declaration is anchored on the declaring call with the name's range.
 *
 * A head with default arguments (`def foo(a, b \\ 1)`) declares one function per arity in its interval, all at the
 * **same** name range. Exposing each as its own declaration would make the platform offer an arity chooser on
 * Shift+F6 (and break in-place rename headlessly). They are the same textual name, so a single representative
 * declaration, the lowest arity, is exposed; the usage searcher already renames every arity's call sites via
 * family/`Callable` resolution.
 */
@Suppress("UnstableApiUsage")
internal class FunctionSymbolDeclarationProvider : PsiSymbolDeclarationProvider {
    @RequiresReadLock
    override fun getDeclarations(element: PsiElement, offsetInElement: Int): Collection<PsiSymbolDeclaration> {
        val (call, form) = DeclaringForm.enclosing(element) ?: return emptyList()

        return when (form) {
            Form.CLAUSE -> clause(call)
            Form.DELEGATION, Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> declared(call, form)
            Form.CALLBACK, Form.EXCEPTION -> emptyList()
        }
    }

    private fun clause(clause: Call): List<PsiSymbolDeclaration> {
        val arity = CallDefinitionClause.declaration(clause, ResolveState.initial())?.let(UseSymbols::headArity)
            ?: return emptyList()
        val symbol = FunctionSymbol.fromClause(clause).firstOrNull { it.arity == arity } ?: return emptyList()
        val nameIdentifier = DeclaringForm.names(clause, Form.CLAUSE).singleOrNull() ?: return emptyList()

        return listOf(declaration(clause, nameIdentifier, symbol))
    }

    private fun declared(call: Call, form: Form): List<PsiSymbolDeclaration> {
        val nameIdentifier = DeclaringForm.names(call, form).singleOrNull() ?: return emptyList()

        return Declarations.of(form, call, ResolveState.initial()).mapNotNull { spelled ->
            val declaration = spelled.declaration ?: return@mapNotNull null
            val arity = UseSymbols.headArity(declaration) ?: return@mapNotNull null

            UseSymbols.symbols(call, nameIdentifier, declaration, arity).firstOrNull()?.let { declaration(call, nameIdentifier, it) }
        }
    }

    private fun declaration(call: Call, nameIdentifier: PsiElement, symbol: Symbol): PsiSymbolDeclaration {
        val rangeInDeclaringElement = UseSymbols.nameRange(nameIdentifier).shiftLeft(call.textRange.startOffset)

        return object : PsiSymbolDeclaration {
            override fun getDeclaringElement(): PsiElement = call
            override fun getRangeInDeclaringElement(): TextRange = rangeInDeclaringElement
            override fun getSymbol(): Symbol = symbol
        }
    }
}
