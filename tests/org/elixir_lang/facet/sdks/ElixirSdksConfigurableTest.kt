package org.elixir_lang.facet.sdks

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.Splitter
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.common.runAll
import com.intellij.ui.ToolbarDecorator
import com.intellij.util.ui.UIUtil
import java.util.concurrent.atomic.AtomicBoolean
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.SdksService
import org.elixir_lang.facet.sdk.Model
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.ScriptedErlangStep
import org.elixir_lang.sdk.erlang_dependent.ScriptedFileChooserFactory
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.sdk.wsl.wslCompat
import org.elixir_lang.facet.sdks.elixir.Configurable as ElixirSdksConfigurable
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType

/**
 * Tests for the Settings -> Elixir -> SDKs page ([org.elixir_lang.facet.sdks.Configurable]) and the
 * SDK chooser [Model], covering the regressions:
 *  - the list stays empty even though SDKs exist (model not populated),
 *  - removing an SDK throws an NPE, and
 *  - a removed SDK "ghosts" in the chooser.
 */
class ElixirSdksConfigurableTest : PlatformTestCase() {

    private val added = mutableListOf<Sdk>()

    override fun setUp() {
        super.setUp()
        service().resetForTests()
    }

    override fun tearDown() {
        runAll(
            {
                WriteAction.run<Throwable> {
                    val table = ProjectJdkTable.getInstance()
                    added.filter { table.allJdks.contains(it) }.forEach { table.removeJdk(it) }
                }
                added.clear()
            },
            { service().resetForTests() },
            { super.tearDown() },
        )
    }

    private fun service() = SdksService.getInstance()!!

