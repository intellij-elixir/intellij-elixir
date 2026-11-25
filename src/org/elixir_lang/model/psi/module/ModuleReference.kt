package org.elixir_lang.model.psi.module

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ModuleName
import org.elixir_lang.reference.resolver.Module as ModuleResolver

/**
 * A module reference hosted on the outermost [QualifiableAlias] of a usage site, resolving [alias]
 * (the chain node this reference's range covers - the whole chain or one of its prefixes).
 */
@Suppress("UnstableApiUsage")
class ModuleReference(
    private val host: QualifiableAlias,
    private val alias: QualifiableAlias,
    private val rangeInElement: TextRange
) : PsiSymbolReference {
    override fun getElement(): PsiElement = host

    override fun getRangeInElement(): TextRange = rangeInElement

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> = symbols(ModuleResolver.resolveWritten(alias, false))

    companion object {
        /**
         * The modules [name] names as seen from [entrance], as an alias written there would resolve. A name written
         * `Elixir.`-rooted is the module it says, which no `alias` in scope applies to.
         */
        @RequiresReadLock
        fun resolve(entrance: PsiElement, name: String): List<ModuleSymbol> {
            val rooted = org.elixir_lang.Module.inspectedAlias(name)

            return resolve(entrance, ModuleName(rooted ?: name, absolute = rooted != null))
        }

        /** The modules [module] names as seen from [entrance]: the aliases in scope apply to it unless it is absolute. */
        @RequiresReadLock
        fun resolve(entrance: PsiElement, module: ModuleName): List<ModuleSymbol> {
            ThreadingAssertions.assertReadAccess()

            return symbols(ModuleResolver.resolve(entrance, module, false))
        }

        private fun symbols(results: Array<ResolveResult>): List<ModuleSymbol> =
            results
                .filter(ResolveResult::isValidResult)
                .mapNotNull { result ->
                    when (val element = result.element) {
                        is Call -> element
                        // A module that exists only as compiled `.beam` resolves to the coarse stub element;
                        // its navigationElement is the `defmodule` Call in the decompiled mirror, which the
                        // source pipeline below accepts (same pattern as TypeReference/FunctionCallReference).
                        is org.elixir_lang.beam.psi.Module -> element.navigationElement as? Call
                        else -> null
                    }
                }
                .mapNotNull(ModuleSymbol::fromModular)
                .distinct()
    }
}
