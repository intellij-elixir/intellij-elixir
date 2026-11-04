package org.elixir_lang

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.FacetTypeId
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTable
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import org.elixir_lang.facet.Configuration

class Facet(
    facetType: FacetType<*, *>,
    module: Module,
    name: String,
    configuration: Configuration,
    underlyingFacet: com.intellij.facet.Facet<*>?
) : com.intellij.facet.Facet<Configuration>(facetType, module, name, configuration, underlyingFacet) {
    var sdk: Sdk?
        get() = configuration.sdkName?.let(::sdkNamed)
        @RequiresWriteLock
        set(value) {
            configuration.sdkName = value?.name
            FacetManager.getInstance(module).facetConfigurationChanged(this)

            val modifiableModuleRootManger = module.rootManager.modifiableModel
            val libraryTable = modifiableModuleRootManger.moduleLibraryTable
            removeElixirSDKs(libraryTable)

            if (value != null) {
                addElixirSDK(libraryTable, value)
            }

            modifiableModuleRootManger.commit()
        }

    companion object {
        val ID = FacetTypeId<Facet>("elixir")

        fun sdk(module: Module): Sdk? = FacetManager.getInstance(module).getFacetByType(ID)?.sdk

        private fun removeElixirSDKs(moduleLibraryTable: LibraryTable) {
            sdks()
                .map { it.name }
                .mapNotNull { name ->
                    moduleLibraryTable.getLibraryByName(name)
                }
                .forEach { library ->
                    moduleLibraryTable.removeLibrary(library)
                }
        }

        /**
         * The module library that stands in for [sdk] on a small IDE, carrying the SDK's own roots.
         *
         * Both root types are copied. `CLASSES` covers the compiled `ebin` directories, but Elixir's
         * stubs are indexed from `.ex` sources rather than `.beam`, so `defmodule` declarations for
         * the standard library live under `SOURCES` - without those, an assigned SDK still completes
         * nothing.
         */
        private fun addElixirSDK(moduleLibraryTable: LibraryTable, sdk: Sdk) {
            moduleLibraryTable.createLibrary(sdk.name).modifiableModel.apply {
                for (rootType in ROOT_TYPES) {
                    sdk.rootProvider.getUrls(rootType).forEach { addRoot(it, rootType) }
                }

                commit()
            }
        }

        /** The SDK root types carried onto the module library, in the order they are copied. */
        private val ROOT_TYPES = listOf(OrderRootType.CLASSES, OrderRootType.SOURCES)

        fun sdks(): kotlin.collections.List<Sdk> =
            ProjectJdkTable
                .getInstance()
                .getSdksOfType(org.elixir_lang.sdk.elixir.Type.instance)

        private fun sdkNamed(name: String): Sdk? = sdks().firstOrNull { it.name == name }
    }
}
