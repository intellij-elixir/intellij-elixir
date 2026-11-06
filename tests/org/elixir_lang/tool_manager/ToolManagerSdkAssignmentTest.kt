package org.elixir_lang.tool_manager

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.common.runAll
import org.elixir_lang.Facet
import org.elixir_lang.facet.FacetModelFixtures
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.SdkRegistrar
import org.elixir_lang.sdk.choice.RecordedChooser
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install

/** The table's Elixir SDKs at [home]. */
internal fun elixirSdksAt(home: String): List<Sdk> = ProjectJdkTable.getInstance().allJdks.filter { SdkRegistrar.onInstall(it, home) }

class ToolManagerSdkAssignmentTest : HeavyTestCase() {
    private val checker get() = ToolManagerSdkChecker(project, emptyList(), ToolManagerSettings())
    private lateinit var table: SdkFixtures.TableSnapshot

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        // As a module IntelliJ IDEA creates; a test module has no SDK entry.
        ModuleRootModificationUtil.setSdkInherited(module)
    }

    override fun tearDown() = runAll({ table.restore() }, { super.tearDown() })

    private fun sdk(name: String) = SdkFixtures.register(SdkFixtures.elixirSdk(name, fakeHome("elixir/$name")), testRootDisposable)

    /** This module is no `ELIXIR_MODULE`, so the pick is the project SDK, which the module inherits. */
    private fun assertProjectSdkInherited(sdk: Sdk) {
        assertEquals(sdk, ProjectRootManager.getInstance(project).projectSdk)
        assertTrue(ModuleRootManager.getInstance(module).isSdkInherited)
    }

    /** IntelliJ IDEA never reads a facet a small IDE left, so the facet is not touched. */
    fun testAnElixirFacetTheModuleAlreadyHasIsLeftAlone() {
        val facet = FacetModelFixtures.facet(module)
        val before = sdk("before")
        val after = sdk("after")
        FacetModelFixtures.assign(module, before)

        WriteAction.runAndWait<Throwable> { checker.assignElixirSdk(module, after) }

        assertProjectSdkInherited(after)
        assertEquals(before, facet.sdk)
    }

    fun testAModuleWithoutAnElixirFacetIsGivenNone() {
        val after = sdk("after")

        WriteAction.runAndWait<Throwable> { checker.assignElixirSdk(module, after) }

        assertProjectSdkInherited(after)
        assertNull(FacetManager.getInstance(module).getFacetByType(Facet.ID))
    }

    /** Two modules that read the one project SDK, pinned to different installs: which is meant can't be told. */
    fun testModulesPinnedToDifferentInstallsAskTheChooserOnce() {
        val other = createModule("Tool manager picked pin")
        val (homes, pins) = pinnedToTwoInstalls(other)
        val recorded = RecordedChooser(testRootDisposable) { state -> state.entries.single { SdkRegistrar.onInstall(it.sdk, homes[1]) } }

        checker.configureSdks(pins)

        assertEquals(listOf(listOf(module, other)), recorded.chooserAsked)
        assertProjectSdkInherited(elixirSdksAt(homes[1]).single())
    }

    fun testModulesPinnedToDifferentInstallsLeaveTheProjectSdkWhenTheChooserIsCancelled() {
        RecordedChooser(testRootDisposable)
        val (homes, pins) = pinnedToTwoInstalls(createModule("Tool manager other pin"))

        checker.configureSdks(pins)

        assertNull(ProjectRootManager.getInstance(project).projectSdk)
        homes.forEach { home -> assertEquals(home, 1, elixirSdksAt(home).size) }
    }

    fun testModulesPinnedToOneInstallSetTheProjectSdk() {
        val recorded = RecordedChooser(testRootDisposable)
        val other = createModule("Tool manager same pin")
        val home = elixirInstall("1.18.4", "27")
        val pin = pin(home, install("27", "27.3.4"))

        checker.configureSdks(linkedMapOf(module.name to pin, other.name to pin))

        assertProjectSdkInherited(elixirSdksAt(home).single())
        assertEmpty(recorded.chooserAsked)
    }

    /** Two installs' homes, and [module] and [other] each pinned to one of them. */
    private fun pinnedToTwoInstalls(other: Module): Pair<List<String>, Map<String, ToolManagerVersions>> {
        val homes = listOf(elixirInstall("1.18.4", "27"), elixirInstall("1.17.3", "26"))
        return homes to linkedMapOf(module.name to pin(homes[0], install("27", "27.3.4")), other.name to pin(homes[1], install("26", "26.2.5")))
    }

    private fun pin(elixirHome: String, erlangHome: String): ToolManagerVersions =
        versions(elixir = ToolEntry("pinned", elixirHome, true), erlang = ToolEntry("pinned", erlangHome, true))
}
