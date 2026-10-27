package org.elixir_lang.model.psi.config

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.util.PathUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.indexing.FileBasedIndex
import org.elixir_lang.mix.ProjectKeywords

/** The config files whose settings count, and the apps that are the project's own. */
object ConfigFiles {
    /**
     * The `.exs` files in the directory of each content `mix.exs` file's `config_path:`. A dependency's `mix.exs` is not
     * content, so its config never counts.
     */
    @RequiresReadLock
    fun scope(project: Project): GlobalSearchScope {
        val directories = projects(project).mapNotNullTo(mutableSetOf()) { (mixExs, entry) ->
            (entry.configPath as? ProjectKeywords.ConfigPath.Written)?.path?.let { path ->
                mixExs.parent?.findFileByRelativePath(PathUtil.getParentPath(path))?.takeIf { it.isDirectory }
            }
        }

        return if (directories.isEmpty()) {
            GlobalSearchScope.EMPTY_SCOPE
        } else {
            ExsScope(GlobalSearchScopesCore.directoriesScope(project, false, *directories.toTypedArray()))
        }
    }

    /** The apps the content `mix.exs` files name. */
    @RequiresReadLock
    fun ownApps(project: Project): kotlin.collections.Set<String> = projects(project).mapNotNullTo(mutableSetOf()) { (_, entry) -> entry.app }

    /** Each content `mix.exs` file and its project entry. */
    private fun projects(project: Project): List<Pair<VirtualFile, ConfigEntry.ProjectEntry>> {
        val projects = mutableListOf<Pair<VirtualFile, ConfigEntry.ProjectEntry>>()

        FileBasedIndex.getInstance().processValues(
            ConfigIndexing.NAME,
            ConfigIndexKey.Project,
            null,
            { mixExs, entries ->
                ProgressManager.checkCanceled()
                entries.filterIsInstance<ConfigEntry.ProjectEntry>().mapTo(projects) { mixExs to it }

                true
            },
            GlobalSearchScope.projectScope(project)
        )

        return projects
    }

    private class ExsScope(directories: GlobalSearchScope) : DelegatingGlobalSearchScope(directories) {
        override fun contains(file: VirtualFile): Boolean = file.extension == "exs" && super.contains(file)
    }
}
