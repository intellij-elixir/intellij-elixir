package org.elixir_lang.facet

import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.runAll
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.Facet
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.mix.sync.MixSyncTestHelpers.runSuspendOnPooledThread
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import java.io.File

/**
 * The repair leaves the project fixed on disk, not merely behaving as if it were.
 *
 * Deliberately does **not** save anything itself. [SdkLibraryReconciler.repair] is responsible for
 * writing its own work out, and a test that forces a save of its own cannot tell whether it did - an
 * earlier version of this test did exactly that and passed against a repair that saved nothing.
 * Needs a real project, hence [HeavyPlatformTestCase]: the light fixture has no module file to read
 * back.
 *
 * **What this pins, and what it does not.** Dropping the save from `repair` turns this red, so it
 * covers the difference between a project that is fixed and one that merely looks fixed until it is
 * next opened. It does *not* cover how the roots are committed: a version writing them through the
 * module library's own modifiable model, outside the owning
 * [com.intellij.openapi.roots.ModifiableRootModel], passes this too, while completion in a real IDE
 * finds nothing. Nothing in either fixture separates those, so that half rests on the sandbox.
 */
class SdkLibraryReconcilerHeavyTest : HeavyTestCase() {

    private val added = mutableListOf<Sdk>()

    override fun tearDown() {
        runAll(
            {
                WriteAction.run<Throwable> {
                    val table = ProjectJdkTable.getInstance()
                    added.filter { table.allJdks.contains(it) }.forEach { table.removeJdk(it) }
                }
                added.clear()
            },
            { SdksService.getInstance()?.resetForTests() },
            { super.tearDown() },
        )
    }

    // The .iml is what the repair must reach, and only Module.getModuleFilePath names it.
    @Suppress("UnstableApiUsage")
    @RequiresEdt
    fun testRepairIsWrittenToTheModuleFile() {
        val sdkHome = createTempDir("elixir_sdk_home")
        val ebin = File(sdkHome, "lib/elixir/ebin").apply { mkdirs() }
        FileUtil.writeToFile(File(ebin, "Elixir.Stdlib.beam"), "")

        val ebinVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ebin)
            ?: error("ebin not found in VFS after refresh")

        val sdk = ProjectJdkImpl("Elixir Heavy Reconciler Test", ElixirSdkType.instance)
        WriteAction.run<Throwable> {
            sdk.sdkModificator.apply {
                addRoot(ebinVf, OrderRootType.CLASSES)
                commitChanges()
            }
            ProjectJdkTable.getInstance().addJdk(sdk)
        }
        added.add(sdk)
        SdksService.getInstance()?.resetForTests()

        val module = createModule("stdlib_check")
        PsiTestUtil.addContentRoot(
            module,
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(createTempDir("content"))
                ?: error("content root not found in VFS")
        )
        FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java))

        // The state an older version left behind: the library names the SDK and carries no roots.
        WriteAction.run<Throwable> {
            val modifiableModel = ModuleRootManager.getInstance(module).modifiableModel
            modifiableModel.moduleLibraryTable.createLibrary(sdk.name)
            modifiableModel.commit()
        }
        FacetModelFixtures.nameFacet(module, sdk.name)

        assertEquals(
            "Precondition: the Facet SDK resolves, so the repair has something to act on",
            sdk.name,
            Facet.sdk(module)?.name
        )

        runSuspendOnPooledThread { SdkLibraryReconciler.repair(project) }

        val moduleFile = File(module.moduleFilePath)
        assertTrue("Module file was never written to ${moduleFile.absolutePath}", moduleFile.isFile)

        val saved = FileUtil.loadFile(moduleFile)
        assertTrue(
            "The repair was not written to the module file - it carries no SDK roots:\n$saved",
            saved.contains("/lib/elixir/ebin")
        )
    }
}
