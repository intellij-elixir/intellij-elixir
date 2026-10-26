package org.elixir_lang.model.psi.config

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock

/**
 * An application env key, written at [element], of [app]: the key of each app that sets it. The key of a [read] refers
 * only where the read is one.
 */
@Suppress("UnstableApiUsage")
internal class ConfigKeyReference(
    private val element: PsiElement,
    private val rangeInElement: TextRange,
    private val app: ReadApp,
    private val key: WrittenKey,
    private val read: Read?,
) : PsiSymbolReference {
    override fun getElement(): PsiElement = element

    override fun getRangeInElement(): TextRange = rangeInElement

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> {
        val project = element.project
        if (DumbService.isDumb(project)) return emptyList()
        if (read != null && !read.module.isRead(read.call)) return emptyList()
        val atom = key.atom(element) ?: return emptyList()
        val apps = when (app) {
            is ConfigIndexKey.App -> listOf(app.atom).filter { ConfigSettings.of(project, it, atom).isNotEmpty() }
            ConfigIndexKey.AnyApp -> ConfigSettings.apps(project, atom)
        }

        return apps.map { ConfigKeySymbol(it, atom) }
    }
}
