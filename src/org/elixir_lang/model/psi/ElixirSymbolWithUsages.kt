package org.elixir_lang.model.psi

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.annotations.RequiresReadLock

/**
 * An [ElixirSymbol] that can be searched for. [ElixirSymbolUsageSearcher] dispatches on this type.
 *
 * @property file the file containing the declaring occurrence
 * @property range absolute range (in [file]) of the declaring occurrence, used for navigation and
 *   the self-declaration usage
 * @property searchText the bare name to anchor a text search on (e.g. `handle_call`)
 */
interface ElixirSymbolWithUsages : ElixirSymbol {
    val file: PsiFile
    val range: TextRange
    val searchText: String

    /**
     * The compiled file this symbol is declared in, or `null` for source. [file] is either that file or, once a
     * pointer has restored the symbol from the decompiled mirror, the mirror, whose `originalFile` is that file.
     */
    val compiledFile: PsiCompiledFile?
        get() = file as? PsiCompiledFile ?: file.originalFile as? PsiCompiledFile

    /**
     * Whether [file] is in a library or SDK and not under a content root, so the project does not own it: an SDK's
     * sources, a dependency under an excluded `deps/`, a `path:` dependency outside the project. A file with no
     * [com.intellij.openapi.vfs.VirtualFile] is not.
     */
    @get:RequiresReadLock
    val declaredInLibrary: Boolean
        get() {
            val virtualFile = file.originalFile.virtualFile ?: return false
            val index = ProjectFileIndex.getInstance(file.project)

            return index.isInLibrary(virtualFile) && !index.isInContent(virtualFile)
        }
}
