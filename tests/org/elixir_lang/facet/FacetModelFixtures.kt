package org.elixir_lang.facet

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.runAll
import org.elixir_lang.Facet
import org.elixir_lang.sdk.ProcessOutput
import org.jdom.Element
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType

/** Elixir SDKs and facets naming them, set up the way the small IDEs store them. */
internal class FacetModelFixtures {
    private val added = mutableListOf<Sdk>()

    /** An Elixir SDK in the table with [classes] as its `CLASSES` roots and [sources] as its `SOURCES` roots. */
    fun register(name: String, classes: List<VirtualFile> = emptyList(), sources: List<VirtualFile> = emptyList()): Sdk {
        val sdk = ProjectJdkImpl(name, ElixirSdkType.instance)

        WriteAction.run<Throwable> {
            sdk.sdkModificator.apply {
                classes.forEach { addRoot(it, OrderRootType.CLASSES) }
                sources.forEach { addRoot(it, OrderRootType.SOURCES) }
                commitChanges()
            }
            ProjectJdkTable.getInstance().addJdk(sdk)
        }

        added.add(sdk)
        SdksService.getInstance()!!.resetForTests()

        return sdk
    }

    fun remove(sdk: Sdk) {
        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(sdk) }
        SdksService.getInstance()!!.resetForTests()
    }

    /** Removes every SDK [register] added that is still in the table. */
    fun removeAll() {
        WriteAction.run<Throwable> {
            val table = ProjectJdkTable.getInstance()
            added.filter { table.allJdks.contains(it) }.forEach { table.removeJdk(it) }
        }
        added.clear()
        SdksService.getInstance()!!.resetForTests()
    }

    /** [removeAll], then lets the deferred roots change run while still a small IDE, then stops being one. */
    fun leaveSmallIde(project: Project) {
        runAll(
            { removeAll() },
            { settle(project) },
            { ProcessOutput.isSmallIdeOverride = null },
        )
    }

    companion object {
        /** Lets the deferred roots change run, and the indexing it starts finish. */
        fun settle(project: Project) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }

        fun facet(module: Module): Facet =
            FacetManager.getInstance(module).getFacetByType(Facet.ID)
                ?: WriteAction.computeAndWait<Facet, Throwable> {
                    FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java))
                }

        /** Stores [name] in [module]'s facet as a saved `.iml` would hold it, with no library. */
        fun nameFacet(module: Module, name: String) {
            val facet = facet(module)
            val saved = Element("configuration").setAttribute("sdkName", name)
            FacetUtil.loadFacetConfiguration(facet.configuration, saved)
            WriteAction.runAndWait<Throwable> { FacetManager.getInstance(module).facetConfigurationChanged(facet) }
        }

        /** The name [module]'s facet would save, or null when it saves none. */
        fun storedName(module: Module): String? =
            FacetManager.getInstance(module).getFacetByType(Facet.ID)
                ?.let { FacetUtil.saveFacetConfiguration(it.configuration) }
                ?.getAttributeValue("sdkName")

        fun assign(module: Module, sdk: Sdk) {
            val facet = facet(module)
            WriteAction.runAndWait<Throwable> { facet.sdk = sdk }
        }
    }
}
