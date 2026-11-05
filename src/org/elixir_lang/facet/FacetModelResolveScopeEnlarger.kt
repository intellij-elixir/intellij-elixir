package org.elixir_lang.facet

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ResolveScopeEnlarger
import com.intellij.psi.search.SearchScope

/** A module file resolves into the SDK its module's Elixir facet names. */
internal class FacetModelResolveScopeEnlarger : ResolveScopeEnlarger() {
    override fun getAdditionalResolveScope(file: VirtualFile, project: Project): SearchScope? =
        ProjectFileIndex.getInstance(project).getModuleForFile(file)?.let(FacetModels.getInstance(project)::scope)
}
