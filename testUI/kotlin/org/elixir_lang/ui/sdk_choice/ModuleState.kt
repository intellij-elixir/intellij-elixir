package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.Module
import com.intellij.driver.sdk.getModules
import com.intellij.driver.sdk.getOrderEntries
import org.elixir_lang.ui.framework.core.TableSdk

@Remote("com.intellij.openapi.roots.ModuleRootManager")
internal interface ModuleRootsRef {
    fun getInstance(module: Module): ModuleRootsRef
    fun getSdk(): TableSdk?
    fun isSdkInherited(): Boolean
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

/** The open project's module named [name], or its one module when no name is given. */
internal fun Driver.module(name: String? = null): Module = getModules().single { name == null || it.getName() == name }

/** The name of the SDK of the [module] named [name], its own or the project's it inherits; null when it has none. */
internal fun Driver.moduleSdk(name: String? = null): String? =
    withReadAction { utility(ModuleRootsRef::class).getInstance(module(name)).getSdk()?.getName() }

/** Whether the module inherits the project's SDK rather than naming its own. */
internal fun Driver.moduleInheritsSdk(): Boolean = withReadAction { utility(ModuleRootsRef::class).getInstance(module()).isSdkInherited() }

/** The module's dependencies as Project Structure names them: a library by its name, an SDK as `< name >`. */
internal fun Driver.dependencyNames(): List<String> = withReadAction { getOrderEntries(module()).map { it.getPresentableName() } }

/** The SDK name the module's one facet, the Elixir facet, stores. */
internal fun Driver.facetSdkName(): String? = withReadAction {
    val facet = utility(FacetManagerRef::class).getInstance(module()).getAllFacets().single()
    cast(facet.getConfiguration(), ElixirFacetConfigurationRef::class).getSdkName()
}
