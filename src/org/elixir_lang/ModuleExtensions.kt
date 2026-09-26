package org.elixir_lang

import com.intellij.facet.FacetManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresReadLock
import kotlin.collections.List
import kotlin.collections.emptyList
import kotlin.collections.isNotEmpty
import kotlin.collections.mapNotNull
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID

data class MixContentRoot(
    val contentEntry: ContentEntry,
    val root: VirtualFile,
)

fun Module.isElixirModule(): Boolean {
    // Work queued before a module is removed or its project closes can still hold it, and a disposed module's
    // FacetManager logs an error.
    if (isDisposed) return false

    val isElixirModuleType = ModuleType.get(this).id == ELIXIR_MODULE_TYPE_ID
    val hasElixirFacet = FacetManager.getInstance(this).getFacetByType(Facet.ID) != null
    return isElixirModuleType || hasElixirFacet
}

@RequiresReadLock
fun Module.mixContentRoots(): List<MixContentRoot> {
    if (!isElixirModule()) return emptyList()

    return ModuleRootManager.getInstance(this).contentEntries.mapNotNull { entry ->
        val root = entry.file ?: return@mapNotNull null
        if (root.findChild("mix.exs") == null) return@mapNotNull null
        MixContentRoot(entry, root)
    }
}

@RequiresReadLock
fun Module.isElixirMixModule(): Boolean = mixContentRoots().isNotEmpty()
