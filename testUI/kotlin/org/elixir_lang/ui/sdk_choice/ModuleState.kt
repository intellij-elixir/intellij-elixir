package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.Module
import com.intellij.driver.sdk.getModules
import com.intellij.driver.sdk.getOrderEntries
import org.elixir_lang.ui.framework.core.TableSdk

@Remote("com.intellij.openapi.roots.ModuleRootManager")
internal interface ModuleRootsRef {
    fun getInstance(module: Module): ModuleRootsRef
    fun getSdk(): TableSdk?
}

@Remote("com.intellij.facet.FacetManager")
internal interface FacetManagerRef {
    fun getInstance(module: Module): FacetManagerRef
    fun getAllFacets(): Array<FacetRef>
}

@Remote("com.intellij.facet.Facet")
internal interface FacetRef {
    fun getConfiguration(): FacetConfigurationRef
}

@Remote("com.intellij.facet.FacetConfiguration")
internal interface FacetConfigurationRef

@Remote("org.elixir_lang.facet.Configuration", plugin = "org.elixir_lang")
internal interface ElixirFacetConfigurationRef {
    fun getSdkName(): String?
}

/** The open project's one module. */
internal fun Driver.module(): Module = getModules().single()

/** The name of the module's SDK, its own or the project's it inherits; null when it has none. */
internal fun Driver.moduleSdk(): String? = withReadAction { utility(ModuleRootsRef::class).getInstance(module()).getSdk()?.getName() }

/** The module's dependencies as Project Structure names them: a library by its name, an SDK as `< name >`. */
internal fun Driver.dependencyNames(): List<String> = withReadAction { getOrderEntries(module()).map { it.getPresentableName() } }

/** The SDK name the module's one facet, the Elixir facet, stores. */
internal fun Driver.facetSdkName(): String? = withReadAction {
    val facet = utility(FacetManagerRef::class).getInstance(module()).getAllFacets().single()
    cast(facet.getConfiguration(), ElixirFacetConfigurationRef::class).getSdkName()
}
