package org.elixir_lang.model.psi.config

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Module

/** The apps and first-level keys of the application env that are written somewhere, by name. */
internal object ConfigNames {
    /** The project's own apps, the apps its config files configure, and the apps code reads by a literal name. */
    @RequiresReadLock
    fun apps(project: Project): List<String> =
        (
            ConfigFiles.ownApps(project) +
                ConfigSettings.apps(project) +
                ConfigReads.apps(project, GlobalSearchScope.allScope(project))
            ).distinct().sorted()

    /** The keys of [app] that its config files set or code reads, libraries and the SDK included, none of them a module's name. */
    @RequiresReadLock
    fun keys(project: Project, app: String): List<String> =
        (ConfigSettings.keys(project, app) + ConfigReads.keys(project, app, GlobalSearchScope.allScope(project)))
            .filter { Module.inspectedAlias(it) == null }
            .distinct()
            .sorted()
}
