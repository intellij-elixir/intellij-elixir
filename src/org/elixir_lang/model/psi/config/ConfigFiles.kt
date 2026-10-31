package org.elixir_lang.model.psi.config

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.PathUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.indexing.FileBasedIndex
import org.elixir_lang.mix.ProjectKeywords
import org.elixir_lang.psi.ElixirEndOfExpression
import org.elixir_lang.psi.ElixirInterpolation
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses

/** The config files whose settings count, and the apps that are the project's own. */
object ConfigFiles {
    /**
     * The `.exs` files in the directory of each content `mix.exs` file's `config_path:`. A dependency's `mix.exs` is not
     * content, so its config never counts.
     */
    @RequiresReadLock
    fun scope(project: Project): GlobalSearchScope {
        val directories = directories(project)

        return if (directories.isEmpty()) {
            GlobalSearchScope.EMPTY_SCOPE
        } else {
            ExsScope(GlobalSearchScopesCore.directoriesScope(project, false, *directories.toTypedArray()), directories)
        }
    }

    /**
     * Whether the config a build reads may hold files that [scope], the files that count, doesn't: a content `mix.exs`
     * names its `config_path:` in a way that can't be read, or an `import_config` in [scope] may name a file that
     * doesn't count.
     */
    @RequiresReadLock
    fun unread(project: Project, scope: GlobalSearchScope): Boolean {
        if (projects(project).any { (_, entry) -> entry.configPath == ProjectKeywords.ConfigPath.Unknown }) return true
        val imports = ConfigIndexing.written(project, ConfigIndexKey.AnyApp, scope, ConfigIndexing.Decided.IMPORT)
        if (imports.isEmpty()) return false
        val directories = directories(project)

        return imports.any { !countsImport(it.element, directories) }
    }

    /**
     * Whether [argument], an `import_config`'s, names a file in [directories], which `Config` expands against the
     * importing file's directory: a string whose directory has no interpolation, whose name ends `.exs` and interpolates
     * only the build's env or target ([isBuildName]), and which, where the name has no interpolation, exists. A wildcard,
     * which only `Mix.Config` expanded, and an escape aren't read.
     */
    private fun countsImport(argument: PsiElement, directories: kotlin.collections.Set<VirtualFile>): Boolean {
        // The index gives back the outermost element of the argument's range, which may wrap the string.
        val line = generateSequence(argument) { it.firstChild?.takeIf { child -> child.textRange == argument.textRange } }
            .firstNotNullOfOrNull { it as? ElixirLine }
            ?.takeUnless { it.isCharList }
            ?: return false
        val body = line.lineBody ?: return false
        val path = StringBuilder(body.text)
        for (interpolation in PsiTreeUtil.getChildrenOfTypeAsList(body, ElixirInterpolation::class.java)) {
            if (!isBuildName(interpolation)) return false
            val range = interpolation.textRange.shiftLeft(body.textRange.startOffset)
            path.replace(range.startOffset, range.endOffset, INTERPOLATED.toString().repeat(range.length))
        }
        if (path.any { it == '\\' || it in WILDCARD }) return false
        val slash = path.lastIndexOf("/")
        val directory = path.substring(0, slash.coerceAtLeast(0))
        val name = path.substring(slash + 1)
        if (INTERPOLATED in directory) return false
        val importer = argument.containingFile?.originalFile?.virtualFile?.parent ?: return false
        val folder = when {
            slash < 0 -> importer
            path.startsWith("/") -> importer.fileSystem.findFileByPath(directory.ifEmpty { "/" })
            else -> importer.findFileByRelativePath(directory)
        } ?: return false

        return counts(folder, name, directories) && (INTERPOLATED in name || folder.findChild(name) != null)
    }

    /**
     * Whether [interpolation] is only the build's env or target, as Mix's generated config names a file:
     * `config_env()`, `config_target()`, `Mix.env()` or `Mix.target()`.
     */
    private fun isBuildName(interpolation: ElixirInterpolation): Boolean {
        val call = interpolation.children.filterNot { it is ElixirEndOfExpression }.singleOrNull()
            ?.stripAccessExpressionAndParentheses() as? Call
            ?: return false

        return CONFIG_BUILD_NAMES.any { Set.isConfigFunction(call, it, 0) } ||
            call.functionName() in BUILD_NAMES && call.resolvedFinalArity() == 0 &&
            CallModule.of(call)?.reaches(call, listOf(MIX)) == CallModule.Answer.REACHES
    }

    /** Whether a file named [name] in [folder] counts: config directly in one of [directories]. */
    private fun counts(folder: VirtualFile?, name: String, directories: kotlin.collections.Set<VirtualFile>): Boolean =
        folder in directories && isConfig(name)

    /** Whether a file named [name] may be config: an `.exs` file. */
    fun isConfig(name: String): Boolean = name.endsWith(".exs")

    /** The directory of each content `mix.exs` file's `config_path:`. */
    private fun directories(project: Project): kotlin.collections.Set<VirtualFile> =
        projects(project).mapNotNullTo(mutableSetOf()) { (mixExs, entry) ->
            (entry.configPath as? ProjectKeywords.ConfigPath.Written)?.let { written ->
                mixExs.fileSystem.findFileByPath(PathUtil.getParentPath(configFilePath(mixExs, written)))?.takeIf { it.isDirectory }
            }
        }

    /** Where [written], [mixExs]'s `config_path:`, points: `Path.expand/2` of it against [mixExs]'s directory. */
    fun configFilePath(mixExs: VirtualFile, written: ProjectKeywords.ConfigPath.Written): String {
        val path = FileUtil.toSystemIndependentName(written.path)

        return FileUtil.toCanonicalPath(if (FileUtil.isAbsolutePlatformIndependent(path)) path else "${mixExs.parent.path}/$path")
    }

    /** The apps the content `mix.exs` files name. */
    @RequiresReadLock
    fun ownApps(project: Project): kotlin.collections.Set<String> = projects(project).mapNotNullTo(mutableSetOf()) { (_, entry) -> entry.app }

    /** Each app a content `mix.exs` file names, and the folders of the `mix.exs` files that name it. */
    @RequiresReadLock
    fun ownAppFolders(project: Project): Map<String, List<VirtualFile>> =
        projects(project)
            .mapNotNull { (mixExs, entry) -> entry.app?.let { app -> mixExs.parent?.let { app to it } } }
            .groupBy({ it.first }, { it.second })

    /** The config path [mixExs] names, `null` where [mixExs] is no content project's `mix.exs`. */
    @RequiresReadLock
    fun configPath(project: Project, mixExs: VirtualFile): ProjectKeywords.ConfigPath? =
        projects(project).firstOrNull { it.first == mixExs }?.second?.configPath

    /** Each content `mix.exs` file and its project entry. */
    fun projects(project: Project): List<Pair<VirtualFile, ConfigEntry.ProjectEntry>> {
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

    /** Stands for an interpolation's text in a path. */
    private const val INTERPOLATED = '\u0000'
    const val WILDCARD = "*?[{"
    private const val MIX = "Elixir.Mix"
    private val BUILD_NAMES = listOf("env", "target")
    private val CONFIG_BUILD_NAMES = setOf("config_env", "config_target")

    private class ExsScope(scope: GlobalSearchScope, private val directories: kotlin.collections.Set<VirtualFile>) :
        DelegatingGlobalSearchScope(scope) {
        override fun contains(file: VirtualFile): Boolean = counts(file.parent, file.name, directories)
    }
}
