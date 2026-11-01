package org.elixir_lang.model.psi.config

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock

/** The config lines, in the files that count, that set an application env key. */
internal object ConfigSettings {
    /** Where [app]'s [key] is set: each setting's key, as written. */
    @RequiresReadLock
    fun of(project: Project, app: String, key: String, scope: GlobalSearchScope = ConfigFiles.counted(project)): List<PsiElement> =
        settings(project, app, scope)
            .filter { it.key.atom(it.element) == key }
            .map { it.element }

    /**
     * Whether config in [scope] that can't be read may set [app]'s keys: a setting or write of [app], or of an app that
     * can't be read ([ConfigIndexing.Decided.UNREAD]); a setting of [app] whose key can't be named; or config [scope] may not
     * hold ([ConfigFiles.unread]).
     */
    @RequiresReadLock
    fun unread(project: Project, app: String, scope: GlobalSearchScope): Boolean =
        listOf(ConfigIndexKey.App(app), ConfigIndexKey.AnyApp).any { key ->
            ConfigIndexing.written(project, key, scope, ConfigIndexing.Decided.UNREAD).isNotEmpty()
        } ||
            settings(project, app, scope).any { it.key.atom(it.element) == null } ||
            ConfigFiles.unread(project, scope)

    /** The keys written as atoms that [app]'s settings set. */
    @RequiresReadLock
    fun keys(project: Project, app: String): List<String> =
        settings(project, app, ConfigFiles.counted(project)).mapNotNull { (it.key as? WrittenKey.Atom)?.name }

    /** The apps with a setting of [key]. */
    @RequiresReadLock
    fun apps(project: Project, key: String): List<String> {
        val scope = ConfigFiles.counted(project)

        return ConfigIndexing.apps(project).filter { of(project, it, key, scope).isNotEmpty() }
    }

    /** The apps with a setting. */
    @RequiresReadLock
    fun apps(project: Project): List<String> {
        val scope = ConfigFiles.counted(project)

        return ConfigIndexing.apps(project).filter { settings(project, it, scope).isNotEmpty() }
    }

    private fun settings(project: Project, app: String, scope: GlobalSearchScope): List<ConfigIndexing.Written> =
        ConfigIndexing.written(project, ConfigIndexKey.App(app), scope, ConfigIndexing.Decided.SET)
}
