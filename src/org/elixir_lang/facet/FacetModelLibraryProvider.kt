package org.elixir_lang.facet

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.Icons
import java.util.Objects
import javax.swing.Icon

/** Each SDK the project's Elixir facets name, as one library under External Libraries. */
internal class FacetModelLibraryProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> =
        FacetModels.getInstance(project).named().map(::FacetModelLibrary)

    private class FacetModelLibrary(sdk: Sdk) :
        SyntheticLibrary("Elixir facet SDK ${sdk.name}", null),
        ItemPresentation {
        private val name = sdk.name
        private val binaryRoots: List<VirtualFile> = sdk.rootProvider.getFiles(OrderRootType.CLASSES).toList()
        private val sourceRoots: List<VirtualFile> = sdk.rootProvider.getFiles(OrderRootType.SOURCES).toList()

        override fun getBinaryRoots(): Collection<VirtualFile> = binaryRoots

        override fun getSourceRoots(): Collection<VirtualFile> = sourceRoots

        override fun getPresentableText(): String = name

        override fun getIcon(unused: Boolean): Icon = Icons.LANGUAGE

        override fun equals(other: Any?): Boolean =
            other is FacetModelLibrary &&
                name == other.name &&
                binaryRoots == other.binaryRoots &&
                sourceRoots == other.sourceRoots

        override fun hashCode(): Int = Objects.hash(name, binaryRoots, sourceRoots)
    }
}
