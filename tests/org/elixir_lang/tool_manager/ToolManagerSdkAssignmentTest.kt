package org.elixir_lang.tool_manager

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import org.elixir_lang.Facet
import org.elixir_lang.facet.FacetModelFixtures
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome

class ToolManagerSdkAssignmentTest : HeavyTestCase() {
    private val checker get() = ToolManagerSdkChecker(project, emptyList(), ToolManagerSettings())

    override fun setUp() {
        super.setUp()
        // As a module IntelliJ IDEA creates; a test module has no SDK entry.
        ModuleRootModificationUtil.setSdkInherited(module)
    }

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
}
