package org.elixir_lang.model.psi.config

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.indexing.FileBasedIndex

/** The config lines, in the files that count, that set an application env key. */
internal object ConfigSettings {
    /** Where [app]'s [key] is set: each setting's key, as written. */
    @RequiresReadLock
    fun of(project: Project, app: String, key: String, scope: GlobalSearchScope = ConfigFiles.scope(project)): List<PsiElement> =
        ConfigIndexing.written(project, ConfigIndexKey.App(app), scope) { it == ConfigEntry.Kind.Set }
            .filter { it.key.atom(it.element) == key }
            .map { it.element }

    /** The apps with a setting of [key]. */
    @RequiresReadLock
    fun apps(project: Project, key: String): List<String> {
        val scope = ConfigFiles.scope(project)

        return FileBasedIndex.getInstance()
            .getAllKeys(ConfigIndexing.NAME, project)
            .filterIsInstance<ConfigIndexKey.App>()
            .map { it.atom }
            .filter { of(project, it, key, scope).isNotEmpty() }
    }
}
