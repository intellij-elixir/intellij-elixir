package org.elixir_lang.model.psi.module_attribute

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.AtOperation
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.moduleAttributeAtom
import org.elixir_lang.psi.scope.module_attribute.MultiResolve

@Suppress("UnstableApiUsage")
class ModuleAttributeReference(
    private val call: Call,
    private val rangeInElement: TextRange
) : PsiSymbolReference {
    override fun getElement(): PsiElement = call

    override fun getRangeInElement(): TextRange = rangeInElement

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> =
        resolveSymbols(call)

    companion object {
        @RequiresReadLock
        fun resolveSymbols(call: Call): List<ModuleAttributeSymbol> {
            val declaration = resolveDeclaration(call) ?: return emptyList()
            return ModuleAttributeSymbol.fromDeclaration(declaration)?.let { listOf(it) } ?: emptyList()
        }

        @RequiresReadLock
        fun resolveDeclaration(call: Call): AtUnqualifiedNoParenthesesCall<*>? {
            ThreadingAssertions.assertReadAccess()

            val name = call.functionName() ?: return null
            return resolveDeclaration(name, call)
        }

        /** The declaration [use], `@name`, reads, whatever its operand parses as: in `%@name{}` it is a variable. */
        @RequiresReadLock
        fun resolveDeclaration(use: AtOperation): AtUnqualifiedNoParenthesesCall<*>? {
            ThreadingAssertions.assertReadAccess()

            val name = moduleAttributeAtom(use) ?: return null
            return resolveDeclaration(name, use)
        }

        private fun resolveDeclaration(name: String, entrance: PsiElement): AtUnqualifiedNoParenthesesCall<*>? =
            MultiResolve.resolveResultOrderedSet("@$name", entrance)
                .toList()
                .firstOrNull { it.isValidResult }
                ?.element as? AtUnqualifiedNoParenthesesCall<*>
    }
}
