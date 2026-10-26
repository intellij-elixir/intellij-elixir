package org.elixir_lang.model.psi.config

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.call.Call

/** The code that reads an application env key. */
internal object ConfigReads {
    /**
     * The reads of [app]'s [key] in [scope]: each read's key as written, or the whole call where it reads every key. A
     * read of [app] whose key is not written as a literal may read [key], so it is one.
     */
    @RequiresReadLock
    fun of(project: Project, app: String, key: String, scope: GlobalSearchScope): List<PsiElement> =
        (
            ConfigIndexing.written(project, ConfigIndexKey.App(app), scope) { it is ConfigEntry.Kind.Read }
                .filter { it.key == WrittenKey.Unknown || it.key == WrittenKey.All || it.key.atom(it.element) == key } +
                ConfigIndexing.written(project, ConfigIndexKey.AnyApp, scope) { it is ConfigEntry.Kind.Read }.filter { it.key.atom(it.element) == key }
            )
            .filter { isRead(it) }
            .map { it.element }

    @RequiresReadLock
    private fun isRead(written: ConfigIndexing.Written): Boolean {
        val module = (written.kind as? ConfigEntry.Kind.Read)?.module ?: return false
        val call = if (written.key == WrittenKey.All) written.element as? Call else Keys.call(written.element)

        return call != null && module.isRead(call)
    }
}
