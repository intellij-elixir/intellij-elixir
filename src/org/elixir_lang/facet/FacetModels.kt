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
import com.intellij.openapi.module.impl.scopes.JdkScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
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

    /** The modules whose facet names an SDK with a root holding [file]. */
    @RequiresReadLock
    fun modulesHolding(file: VirtualFile): List<Module> =
        facetModules()
            .groupBy(ElixirSdkLookup::facetSdk)
            .filterKeys { sdk -> sdk != null && sdkScope(sdk).contains(file) }
            .values
            .flatten()

    /** The roots of the SDK [module]'s facet names, which none of its order entries carries. */
    @RequiresReadLock
    fun scope(module: Module): GlobalSearchScope? = ElixirSdkLookup.facetSdk(module)?.let { sdkScope(it) }

    /** [scope]'s roots, as its CLASSES and its SOURCES. */
    @RequiresReadLock
    fun roots(module: Module): Pair<Array<VirtualFile>, Array<VirtualFile>>? = ElixirSdkLookup.facetSdk(module)?.let { roots(it) }

    private fun roots(sdk: Sdk): Pair<Array<VirtualFile>, Array<VirtualFile>> =
        sdk.rootProvider.getFiles(OrderRootType.CLASSES) to sdk.rootProvider.getFiles(OrderRootType.SOURCES)

    private fun sdkScope(sdk: Sdk): GlobalSearchScope = roots(sdk).let { (classes, sources) -> JdkScope(project, classes, sources, sdk.name) }

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

    /** Stores [sdk]'s new name in every facet that named it by [previousName], so they go on supplying it. */
    @RequiresWriteLock
    private fun sdkRenamed(sdk: Sdk, previousName: String) {
        for (facet in ProjectFacetManager.getInstance(project).getFacets(Facet.ID)) {
            if (ElixirSdkLookup.facetSdkName(facet.module) == previousName) facet.sdk = sdk
        }
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
        override fun jdkAdded(jdk: Sdk) = ifElixir(jdk) {
            sdkChanged(jdk)
            StandInMigration.rerun(project)
        }

        override fun jdkRemoved(jdk: Sdk) = ifElixir(jdk) { sdkChanged(jdk) }

        override fun jdkNameChanged(jdk: Sdk, previousName: String) = ifElixir(jdk) {
            sdkRenamed(jdk, previousName)
            sdkChanged(jdk)
            StandInMigration.rerun(project)
        }

        private inline fun ifElixir(jdk: Sdk, block: FacetModels.() -> Unit) {
            if (jdk.sdkType === ElixirSdkType.instance) getInstance(project).block()
        }
    }

    companion object {
        fun getInstance(project: Project): FacetModels = project.service()
    }
}
