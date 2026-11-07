package org.elixir_lang.tool_manager

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.common.runAll
import junit.framework.TestCase.assertTrue
import org.elixir_lang.Facet
import org.elixir_lang.facet.FacetModelFixtures
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.SdkFixtures.storedErlangHome
import org.elixir_lang.sdk.SdkRegistrar
import org.elixir_lang.sdk.choice.RecordedChooser
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.sdk.wsl.wslCompat

/** The table's Elixir SDKs at [home]. */
internal fun elixirSdksAt(home: String): List<Sdk> = ProjectJdkTable.getInstance().allJdks.filter { SdkRegistrar.onInstall(it, home) }

/** Asserts that [sdk] is paired with the Erlang SDK at [erlangHome]. */
internal fun assertPairedWithErlangAt(sdk: Sdk, erlangHome: String) =
    assertTrue("paired with the Erlang at $erlangHome: ${storedErlangHome(sdk)}", wslCompat.pathsEqualWslAware(storedErlangHome(sdk), erlangHome))

/** An Erlang SDK named [name] at [home], in the table. */
internal fun registeredErlang(name: String, home: String): Sdk = SdkFixtures.register(SdkFixtures.erlangSdk(name, home))

/** An Elixir SDK named [name] at [elixirHome], in the table, paired with [erlang]. */
internal fun pairedElixir(name: String, elixirHome: String, erlang: Sdk): Sdk =
    SdkFixtures.commitPairing(SdkFixtures.register(SdkFixtures.elixirSdk(name, elixirHome)), erlang)

/** Two Elixir SDKs at [elixirHome], named from [prefix], each paired with an Erlang SDK of its own. */
internal fun twoSdksOnTheInstall(elixirHome: String, prefix: String): List<Sdk> =
    listOf(install("27", "27.3.4"), install("26", "26.2.5")).mapIndexed { index, erlangHome ->
        pairedElixir("$prefix Elixir $index", elixirHome, registeredErlang("$prefix Erlang $index", erlangHome))
    }

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

    private fun assign(sdk: Sdk) = WriteAction.runAndWait<Throwable> { checker.assignElixirSdk(module, sdk) }

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

        assign(after)

        assertProjectSdkInherited(after)
        assertEquals(before, facet.sdk)
    }

    fun testAModuleWithoutAnElixirFacetIsGivenNone() {
        val after = sdk("after")

        assign(after)

        assertProjectSdkInherited(after)
        assertNull(FacetManager.getInstance(module).getFacetByType(Facet.ID))
    }

    private fun projectJdk(): Sdk {
        val jdk = SdkFixtures.register(ProjectJdkImpl("Tool manager JDK", JavaSdk.getInstance()), testRootDisposable)
        WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = jdk }
        return jdk
    }

    private fun inheritingModule(name: String): Module = createModule(name).also { ModuleRootModificationUtil.setSdkInherited(it) }

    fun testAModuleInheritingAProjectJdkKeepsIt() {
        val jdk = projectJdk()
        val java = inheritingModule("Tool manager Java module")
        val pick = sdk("pick")

        assign(pick)

        assertProjectSdkInherited(pick)
        assertSame(jdk, ModuleRootManager.getInstance(java).sdk)
    }

    fun testAnElixirModuleInheritingAProjectJdkGetsThePick() {
        projectJdk()
        val elixir = inheritingModule("Tool manager facet module")
        FacetModelFixtures.facet(elixir)
        val pick = sdk("pick")

        assign(pick)

        assertSame(pick, ModuleRootManager.getInstance(elixir).sdk)
    }

    fun testWithNoProjectSdkNoOtherModuleIsGivenAnEntry() {
        val other = inheritingModule("Tool manager other module")
        val pick = sdk("pick")

        assign(pick)

        assertProjectSdkInherited(pick)
        assertTrue(ModuleRootManager.getInstance(other).isSdkInherited)
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
