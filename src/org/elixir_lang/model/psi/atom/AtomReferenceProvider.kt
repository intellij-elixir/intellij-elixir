package org.elixir_lang.model.psi.atom

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

@Suppress("UnstableApiUsage")
internal class AtomReferenceProvider : PsiSymbolReferenceProvider {
    @RequiresReadLock
    override fun getReferences(
        element: PsiExternalReferenceHost,
        hints: PsiSymbolReferenceHints
    ): Collection<PsiSymbolReference> {
        val atom = element as? ElixirAtom ?: return emptyList()
        val context = atom.mfaReferenceContext() ?: return emptyList()
        return listOf(AtomReference(atom, context.moduleElement, atom.nameRangeInAtom(), context.arity))
    }

    override fun getSearchRequests(project: Project, target: Symbol): Collection<SearchRequest> = emptyList()
}
