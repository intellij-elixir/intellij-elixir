package org.elixir_lang.model.psi.generic_server

import org.elixir_lang.psi.impl.nameRangeInAtom
import com.intellij.model.Symbol
import com.intellij.model.psi.PsiExternalReferenceHost
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceHints
import com.intellij.model.psi.PsiSymbolReferenceProvider
import com.intellij.model.search.SearchRequest
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.ElixirAtom

/**
 * Attaches a [GenServerRequestReference] to a message atom only when it is the message argument of a
 * supported GenServer / `Process` send site with at least one matching handler clause; otherwise
 * returns an empty collection so it coexists with [org.elixir_lang.model.psi.atom.AtomReferenceProvider]
 * (whose MFA context is null at a send site).
 */
@Suppress("UnstableApiUsage")
internal class GenServerRequestReferenceProvider : PsiSymbolReferenceProvider {
    @RequiresReadLock
    override fun getReferences(
        element: PsiExternalReferenceHost,
        hints: PsiSymbolReferenceHints
    ): Collection<PsiSymbolReference> {
        val atom = element as? ElixirAtom ?: return emptyList()
        if (GenServerDispatch.handlerTargetsForRequestAtom(atom).isEmpty()) return emptyList()
        return listOf(GenServerRequestReference(atom, atom.nameRangeInAtom()))
    }

    override fun getSearchRequests(project: Project, target: Symbol): Collection<SearchRequest> = emptyList()
}
