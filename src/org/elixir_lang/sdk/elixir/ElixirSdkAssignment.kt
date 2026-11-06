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
    /** Where [assign] writes a module's Elixir SDK pick. */
    enum class Target { FACET, MODULE_SDK, PROJECT_SDK }

    /** On a small IDE, [module]'s Elixir facet; on IntelliJ IDEA, an `ELIXIR_MODULE`'s module SDK, else the project SDK. */
    fun target(module: Module): Target = when {
        isSmallIde -> Target.FACET
        ModuleType.get(module).id == ELIXIR_MODULE_TYPE_ID -> Target.MODULE_SDK
        else -> Target.PROJECT_SDK
    }

    /**
     * Writes [sdk] at [module]'s [target]. A facet, added as "Elixir facet" when absent, names it. A module or project
     * SDK is the table's SDK of that name, because a pick may be a `ProjectSdksModel` copy, and a module given the project
     * SDK has its own Elixir entry, present or dangling, set to inherit.
     */
    @RequiresWriteLock
    fun assign(module: Module, sdk: Sdk) {
        when (target(module)) {
            Target.FACET -> {
                val facetManager = FacetManager.getInstance(module)
                val facet = facetManager.getFacetByType(Facet.ID)
                    ?: facetManager.addFacet(FacetType.findInstance(ElixirFacetType::class.java), "Elixir facet", null)
                facet.sdk = sdk
            }
            Target.MODULE_SDK -> ModuleRootModificationUtil.setModuleSdk(module, tableSdk(sdk))
            Target.PROJECT_SDK -> {
                ProjectRootManager.getInstance(module.project).projectSdk = tableSdk(sdk)
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

    private fun tableSdk(sdk: Sdk): Sdk =
        checkNotNull(Facet.sdkNamed(sdk.name)) { "Elixir SDK '${sdk.name}' is not in the SDK table" }
}
