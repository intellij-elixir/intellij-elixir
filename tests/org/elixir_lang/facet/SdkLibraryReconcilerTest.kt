package org.elixir_lang.facet

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.runAll
import org.elixir_lang.mix.sync.MixSyncTestHelpers.runSuspendOnPooledThread
import org.elixir_lang.Facet
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import com.intellij.util.concurrency.annotations.RequiresEdt

/**
 * A project configured by a version that created the Facet's module library empty gets its roots
 * attached on the next open, without the user touching Settings.
 *
 * The repair matters because nothing else would ever run: the assignment only happens when the SDK
 * changes, and [Configurable.isModified] compares the selection by identity, so a user re-picking
 * the SDK already shown does not re-trigger it either.
 *
 * **What this cannot tell you.** These assertions pass whether the roots are committed through the
 * module's [com.intellij.openapi.roots.ModifiableRootModel] or through the library's own modifiable
 * model outside it. Only the first survives in a running IDE; the second is visible to the workspace
 * model briefly and then gone. The fixture never round-trips the module, so both look identical here
 * - a version committing the wrong way passed every test in this class while completion in a RubyMine
 * sandbox stayed empty. What these do pin is that the repair happens at all: removing it turns
 * [testEmptyFacetLibraryIsRepopulated] red. The commit path itself is only checkable in a real IDE.
 */
class SdkLibraryReconcilerTest : PlatformTestCase() {

    private val added = mutableListOf<Sdk>()

    override fun setUp() {
        super.setUp()
        SdksService.getInstance()!!.resetForTests()
        ensureElixirFacet()
    }

    override fun tearDown() {
        runAll(
            {
                WriteAction.run<Throwable> {
                    FacetManager.getInstance(module).getFacetByType(Facet.ID)?.let { it.sdk = null }
                }
            },
            {
                WriteAction.run<Throwable> {
                    val table = ProjectJdkTable.getInstance()
                    added.filter { table.allJdks.contains(it) }.forEach { table.removeJdk(it) }
                }
                added.clear()
            },
            { SdksService.getInstance()!!.resetForTests() },
            { super.tearDown() },
        )
    }

    private fun ensureElixirFacet() {
        val facetManager = FacetManager.getInstance(module)

        if (facetManager.getFacetByType(Facet.ID) == null) {
            FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java))
        }
    }

    private fun registerElixirSdk(name: String, vararg classesRoots: VirtualFile): Sdk =
        registerElixirSdkByUrl(name, *classesRoots.map { it.url }.toTypedArray())

    /**
     * Registers an SDK from root **URLs** rather than resolved files, so a caller can declare a root
     * the VFS cannot resolve - the shape an SDK has at startup, before its roots are resolved.
     */
    private fun registerElixirSdkByUrl(name: String, vararg classesRootUrls: String): Sdk {
        val sdk = ProjectJdkImpl(name, ElixirSdkType.instance)

        WriteAction.run<Throwable> {
            sdk.sdkModificator.apply {
                classesRootUrls.forEach { addRoot(it, OrderRootType.CLASSES) }
                commitChanges()
            }
            ProjectJdkTable.getInstance().addJdk(sdk)
        }

        added.add(sdk)
        SdksService.getInstance()!!.resetForTests()

        return sdk
    }

    /**
     * Reproduces what an older version left on disk: a module library named after the SDK, carrying
     * no roots at all. Written through the same module library table [Facet.sdk] uses, so the state
     * is the real one rather than an approximation of it.
     */
    private fun assignSdkTheOldWay(sdk: Sdk) {
        WriteAction.run<Throwable> {
            val modifiableModel = module.rootManager.modifiableModel
            modifiableModel.moduleLibraryTable.createLibrary(sdk.name)
            modifiableModel.commit()
        }
        FacetModelFixtures.nameFacet(module, sdk.name)
    }

    private fun facetLibraryEntry(name: String): LibraryOrderEntry? =
        ModuleRootManager
            .getInstance(module)
            .orderEntries
            .filterIsInstance<LibraryOrderEntry>()
            .firstOrNull { it.libraryName == name }

    fun testEmptyFacetLibraryIsRepopulated() {
        val ebin = myFixture.tempDirFixture.findOrCreateDir("reconciled_elixir/lib/elixir/ebin")
        val sdk = registerElixirSdk("Elixir Reconciler Test A", ebin)

        assignSdkTheOldWay(sdk)

        assertEquals(
            "Precondition: the module library starts with no roots, as an older version left it",
            emptyList<VirtualFile>(),
            facetLibraryEntry(sdk.name)!!.getRootFiles(OrderRootType.CLASSES).toList()
        )
        assertEquals(
            "Precondition: the Facet SDK still resolves, so the repair has something to re-assign",
            sdk.name,
            Facet.sdk(module)?.name
        )

        runSuspendOnPooledThread { SdkLibraryReconciler.repair(project) }

        assertEquals(
            "The empty module library was not repopulated from its SDK",
            listOf(ebin),
            facetLibraryEntry(sdk.name)!!.getRootFiles(OrderRootType.CLASSES).toList()
        )
    }

    /**
     * A module whose library already carries the SDK's roots is left alone, so the repair does not
     * rewrite the project model on every open.
     */
    @RequiresEdt
    fun testPopulatedFacetLibraryIsLeftAlone() {
        val ebin = myFixture.tempDirFixture.findOrCreateDir("healthy_elixir/lib/elixir/ebin")
        val sdk = registerElixirSdk("Elixir Reconciler Test B", ebin)

        WriteAction.run<Throwable> {
            FacetManager.getInstance(module).getFacetByType(Facet.ID)!!.sdk = sdk
        }

        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val before = ProjectRootManager.getInstance(project).modificationCount

        runSuspendOnPooledThread { SdkLibraryReconciler.repair(project) }

        assertEquals(
            "A module whose roots are already attached was rewritten anyway",
            before,
            ProjectRootManager.getInstance(project).modificationCount
        )
        assertEquals(
            listOf(ebin),
            facetLibraryEntry(sdk.name)!!.getRootFiles(OrderRootType.CLASSES).toList()
        )
    }

    /**
     * An SDK with no roots of its own is not treated as a broken assignment - otherwise every open
     * would "repair" it, to no effect.
     */
    fun testSdkWithoutRootsIsNotRepairedRepeatedly() {
        val sdk = registerElixirSdk("Elixir Reconciler Test C")

        assignSdkTheOldWay(sdk)

        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val before = ProjectRootManager.getInstance(project).modificationCount

        runSuspendOnPooledThread { SdkLibraryReconciler.repair(project) }

        assertEquals(
            "An SDK carrying no roots was treated as a repairable assignment",
            before,
            ProjectRootManager.getInstance(project).modificationCount
        )
    }
}