    private fun addElixirSdkToTable(name: String): Sdk {
        val sdk = ProjectJdkImpl(name, ElixirSdkType.instance)
        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().addJdk(sdk) }
        added.add(sdk)
        return sdk
    }

    private fun registerElixirSdk(name: String): Sdk {
        val sdk = addElixirSdkToTable(name)
        service().resetForTests()   // rebuild the model so it reflects the new table entry
        return sdk
    }

    private fun listedNames(): kotlin.collections.List<String> =
        service().projectJdkImplList(ElixirSdkType::class.java).map { it.name }

    private fun modelClone(name: String): ProjectJdkImpl =
        service().getModel().findSdk(name) as ProjectJdkImpl

    private fun comboNames(model: Model): kotlin.collections.List<String> =
        model.items.mapNotNull { it?.name }

    fun testListReflectsRegisteredSdk() {
        registerElixirSdk("Elixir Test A")
        assertTrue("SDK should be listed; got ${listedNames()}", "Elixir Test A" in listedNames())
    }

    fun testACommitOutsideTheModelGivesTheNextViewFreshCopies() {
        val sdk = registerElixirSdk("Elixir Test Committed Outside")
        val before = service().getModel()

        // Written straight to the saved SDK, as SdkRegistrar or a refresh action does.
        WriteAction.run<Throwable> {
            sdk.sdkModificator.apply {
                homePath = "/fake/elixir/committed-outside"
                commitChanges()
            }
        }

        val after = service().getModel()
        assertNotSame("a commit outside the cached model must not leave it to be applied back over the SDK", before, after)
        assertEquals("/fake/elixir/committed-outside", after.findSdk("Elixir Test Committed Outside")?.homePath)
    }

    fun testApplyingTheCachedModelKeepsIt() {
        registerElixirSdk("Elixir Test Applied")
        val model = service().getModel()

        // Applying commits every SDK in the model, which would otherwise drop the model the page still holds.
        service().apply(model)

        assertSame("a page's own Apply must not leave pages opened later with a second model", model, service().getModel())
    }

    fun testApplyingKeepsTheCopiesItApplied() {
        registerElixirSdk("Elixir Test Copy Identity")
        val model = service().getModel()
        val copy = model.findSdk("Elixir Test Copy Identity")

        service().apply(model)

        assertSame("the open page's list and editors hold these copies", copy, model.findSdk("Elixir Test Copy Identity"))
    }

    fun testApplyingAModelThatAddsAnSdkKeepsIt() {
        val model = service().getModel()
        model.addSdk(ProjectJdkImpl("Elixir Test Added By Its Own Apply", ElixirSdkType.instance))

        service().apply(model)

        ProjectJdkTable.getInstance().findJdk("Elixir Test Added By Its Own Apply")!!.also(added::add)
        assertSame("the dialog's own add must not drop the model pages opened later share", model, service().getModel())
    }

    fun testAnSdkRenamedByAnotherWriterWhileApplyingDropsTheCachedModel() {
        val sdk = registerElixirSdk("Elixir Test Apply Trigger For Rename")
        val renamed = registerElixirSdk("Elixir Test Renamed During Apply")
        val model = service().getModel()
        val done = AtomicBoolean()
        sdk.rootProvider.addRootSetChangedListener({
            if (done.compareAndSet(false, true)) {
                // How SdkRegistrar renames an SDK: a modified copy applied over the saved one.
                val modified = (renamed as ProjectJdkImpl).clone()
                modified.sdkModificator.apply {
                    name = "Elixir Test Renamed During Apply (new)"
                    commitChanges()
                }
                ProjectJdkTable.getInstance().updateJdk(renamed, modified)
            }
        }, testRootDisposable)

        service().apply(model)

        assertTrue("precondition: the rename ran while the model was being applied", done.get())
        assertNotSame(
            "a copy that would revert another writer's rename must not be kept for the next page",
            model,
            service().getModel(),
        )
    }

    fun testAnSdkAddedToTheTableWhileApplyingReachesTheNextModel() {
        val sdk = registerElixirSdk("Elixir Test Apply Trigger For Add")
        val model = service().getModel()
        val added = AtomicBoolean()
        // "Configure from mise" registering an SDK while the dialog applies.
        sdk.rootProvider.addRootSetChangedListener({
            if (added.compareAndSet(false, true)) addElixirSdkToTable("Elixir Test Added During Apply")
        }, testRootDisposable)

        service().apply(model)

        assertTrue("precondition: the SDK was added while the model was being applied", added.get())
        assertTrue(
            "an SDK another writer added during the apply must reach the next model; got ${listedNames()}",
            "Elixir Test Added During Apply" in listedNames(),
        )
    }

    fun testAnSdkAddedByApplyingTheModelIsFollowed() {
        val model = service().getModel()
        model.addSdk(ProjectJdkImpl("Elixir Test Added By Apply", ElixirSdkType.instance))
        service().apply(model)
        val addedSdk = ProjectJdkTable.getInstance().findJdk("Elixir Test Added By Apply")!!.also(added::add)
        val kept = service().getModel()

        WriteAction.run<Throwable> {
            addedSdk.sdkModificator.apply {
                homePath = "/fake/elixir/added-by-apply"
                commitChanges()
            }
        }

        assertNotSame("a commit outside the model to an SDK it added must still rebuild it", kept, service().getModel())
    }

    fun testRemoveDoesNotThrowAndRemovesSdk() {
        registerElixirSdk("Elixir Test B")

        val configurable = ElixirSdksConfigurable()
        configurable.createComponent()
        configurable.reset()

        // Regression: this used to NPE (findSdk(clone)!! returned null after model re-cloning).
        configurable.removeSelectedSdk(modelClone("Elixir Test B"))

        assertFalse("model should no longer list it; got ${listedNames()}", "Elixir Test B" in listedNames())

        configurable.apply()
        assertFalse(
            "SDK should be gone from the JDK table",
            ProjectJdkTable.getInstance().allJdks.any { it.name == "Elixir Test B" },
        )
    }

    fun testARemoveReachesTheTableAndTheLibrariesOnlyOnApply() = removeOnThePage("Elixir Test Remove Applied") { configurable, name ->
        assertTrue("the table keeps it until Apply", inTable(name))
        assertNotNull("its library stays until Apply", library(name))

        configurable.apply()

        assertFalse("Apply removes it from the table", inTable(name))
        assertNull("Apply removes its library", library(name))
    }

    fun testCancelAfterARemoveKeepsTheSdkAndItsLibrary() = removeOnThePage("Elixir Test Remove Cancelled") { configurable, name ->
        // Cancel: the pages close without Apply.
        configurable.disposeUIResources()

        assertTrue("the table keeps it", inTable(name))
        assertNotNull("its library stays", library(name))
        assertTrue("the next page lists it; got ${listedNames()}", name in listedNames())
        service().apply(service().getModel())
        assertTrue("a later Apply keeps it", inTable(name))
    }

    fun testAnSdkReAddedUnderTheRemovedNameKeepsItsLibrary() = removeOnThePage("Elixir Test Remove Readded") { configurable, name ->
        val table = ProjectJdkTable.getInstance()
        try {
            service().getModel().addSdk(ProjectJdkImpl(name, ElixirSdkType.instance))

            configurable.apply()

            assertTrue("the re-added SDK is in the table", inTable(name))
            assertNotNull("its library stays", library(name))
        } finally {
            WriteAction.run<Throwable> { table.findJdk(name)?.let(table::removeJdk) }
        }
    }

    /** An Elixir SDK with its application library, removed with the page's Remove; [check] gets the page and the name. */
    private fun removeOnThePage(name: String, check: (ElixirSdksConfigurable, String) -> Unit) {
        val libraryTable = LibraryTablesRegistrar.getInstance().libraryTable
        registerElixirSdk(name)
        WriteAction.run<Throwable> { libraryTable.createLibrary(name) }
        val configurable = ElixirSdksConfigurable()
        try {
            configurable.createComponent()
            configurable.reset()

            configurable.removeSelectedSdk(modelClone(name))

            check(configurable, name)
        } finally {
            configurable.disposeUIResources()
            WriteAction.run<Throwable> { library(name)?.let(libraryTable::removeLibrary) }
        }
    }

    private fun inTable(name: String): Boolean = ProjectJdkTable.getInstance().findJdk(name) != null

    private fun library(name: String) = LibraryTablesRegistrar.getInstance().libraryTable.getLibraryByName(name)

    fun testExternalSdkAdditionRefreshesCachedModel() {
        // Prime the cached model without the SDK.
        assertFalse("Elixir Test D" in listedNames())

        // Register directly in the JDK table (as the tool-manager "Configure from mise" action does)
        // WITHOUT resetForTests - the JDK-table listener in SdksService must invalidate the cached
        // model so it no longer takes an IDE restart for the SDK to appear in Settings.
        addElixirSdkToTable("Elixir Test D")

        assertTrue("cached model should refresh after external add; got ${listedNames()}", "Elixir Test D" in listedNames())
    }

    fun testDisposeUIResourcesRemovesSdkModelListener() {
        val configurable = ElixirSdksConfigurable()
        configurable.createComponent()
        configurable.reset()

        val model = service().getModel()
        val libraryTable = LibraryTablesRegistrar.getInstance().libraryTable
        try {
            // While the page is open, its SdkModel listener mirrors model additions into the
            // application library table (that is its observable side effect).
            model.addSdk(ProjectJdkImpl("Elixir Listener A", ElixirSdkType.instance))
            assertNotNull(
                "sanity: listener should mirror the SDK into the library table while the page is open",
                libraryTable.getLibraryByName("Elixir Listener A"),
            )

            configurable.disposeUIResources()

            // After disposal the listener must be removed from the shared, app-lifetime model -
            // otherwise one listener accumulates per Settings open.
            model.addSdk(ProjectJdkImpl("Elixir Listener B", ElixirSdkType.instance))
            assertNull(
                "listener must be removed on disposeUIResources",
                libraryTable.getLibraryByName("Elixir Listener B"),
            )
        } finally {
            WriteAction.run<Throwable> {
                listOf("Elixir Listener A", "Elixir Listener B").forEach { name ->
                    libraryTable.getLibraryByName(name)?.let { libraryTable.removeLibrary(it) }
                }
            }
        }
    }

    fun testSdkAddedReusesExistingApplicationLibrary() {
        val configurable = ElixirSdksConfigurable()
        configurable.createComponent()
        configurable.reset()

        val libraryTable = LibraryTablesRegistrar.getInstance().libraryTable
        try {
            // An application library left over from an earlier add/remove cycle, carrying a root the
            // SDK about to be added does not have.
            WriteAction.run<Throwable> {
                libraryTable.createLibrary("Elixir Reused").modifiableModel.apply {
                    addRoot("temp:///stale", OrderRootType.CLASSES)
                    commit()
                }
            }

            // Regression: this used to throw
            // "IllegalStateException: Application library named Elixir Reused already exists" - sdkAdded
            // called createLibrary unconditionally, and the platform refuses a name that already has one.
            service().getModel().addSdk(ProjectJdkImpl("Elixir Reused", ElixirSdkType.instance))

            assertEquals(
                "the existing library must be reused, not duplicated",
                1,
                libraryTable.libraries.count { it.name == "Elixir Reused" },
            )
            // The roots are replaced rather than appended, so the stale one is gone - which also means
            // this test fails if sdkAdded stops mirroring altogether.
            assertEmpty(
                "stale roots must be replaced by the SDK's own",
                libraryTable.getLibraryByName("Elixir Reused")!!.getUrls(OrderRootType.CLASSES).toList(),
            )
        } finally {
            configurable.disposeUIResources()
            WriteAction.run<Throwable> {
                libraryTable.getLibraryByName("Elixir Reused")?.let { libraryTable.removeLibrary(it) }
            }
        }
    }

    fun testRemovedSdkDoesNotGhostInChooser() {
        registerElixirSdk("Elixir Test C")

        val model = Model()
        assertTrue("chooser should list it initially; got ${comboNames(model)}", "Elixir Test C" in comboNames(model))

        // Removing via the model fires beforeSdkRemove(original); the chooser must drop it by name.
        service().getModel().removeSdk(modelClone("Elixir Test C"))

        assertFalse("removed SDK must not ghost in the chooser; got ${comboNames(model)}", "Elixir Test C" in comboNames(model))
    }

    fun testAnAddWithAnInstallPickKeepsThePagesModel() = addThroughThePage { list, model, elixirHome ->
        val listed = (0 until list.model.size).map(list.model::getElementAt)
        assertTrue("the new Elixir SDK is listed", listed.any { wslCompat.pathsEqualWslAware(elixirHome, it.homePath) })
        assertSame("registering the picked install keeps the page's model", model, service().getModel())
    }

    fun testAnAddedSdkIsSelected() = addThroughThePage { list, _, elixirHome ->
        val selected = list.selectedValue
        assertNotNull("the new Elixir SDK is selected", selected)
        assertTrue(wslCompat.pathsEqualWslAware(elixirHome, selected.homePath))
    }

    /**
     * Adds an Elixir SDK through the page's "+", picking an Erlang install in the step, then hands [check] the page's
     * list, the model the page held before the add, and the Elixir install.
     */
    private fun addThroughThePage(check: (List, ProjectSdksModel, String) -> Unit) {
        ProcessOutput.isSmallIdeOverride = true
        val table = SdkFixtures.TableSnapshot()
        val libraryTable = LibraryTablesRegistrar.getInstance().libraryTable
        val librariesBefore = libraryTable.libraries.toSet()
        var configurable: ElixirSdksConfigurable? = null
        try {
            val asked = Asked()
            val elixirHome = elixirInstall("1.18.4", "28")
            val install = install("28", "28.1")
            ScriptedErlangStep(asked, ScriptedErlangStep.pickInstall(install), installs = listOf(install)).install(testRootDisposable)
            ScriptedFileChooserFactory(asked, elixirHome).install(testRootDisposable)
            val page = ElixirSdksConfigurable().also { configurable = it }
            val listPanel = (page.createComponent() as Splitter).firstComponent
            page.reset()
            val model = service().getModel()

            ToolbarDecorator.findAddButton(listPanel)!!.actionPerformed(TestActionEvent.createTestEvent())

            check(UIUtil.findComponentOfType(listPanel, List::class.java)!!, model, elixirHome)
        } finally {
            configurable?.disposeUIResources()
            ProcessOutput.isSmallIdeOverride = null
            // The page mirrors each SDK its model adds into an application library.
            WriteAction.run<Throwable> { libraryTable.libraries.filterNot(librariesBefore::contains).forEach(libraryTable::removeLibrary) }
            table.restore()
        }
    }
}
