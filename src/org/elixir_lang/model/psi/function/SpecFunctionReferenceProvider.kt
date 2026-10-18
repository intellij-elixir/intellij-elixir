package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiExternalReferenceHost
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceHints
import com.intellij.model.psi.PsiSymbolReferenceProvider
import com.intellij.model.search.SearchRequest
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.moduleAttributeName
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.CallDefinitionSpecification

/**
 * Attaches [SpecFunctionReference] to the function head in `@spec foo(...) :: ...`.
 */
@Suppress("UnstableApiUsage")
internal class SpecFunctionReferenceProvider : PsiSymbolReferenceProvider {
    @RequiresReadLock
    override fun getReferences(
        element: PsiExternalReferenceHost,
        hints: PsiSymbolReferenceHints
    ): Collection<PsiSymbolReference> {
        if (element !is Call) return emptyList()
        val moduleAttribute = generateSequence(element.parent) { it.parent }
            .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
            .firstOrNull()
            ?: return emptyList()
        if (moduleAttributeName(moduleAttribute) != "@spec") return emptyList()
        val specification = CallDefinitionSpecification.specification(moduleAttribute) ?: return emptyList()
        val specHead = CallDefinitionSpecification.specificationType(specification) ?: return emptyList()
        if (!specHead.isEquivalentTo(element)) return emptyList()

        val nameElement = CallDefinitionHead.nameElement(element) ?: return emptyList()
        val rangeInElement = UseSymbols.nameRange(nameElement).shiftLeft(element.textRange.startOffset)
        return listOf(SpecFunctionReference(element, moduleAttribute, rangeInElement))
    }

    override fun getSearchRequests(project: Project, target: Symbol): Collection<SearchRequest> = emptyList()
}
