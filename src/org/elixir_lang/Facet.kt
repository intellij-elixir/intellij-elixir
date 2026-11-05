package org.elixir_lang

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.FacetTypeId
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
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
        }

    companion object {
        val ID = FacetTypeId<Facet>("elixir")

        fun sdk(module: Module): Sdk? = FacetManager.getInstance(module).getFacetByType(ID)?.sdk

        fun sdks(): kotlin.collections.List<Sdk> =
            ProjectJdkTable
                .getInstance()
                .getSdksOfType(org.elixir_lang.sdk.elixir.Type.instance)

        /** The registered Elixir SDK named [name]. */
        fun sdkNamed(name: String): Sdk? = sdks().firstOrNull { it.name == name }
    }
}
