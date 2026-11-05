package org.elixir_lang.reference.resolver

import com.intellij.codeInsight.daemon.SyntheticPsiFileSupport
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.module.impl.scopes.JdkScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.impl.LibraryScopeCache
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.facet.FacetModels
import org.jetbrains.annotations.VisibleForTesting

/**
 * The [VirtualFile] that should drive module / scope selection for [element]: for a synthetic file, the on-disk file
 * it shows when that can be recovered, and otherwise the synthetic file itself.
 *
 * For normal files this is just `containingFile.originalFile.virtualFile`.  Files shown in the
 * commit / VCS diff view are backed by a synthetic [com.intellij.testFramework.LightVirtualFile]
 * which is not part of any module, so [ModuleUtil.findModuleForFile] returns null for it.  Depending
 * on whether the view provider's event system is enabled, that light file surfaces through either
 * `originalFile.virtualFile` or `viewProvider.virtualFile`; in both cases we map it back to the real
 * file via [recoverLocalFile].
 */
@VisibleForTesting
@RequiresReadLock
internal fun effectiveVirtualFile(element: PsiElement): VirtualFile? {
    // An injected fragment's own light file is never marked; its host's file may be.
    val containingFile = InjectedLanguageManager.getInstance(element.project).getTopLevelFile(element) ?: return null

    val backingFile = containingFile.originalFile.virtualFile
        ?: containingFile.viewProvider.virtualFile

    return recoverLocalFile(backingFile) ?: backingFile
}

/** The IDE module of [element], or of [virtualFile], its [effectiveVirtualFile], where [element] itself is in none. */
@RequiresReadLock
internal fun moduleOf(element: PsiElement, virtualFile: VirtualFile? = effectiveVirtualFile(element)): Module? =
    ModuleUtil.findModuleForPsiElement(element)
        ?: virtualFile?.let { ModuleUtil.findModuleForFile(it, element.project) }

/**
 * Maps a synthetic diff / VCS [file] back to its real on-disk [VirtualFile], or null when [file] is
 * already a real local file (or nothing can be recovered).
 *
 * The diff platform marks these [com.intellij.testFramework.LightVirtualFile]s via
 * [SyntheticPsiFileSupport.markFile] with the original file URL.
 */
private fun recoverLocalFile(file: VirtualFile): VirtualFile? {
    if (file.isInLocalFileSystem) return null

    return SyntheticPsiFileSupport.getOriginalFileUrl(file)?.let { VirtualFileManager.getInstance().findFileByUrl(it) }
}

/**
 * Builds a search scope that covers the module owning [element], its transitive
 * dependencies, and the SDK + non-SDK library sources attached to that specific module.
 *
 * Falls back to [GlobalSearchScope.allScope] when no module can be determined
 * (e.g. scratches).
 *
 * Elixir stubs are indexed from .ex source files (not .beam class files), so
 * defmodule declarations live in SDK and library source roots.
 * [GlobalSearchScope.moduleWithDependenciesAndLibrariesScope] only covers class roots
 * for library dependencies ([LibraryOrderEntry]), so we must explicitly unite with:
 *   - SDK source roots via [LibraryScopeCache.getScopeForSdk] per [JdkOrderEntry]
 *   - Non-SDK library source roots via [JdkScope] over each [LibraryOrderEntry]'s
 *     [OrderRootType.SOURCES] roots (e.g. `deps/ecto/lib`)
 *   - On a small IDE, the roots of the SDK the module's Elixir facet names ([FacetModels.scope])
 *
 * Using the module's own root model (not
 * [com.intellij.openapi.roots.ProjectFileIndex.getOrderEntriesForFile]) keeps the
 * scope scoped to this module's dependencies only, preventing spurious "Choose Declaration"
 * results from sibling modules that share a module name but use a different SDK or library version.
 */
@RequiresReadLock
fun narrowedScope(element: PsiElement, project: Project): GlobalSearchScope {
    val virtualFile = effectiveVirtualFile(element)

    val module = moduleOf(element, virtualFile) ?: return GlobalSearchScope.allScope(project)

    val projectFileIndex = ProjectRootManager.getInstance(project).fileIndex
    val includeTests = virtualFile?.let { projectFileIndex.isInTestSourceContent(it) } ?: false

    val orderEntries = ModuleRootManager.getInstance(module).orderEntries

    val moduleScope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, includeTests)

    val sdkSourceScope = orderEntries
        .filterIsInstance<JdkOrderEntry>()
        .fold(GlobalSearchScope.EMPTY_SCOPE as GlobalSearchScope) { acc, entry ->
            acc.uniteWith(LibraryScopeCache.getInstance(project).getScopeForSdk(entry))
        }

    // moduleWithDependenciesAndLibrariesScope covers library CLASS roots (ebin/) but not SOURCE
    // roots (deps/<lib>/lib/).  Without this, multiResolveProject finds only the beam ModuleImpl
    // and never the source defmodule, causing navigation to land on the decompiled .beam file
    // instead of the .ex source even when source roots are attached to the library.
    val librarySourceScope = orderEntries
        .filterIsInstance<LibraryOrderEntry>()
        .fold(GlobalSearchScope.EMPTY_SCOPE as GlobalSearchScope) { acc, entry ->
            val sourceRoots = entry.getRootFiles(OrderRootType.SOURCES)
            if (sourceRoots.isEmpty()) acc
            else acc.uniteWith(JdkScope(project, emptyArray(), sourceRoots, entry.libraryName))
        }

    val facetSdkScope = FacetModels.getInstance(project).scope(module) ?: GlobalSearchScope.EMPTY_SCOPE

    return moduleScope.uniteWith(sdkSourceScope).uniteWith(librarySourceScope).uniteWith(facetSdkScope)
}
