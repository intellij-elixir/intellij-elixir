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

/** The config files whose settings count. */
object ConfigFiles {
    /**
     * The `.exs` files in the directory of each content `mix.exs` file's `config_path:`. A dependency's `mix.exs` is not
     * content, so its config never counts.
     */
    @RequiresReadLock
    fun scope(project: Project): GlobalSearchScope {
        val directories = mutableSetOf<VirtualFile>()

        FileBasedIndex.getInstance().processValues(
            ConfigIndexing.NAME,
            ConfigIndexKey.Project,
            null,
            { mixExs, entries ->
                for (entry in entries) {
                    ProgressManager.checkCanceled()
                    val path = ((entry as? ConfigEntry.ProjectEntry)?.configPath as? ProjectKeywords.ConfigPath.Written)?.path
                        ?: continue

                    mixExs.parent
                        ?.findFileByRelativePath(PathUtil.getParentPath(path))
                        ?.takeIf { it.isDirectory }
                        ?.let(directories::add)
                }

                true
            },
            GlobalSearchScope.projectScope(project)
        )

        return if (directories.isEmpty()) {
            GlobalSearchScope.EMPTY_SCOPE
        } else {
            ExsScope(GlobalSearchScopesCore.directoriesScope(project, false, *directories.toTypedArray()))
        }
    }

    private class ExsScope(directories: GlobalSearchScope) : DelegatingGlobalSearchScope(directories) {
        override fun contains(file: VirtualFile): Boolean = file.extension == "exs" && super.contains(file)
    }
}
