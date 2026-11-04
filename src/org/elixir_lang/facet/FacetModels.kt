package org.elixir_lang.facet

import com.intellij.facet.ProjectFacetListener
import com.intellij.facet.ProjectFacetManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import org.elixir_lang.Facet
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Elixir SDKs the project's facets name, which supply their roots to the project as libraries, and the one place a
 * change to them becomes a roots change.
 */
@Service(Service.Level.PROJECT)
class FacetModels(private val project: Project) : Disposable {
    private val scheduled = AtomicBoolean()

    /** Each named SDK's root-set listener's disposable, by identity: a re-added SDK is a new [Sdk]. Write lock only. */
    private val watched = IdentityHashMap<Sdk, Disposable>()

    /** The SDKs the project's facets name, one for each name. */
    @RequiresReadLock
    fun named(): List<Sdk> = facetModules().mapNotNull(ElixirSdkLookup::facetSdk).distinctBy { it.name }

    private fun facetModules(): List<Module> = ProjectFacetManager.getInstance(project).getModulesWithFacet(Facet.ID)

    /**
     * Schedules one roots change, so that the named SDKs' roots are read again, whatever lock the caller holds: a facet
     * added at project open is reported on a background thread with none.
     */
    private fun rootsChanged() {
        if (!scheduled.compareAndSet(false, true)) return

        ApplicationManager.getApplication().invokeLater(
            {
                scheduled.set(false)
                WriteAction.run<Throwable> {
                    val named = named()
                    // Nothing named now or at the last change, as always on IntelliJ IDEA: no root has changed.
                    if (named.isEmpty() && watched.isEmpty()) return@run
                    watch(named)
                    ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(
                        EmptyRunnable.getInstance(),
                        RootsChangeRescanningInfo.RESCAN_DEPENDENCIES_IF_NEEDED,
                    )
                }
            },
            ModalityState.nonModal(),
            project.disposed,
        )
    }

    /** Schedules a roots change for an Elixir SDK added, removed or renamed that is or was named. */
    @RequiresWriteLock
    private fun sdkChanged(sdk: Sdk) {
        if (watched.containsKey(sdk) || named().any { it === sdk }) rootsChanged()
    }

    @RequiresWriteLock
    private fun watch(named: List<Sdk>) {
        watched.keys.filter { old -> named.none { it === old } }.forEach { Disposer.dispose(watched.remove(it)!!) }

        for (sdk in named) {
            if (watched.containsKey(sdk)) continue

            val disposable = Disposer.newDisposable(this, "Elixir facet SDK ${sdk.name}")
            sdk.rootProvider.addRootSetChangedListener({ rootsChanged() }, disposable)
            watched[sdk] = disposable
        }
    }

    override fun dispose() {}

    /** An Elixir facet added, removed or reconfigured. */
    internal class FacetListener : ProjectFacetListener<Facet> {
        override fun facetAdded(facet: Facet) = getInstance(facet.module.project).rootsChanged()

        override fun facetRemoved(facet: Facet, project: Project) = getInstance(project).rootsChanged()

        override fun facetConfigurationChanged(facet: Facet) = getInstance(facet.module.project).rootsChanged()
    }

    /** An Elixir SDK added, removed or renamed. */
    internal class SdkListener(private val project: Project) : ProjectJdkTable.Listener {
        override fun jdkAdded(jdk: Sdk) = ifElixir(jdk) { sdkChanged(jdk) }

        override fun jdkRemoved(jdk: Sdk) = ifElixir(jdk) { sdkChanged(jdk) }

        override fun jdkNameChanged(jdk: Sdk, previousName: String) = ifElixir(jdk) { sdkChanged(jdk) }

        private inline fun ifElixir(jdk: Sdk, block: FacetModels.() -> Unit) {
            if (jdk.sdkType === ElixirSdkType.instance) getInstance(project).block()
        }
    }

    companion object {
        fun getInstance(project: Project): FacetModels = project.service()
    }
}
