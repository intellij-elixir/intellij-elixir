package org.elixir_lang.model.psi.protocol

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiExternalReferenceHost
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceHints
import com.intellij.model.psi.PsiSymbolReferenceProvider
import com.intellij.model.search.SearchRequest
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.call.Call

/**
 * Attaches a [ProtocolImplReference] to a clause inside a `defimpl`, so "Go To Declaration" navigates to the matching
 * `def` in the protocol. A `defdelegate` head gets none: it declares its own `FunctionSymbol`.
 *
 * Only a definition that implements a protocol function gets one: a reference that resolves to nothing would shadow
 * the definition's own `FunctionSymbol`, breaking Find Usages and Rename on a private or helper function.
 *
 * [getSearchRequests] returns nothing on purpose: protocol function -> implementations
 * (reverse Find Usages) is served by [org.elixir_lang.model.psi.ElixirSymbolUsageSearcher]'s
 * direct query, avoiding double-listing each implementation.
 */
@Suppress("UnstableApiUsage")
internal class ProtocolImplReferenceProvider : PsiSymbolReferenceProvider {
    @RequiresReadLock
    override fun getReferences(element: PsiExternalReferenceHost, hints: PsiSymbolReferenceHints): Collection<PsiSymbolReference> {
        if (element !is Call || !CallDefinitionClause.`is`(element)) return emptyList()
        val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(element) ?: return emptyList()
        if (!Implementation.`is`(enclosingModular)) return emptyList()
        val nameId = CallDefinitionClause.nameIdentifier(element) ?: return emptyList()
        val rangeInElement = nameId.textRange.shiftLeft(element.textRange.startOffset)
        val reference = ProtocolImplReference(element, rangeInElement)
        return if (reference.resolveReference().isEmpty()) emptyList() else listOf(reference)
    }

    override fun getSearchRequests(project: Project, target: Symbol): Collection<SearchRequest> = emptyList()
}
