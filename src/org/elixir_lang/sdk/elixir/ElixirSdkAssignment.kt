package org.elixir_lang.sdk.elixir

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.ui.EditorNotifications
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import org.elixir_lang.Facet
import org.elixir_lang.facet.Type as ElixirFacetType
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.sdk.ProcessOutput.isSmallIde

/** Writes a module's Elixir SDK pick where the running IDE reads it, which [ElixirSdkLookup.resolve] then finds. */
object ElixirSdkAssignment {
    /**
     * On a small IDE, [module]'s Elixir facet, added when absent, names [sdk]. On IntelliJ IDEA the table's SDK of that
     * name is written, because a pick may be a `ProjectSdksModel` copy: an `ELIXIR_MODULE` gets it as its module SDK,
     * and any other module as the project SDK, with its own Elixir entry, present or dangling, set to inherit.
     */
    @RequiresWriteLock
    fun assign(module: Module, sdk: Sdk) {
        if (isSmallIde) {
            val facetManager = FacetManager.getInstance(module)
            val facet = facetManager.getFacetByType(Facet.ID)
                ?: facetManager.addFacet(FacetType.findInstance(ElixirFacetType::class.java), "Elixir facet", null)
            facet.sdk = sdk
        } else {
            val tableSdk = checkNotNull(Facet.sdkNamed(sdk.name)) { "Elixir SDK '${sdk.name}' is not in the SDK table" }

            if (ModuleType.get(module).id == ELIXIR_MODULE_TYPE_ID) {
                ModuleRootModificationUtil.setModuleSdk(module, tableSdk)
            } else {
                ProjectRootManager.getInstance(module.project).projectSdk = tableSdk
                inheritOwnElixirEntry(module)
            }
        }

        EditorNotifications.getInstance(module.project).updateAllNotifications()
    }

    @RequiresWriteLock
    private fun inheritOwnElixirEntry(module: Module) {
        if (ElixirSdkLookup.ownElixirEntry(module) != null) {
            ModuleRootModificationUtil.setSdkInherited(module)
        }
    }
}
