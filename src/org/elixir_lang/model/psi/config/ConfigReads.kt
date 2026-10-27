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
            reads(project, ConfigIndexKey.App(app), scope) {
                it.key == WrittenKey.Unknown || it.key == WrittenKey.All || it.key.atom(it.element) == key
            } +
                reads(project, ConfigIndexKey.AnyApp, scope) { it.key.atom(it.element) == key }
            )
            .map { it.element }

    /** The keys written as atoms that reads of [app] in [scope] read. A read whose app is not a literal may read [app]. */
    @RequiresReadLock
    fun keys(project: Project, app: String, scope: GlobalSearchScope): List<String> =
        (reads(project, ConfigIndexKey.App(app), scope, ::isAtom) + reads(project, ConfigIndexKey.AnyApp, scope, ::isAtom))
            .map { (it.key as WrittenKey.Atom).name }

    /** The literal apps that code in [scope] reads. */
    @RequiresReadLock
    fun apps(project: Project, scope: GlobalSearchScope): List<String> =
        ConfigIndexing.apps(project).filter { reads(project, ConfigIndexKey.App(it), scope).isNotEmpty() }

    /** The reads of [app] in [scope] that [written] keeps: candidates the index files, each a read by [isRead]. */
    @RequiresReadLock
    private fun reads(
        project: Project,
        app: ReadApp,
        scope: GlobalSearchScope,
        written: (ConfigIndexing.Written) -> Boolean = { true },
    ): List<ConfigIndexing.Written> =
        ConfigIndexing.written(project, app, scope) { it is ConfigEntry.Kind.Read }.filter { written(it) && isRead(it) }

    private fun isAtom(written: ConfigIndexing.Written): Boolean = written.key is WrittenKey.Atom

    @RequiresReadLock
    private fun isRead(written: ConfigIndexing.Written): Boolean {
        val module = (written.kind as? ConfigEntry.Kind.Read)?.module ?: return false
        val call = if (written.key == WrittenKey.All) written.element as? Call else Keys.call(written.element)

        return call != null && module.isRead(call)
    }
}
