package org.elixir_lang.model.psi.config

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiExternalReferenceHost
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceHints
import com.intellij.model.psi.PsiSymbolReferenceProvider
import com.intellij.model.search.SearchRequest
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.impl.isOutermostQualifiableAlias

/**
 * A read's atom key, and an alias key of a read or a setting. Decided by [Read.of] and [Set.of] alone, so the reference
 * is there in dumb mode too, and resolves once indexing ends.
 */
@Suppress("UnstableApiUsage")
internal class ConfigKeyReferenceProvider : PsiSymbolReferenceProvider {
    @RequiresReadLock
    override fun getReferences(element: PsiExternalReferenceHost, hints: PsiSymbolReferenceHints): Collection<PsiSymbolReference> =
        when (element) {
            is ElixirAtom ->
                Keys.readAt(element)
                    ?.takeIf { it.key is WrittenKey.Atom }
                    ?.let { listOf(ConfigKeyReference(element, Keys.range(element), it.app, it.key, it)) }
            is QualifiableAlias ->
                element
                    .takeIf { it.isOutermostQualifiableAlias() }
                    ?.let { alias ->
                        Keys.readAt(alias)
                            ?.takeIf { it.key == WrittenKey.Alias }
                            ?.let { ConfigKeyReference(alias, Keys.range(alias), it.app, it.key, it) }
                            ?: Keys.setAt(alias)
                                ?.takeIf { it.key == WrittenKey.Alias }
                                ?.let { ConfigKeyReference(alias, Keys.range(alias), ConfigIndexKey.App(it.app), it.key, null) }
                    }
                    ?.let { listOf(it) }
            else -> null
        }.orEmpty()

    override fun getSearchRequests(project: Project, target: Symbol): Collection<SearchRequest> = emptyList()
}
