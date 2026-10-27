package org.elixir_lang.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.elixir_lang.Module
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.model.psi.config.ConfigFiles
import org.elixir_lang.model.psi.config.ConfigReads
import org.elixir_lang.model.psi.config.Keys
import org.elixir_lang.model.psi.config.Set
import org.elixir_lang.model.psi.config.WrittenKey
import org.elixir_lang.psi.call.Call
import java.util.concurrent.ConcurrentHashMap

/**
 * A first-level key, written as an atom that names no module, that a counted config file sets for one of the project's
 * own apps, and that nothing reads. An app whose Mix project folder holds Erlang source is not judged, since its
 * Erlang can read its keys and no read written in Erlang is seen.
 */
// It reads the config, spellings and file name indexes, through `ConfigFiles`, `ConfigReads` and `FilenameIndex`.
@Suppress("CanBeDumbAware")
internal class UnreadConfigKey : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val project = holder.project
        val file = holder.file.virtualFile

        if (file == null || !ConfigFiles.scope(project).contains(file)) return PsiElementVisitor.EMPTY_VISITOR

        val judgedApps = judgedApps(project)
        val scope = GlobalSearchScope.allScope(project)
        // Gathered once per app for the pass. The visitor may run on several elements at once.
        val readKeys = ConcurrentHashMap<String, ConfigReads.ReadKeys>()

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is Call) return

                for (set in Set.of(element)) {
                    val key = (set.key as? WrittenKey.Atom)?.name ?: continue

                    if (set.app in judgedApps &&
                        Module.inspectedAlias(key) == null &&
                        key !in readKeys.getOrPut(set.app) { ConfigReads.readKeys(project, set.app, scope) }
                    ) {
                        holder.registerProblem(
                            set.element,
                            Keys.range(set.element),
                            "${InspectAtom.literal(set.app)}'s key ${InspectAtom.literal(key)} is set but never read"
                        )
                    }
                }
            }
        }
    }

    /** The own apps whose Mix project folders, less their `deps/` and `_build/`, hold no `.erl` file. */
    private fun judgedApps(project: Project): kotlin.collections.Set<String> {
        val erlangSources = FilenameIndex.getAllFilesByExt(project, "erl", GlobalSearchScope.projectScope(project))

        return ConfigFiles.ownAppFolders(project)
            .filterValues { folders -> folders.none { folder -> erlangSources.any { holds(folder, it) } } }
            .keys
    }

    private fun holds(folder: VirtualFile, file: VirtualFile): Boolean =
        VfsUtilCore.getRelativePath(file, folder)?.substringBefore('/')?.let { it != "deps" && it != "_build" } ?: false
}
