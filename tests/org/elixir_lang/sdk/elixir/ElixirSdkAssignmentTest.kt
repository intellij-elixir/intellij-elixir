package org.elixir_lang.sdk.elixir

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.ModuleJdkOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import org.elixir_lang.Facet
import org.elixir_lang.facet.FacetModelFixtures
import org.elixir_lang.facet.SmallIdeFacetHeavyTestCase
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome

private fun jdkEntries(module: Module): List<JdkOrderEntry> =
    ModuleRootManager.getInstance(module).orderEntries.filterIsInstance<JdkOrderEntry>()

/** IntelliJ IDEA: an `ELIXIR_MODULE` gets the pick as its module SDK, any other module as the project SDK. */
class ElixirSdkAssignmentTest : HeavyTestCase() {
    private fun sdk(name: String): Sdk =
        SdkFixtures.register(SdkFixtures.elixirSdk(name, fakeHome("elixir/$name")), testRootDisposable)

    private fun jdk(name: String): Sdk = SdkFixtures.register(ProjectJdkImpl(name, JavaSdk.getInstance()), testRootDisposable)

    private fun elixirModule(): Module =
        doCreateRealModuleIn("elixir", project, ModuleTypeManager.getInstance().findByID(ELIXIR_MODULE_TYPE_ID))

    private fun assign(module: Module, sdk: Sdk) = WriteAction.runAndWait<Throwable> { ElixirSdkAssignment.assign(module, sdk) }

    private val projectSdk: Sdk? get() = ProjectRootManager.getInstance(project).projectSdk

    /** [pick] is the project SDK, and [module] inherits it. */
    private fun assertProjectSdkInherited(pick: Sdk) {
        assertSame(pick, projectSdk)
        assertTrue(ModuleRootManager.getInstance(module).isSdkInherited)
    }

    private fun ownEntry(module: Module): ModuleJdkOrderEntry? =
        ModuleRootManager.getInstance(module).orderEntries.filterIsInstance<ModuleJdkOrderEntry>().singleOrNull()

    fun testAnElixirModuleGetsThePickAsItsModuleSdk() {
        val module = elixirModule()
        val pick = sdk("pick")

        assign(module, pick)

        assertSame(pick, ownEntry(module)?.jdk)
        assertNull(projectSdk)
    }

    fun testAnInheritingModuleGetsThePickAsTheProjectSdk() {
        val pick = sdk("pick")
        ModuleRootModificationUtil.setSdkInherited(module)

        assign(module, pick)

        assertProjectSdkInherited(pick)
    }

    fun testAModuleWithNoSdkEntryGetsThePickAsTheProjectSdkAndNoEntry() {
        val pick = sdk("pick")

        assign(module, pick)

        assertSame(pick, projectSdk)
        assertEmpty(jdkEntries(module))
    }

    fun testAnotherModulesOwnElixirSdkGoesToInherit() {
        val before = sdk("before")
        val pick = sdk("pick")
        ModuleRootModificationUtil.setModuleSdk(module, before)

        assign(module, pick)

        assertProjectSdkInherited(pick)
    }

    fun testAnotherModulesDanglingElixirEntryGoesToInherit() {
        val pick = sdk("pick")
        ModuleRootModificationUtil.updateModel(module) { it.setInvalidSdk("deleted", Type.instance.name) }

        assign(module, pick)

        assertProjectSdkInherited(pick)
    }

    fun testAnotherModulesJdkStays() {
        val jdk = jdk("jdk")
        val pick = sdk("pick")
        ModuleRootModificationUtil.setModuleSdk(module, jdk)

        assign(module, pick)

        assertSame(pick, projectSdk)
        assertSame(jdk, ownEntry(module)?.jdk)
    }

    /** The table finds an SDK by name before type, so this entry's SDK is the JDK, and the entry is not Elixir's. */
    fun testAnEntryStoredAsElixirsThatNowFindsAJdkIsLeft() {
        val jdk = jdk("shared name")
        val pick = sdk("pick")
        ModuleRootModificationUtil.updateModel(module) { it.setInvalidSdk(jdk.name, Type.instance.name) }

        assign(module, pick)

        assertSame(pick, projectSdk)
        assertSame(jdk, ownEntry(module)?.jdk)
    }

    fun testAModelCopyIsWrittenAsTheTableSdksModuleSdk() {
        val module = elixirModule()
        val pick = sdk("pick")

        assign(module, copyOf(pick))

        assertSame(pick, ownEntry(module)?.jdk)
    }

    fun testAModelCopyIsWrittenAsTheTableSdkAsProjectSdk() {
        val pick = sdk("pick")

        assign(module, copyOf(pick))

        assertSame(pick, projectSdk)
    }

    private fun copyOf(sdk: Sdk): Sdk {
        val model = ProjectSdksModel().apply { reset(project) }
        val copy = model.findSdk(sdk.name)!!
        assertNotSame(sdk, copy)
        return copy
    }
}

/** A small IDE: the module's Elixir facet names the pick. */
class ElixirSdkAssignmentSmallIdeTest : SmallIdeFacetHeavyTestCase() {
    private fun assign(sdk: Sdk) = WriteAction.runAndWait<Throwable> { ElixirSdkAssignment.assign(module, sdk) }

    fun testAModuleWithoutAFacetIsGivenOneNamingThePick() {
        val pick = models.register("pick")

        assign(pick)

        assertEquals("Elixir facet", FacetManager.getInstance(module).getFacetByType(Facet.ID)?.name)
        assertEquals("pick", FacetModelFixtures.storedName(module))
    }

    fun testAnExistingFacetNamesThePick() {
        val before = models.register("before")
        val pick = models.register("pick")
        FacetModelFixtures.assign(module, before)

        assign(pick)

        assertEquals(1, FacetManager.getInstance(module).getFacetsByType(Facet.ID).size)
        assertEquals("pick", FacetModelFixtures.storedName(module))
    }

    fun testAPickNotYetInTheTableIsNamed() {
        assign(ProjectJdkImpl("not yet applied", Type.instance))

        assertEquals("not yet applied", FacetModelFixtures.storedName(module))
    }

    fun testNoModuleOrProjectSdkIsWritten() {
        val pick = models.register("pick")

        assign(pick)

        assertNull(ProjectRootManager.getInstance(project).projectSdk)
        assertEmpty(jdkEntries(module))
    }
}
