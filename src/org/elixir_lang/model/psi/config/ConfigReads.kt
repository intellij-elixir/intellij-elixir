package org.elixir_lang.model.psi.config

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock

/** The code that reads an application env key. */
internal object ConfigReads {
    /**
     * The reads of [app]'s [key] in [scope]: each read's key as written, or the whole call where it reads every key. A
     * read of [app] whose key is not written as a literal may read [key], so it is one.
     */
    @RequiresReadLock
    fun of(project: Project, app: String, key: String, scope: GlobalSearchScope): List<PsiElement> =
        ConfigIndexing.written(project, ConfigIndexKey.App(app), ConfigEntry.Kind.READ, scope)
            .filter { (written, element) -> written == WrittenKey.Unknown || written == WrittenKey.All || written.atom(element) == key }
            .map { (_, element) -> element } +
            ConfigIndexing.written(project, ConfigIndexKey.AnyApp, ConfigEntry.Kind.READ, scope)
                .filter { (written, element) -> written.atom(element) == key }
                .map { (_, element) -> element }
}
