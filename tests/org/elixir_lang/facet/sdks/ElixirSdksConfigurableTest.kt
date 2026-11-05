package org.elixir_lang.facet.sdks

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ex.ConfigurableCardPanel
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.Splitter
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.common.runAll
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import java.util.concurrent.atomic.AtomicBoolean
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.SdksService
import org.elixir_lang.facet.sdk.Editor
import org.elixir_lang.facet.sdk.Model
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.settle
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.ScriptedErlangStep
import org.elixir_lang.sdk.erlang_dependent.ScriptedFileChooserFactory
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.sdk.wsl.wslCompat
import org.elixir_lang.facet.sdks.elixir.Configurable as ElixirSdksConfigurable
import org.elixir_lang.facet.sdks.erlang.Configurable as ErlangSdksConfigurable
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType

/**
 * Tests for the Settings -> Elixir -> SDKs page ([org.elixir_lang.facet.sdks.Configurable]) and the
 * SDK chooser [Model], covering the regressions:
 *  - the list stays empty even though SDKs exist (model not populated),
 *  - removing an SDK throws an NPE, and
 *  - a removed SDK "ghosts" in the chooser.
 */
class ElixirSdksConfigurableTest : PlatformTestCase() {

    private lateinit var table: SdkFixtures.TableSnapshot

    private val pages = mutableListOf<Page>()

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        service().resetForTests()
    }

    override fun tearDown() {
        runAll(
            { pages.toList().forEach(Page::cancel) },
            { table.restore() },
            { service().resetForTests() },
            { super.tearDown() },
        )
    }

    private fun service() = SdksService.getInstance()!!

    private fun addElixirSdkToTable(name: String, home: String? = null): Sdk =
        SdkFixtures.register(home?.let { SdkFixtures.elixirSdk(name, it) } ?: ProjectJdkImpl(name, ElixirSdkType.instance))

    private fun registerElixirSdk(name: String, home: String? = null): Sdk {
        val sdk = addElixirSdkToTable(name, home)
        service().resetForTests()   // rebuild the model so it reflects the new table entry
        return sdk
    }

    /** An Elixir SDK paired with an Erlang SDK of its own and set up, so a browse to another home finds it. */
    private fun registerPairedElixirSdk(name: String): Sdk =
        SdkFixtures.pairedInTable(name).second.also { service().resetForTests() }

    /** An Elixir SDK whose Erlang SDK resolves nowhere, so an Apply that validates it is refused. */
    private fun registerElixirSdkPairedNowhere(name: String): Sdk {
        val sdk = addElixirSdkToTable(name, "/fake/elixir/a")
        SdkFixtures.commitPairing(sdk, "Nowhere Erlang", "/fake/erlang/nowhere")
        service().resetForTests()
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

        assertNotNull(ProjectJdkTable.getInstance().findJdk("Elixir Test Added By Its Own Apply"))
        assertSame("the dialog's own add must not drop the model pages opened later share", model, service().getModel())
    }

    fun testAnSdkRenamedByAnotherWriterWhileApplyingDropsTheCachedModel() {
        val sdk = registerElixirSdk("Elixir Test Apply Trigger For Rename")
        val renamed = registerElixirSdk("Elixir Test Renamed During Apply")
        val model = service().getModel()
        val done = AtomicBoolean()
        sdk.rootProvider.addRootSetChangedListener({
            if (done.compareAndSet(false, true)) {
                SdkFixtures.renameInTable(renamed.name, "Elixir Test Renamed During Apply (new)")
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
        val addedSdk = ProjectJdkTable.getInstance().findJdk("Elixir Test Added By Apply")!!
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
        val page = Page()

        // Regression: this used to NPE (findSdk(clone)!! returned null after model re-cloning).
        page.configurable.removeSelectedSdk(modelClone("Elixir Test B"))

        assertFalse("the page should no longer list it; got ${page.listed()}", "Elixir Test B" in page.listed())
        assertTrue("the model keeps it until Apply; got ${listedNames()}", "Elixir Test B" in listedNames())
        assertTrue("the table keeps it until Apply", inTable("Elixir Test B"))

        page.apply()
        assertFalse(
            "SDK should be gone from the JDK table",
            ProjectJdkTable.getInstance().allJdks.any { it.name == "Elixir Test B" },
        )
    }

    fun testCancelAfterARemoveKeepsTheSdk() = removeOnThePage("Elixir Test Remove Cancelled") { configurable, name ->
        // Cancel: the pages close without Apply.
        configurable.disposeUIResources()

        assertTrue("the table keeps it", inTable(name))
        assertTrue("the next page lists it; got ${listedNames()}", name in listedNames())
        service().apply(service().getModel())
        assertTrue("a later Apply keeps it", inTable(name))
    }

    fun testAnSdkReAddedUnderTheRemovedNameIsRefused() = removeOnThePage("Elixir Test Remove Readded") { configurable, name ->
        val removed = tableSdk(name)
        service().getModel().addSdk(ProjectJdkImpl(name, ElixirSdkType.instance))

        assertApplyRefused("Apply the removal first") { configurable.apply() }

        assertSame("the table keeps the removed SDK until its removal is applied", removed, tableSdk(name))
    }

    /** An Elixir SDK removed with the page's Remove; [check] gets the page and the name. */
    private fun removeOnThePage(name: String, check: (ElixirSdksConfigurable, String) -> Unit) {
        registerElixirSdk(name)
        val configurable = ElixirSdksConfigurable()
        try {
            configurable.createComponent()
            configurable.reset()

            configurable.removeSelectedSdk(modelClone(name))

            check(configurable, name)
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun inTable(name: String): Boolean = ProjectJdkTable.getInstance().findJdk(name) != null

    fun testARefusedApplyLeavesAnOptionItWroteModified() {
        val a = registerElixirSdk("Elixir Refused Option A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Refused Option B", "/fake/elixir/b")
        val c = registerElixirSdk("Elixir Refused Option C", "/fake/elixir/c")
        val page = Page()
        page.select(a)
        UIUtil.findComponentsOfType(page.root, JBCheckBox::class.java)
            .single { it.text == "Suppress OTP version mismatch warning" }
            .doClick()
        renameCopy(b, c.name)

        assertApplyRefused { page.apply() }

        assertTrue("the option is not applied while Apply is refused", page.isModified)
    }

    fun testAnAppliedHomeChangeLeavesThePageUnmodified() {
        val sdk = registerPairedElixirSdk("Elixir Applied Home")
        val page = Page()
        page.select(sdk).doSetHomePath(SdkFixtures.elixirHome("1.19.5"), ElixirSdkType.instance)
        assertTrue("precondition: browsing modifies the page", page.isModified)

        page.apply()

        assertFalse("an applied page has nothing left to apply", page.isModified)
    }

    fun testCancelAfterARemoveLeavesTheSharedModelWhole() {
        val sdk = registerElixirSdk("Elixir Removed Then Cancelled", "/fake/elixir/a")
        val removing = Page()
        val other = Page()

        removing.remove(sdk)
        assertTrue("the other page still lists it; got ${other.listed()}", sdk.name in other.listed())
        removing.cancel()
        assertTrue("after the Cancel; got ${other.listed()}", sdk.name in other.listed())
        other.apply()

        assertTrue("the other page's Apply keeps it", inTable(sdk.name))
        assertTrue("and lists it; got ${other.listed()}", sdk.name in other.listed())
    }

    fun testARemoveOfAnSdkThatFailsValidationIsAccepted() {
        assertEmpty(
            "precondition: no Erlang SDK is registered",
            ProjectJdkTable.getInstance().allJdks.filter { it.sdkType is ErlangSdkType },
        )
        val sdk = registerElixirSdkPairedNowhere("Elixir Paired Nowhere")
        val page = Page()

        page.remove(sdk)
        page.apply()

        assertFalse("an SDK whose Erlang SDK resolves nowhere can still be removed", inTable(sdk.name))
    }

    fun testANameClashWithARecordedRemoveIsRefused() {
        val a = registerElixirSdk("Elixir Clash A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Clash B", "/fake/elixir/b")
        val page = Page()
        page.remove(a)
        page.select(b)
        renameCopy(b, a.name)

        assertApplyRefused("Apply the removal first") { page.apply() }

        assertTableUnchanged(a to "Elixir Clash A", b to "Elixir Clash B")
    }

    fun testANameClashLeftByAnotherPagesRefusedApplyIsRefused() {
        val a = registerElixirSdk("Elixir Clash Left A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Clash Left B", "/fake/elixir/b")
        val removing = Page()
        val renaming = Page()
        renaming.select(b)
        renameCopy(b, a.name)
        assertApplyRefused { renaming.apply() }

        removing.remove(a)

        assertApplyRefused("Apply the removal first") { removing.apply() }
        assertTableUnchanged(a to "Elixir Clash Left A", b to "Elixir Clash Left B")
    }

    fun testANameClashWithARemoveWhoseCopyWasRenamedIsRefused() {
        val a = registerElixirSdk("Elixir Clash Renamed A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Clash Renamed B", "/fake/elixir/b")
        val c = registerElixirSdk("Elixir Clash Renamed C", "/fake/elixir/c")
        val removing = Page()
        val renaming = Page()
        renaming.select(a)
        renameCopy(a, c.name)
        assertApplyRefused { renaming.apply() }

        removing.remove(a)
        removing.select(b)
        renameCopy(b, a.name)

        assertApplyRefused("Apply the removal first") { removing.apply() }
        assertTableUnchanged(a to "Elixir Clash Renamed A", b to "Elixir Clash Renamed B", c to "Elixir Clash Renamed C")
    }

    fun testCancelAfterAnAddAnotherPageAppliedKeepsTheSdk() = addThroughThePage { _, _, elixirHome ->
        val adding = pages.single()
        val other = Page()
        other.apply()
        adding.cancel()
        settle()
        other.apply()

        assertTrue(
            "the SDK the other page applied stays after a later Apply",
            ProjectJdkTable.getInstance().allJdks.any {
                it.sdkType is ElixirSdkType && wslCompat.pathsEqualWslAware(elixirHome, it.homePath)
            },
        )
    }

    fun testAnAddAnotherPageAppliedLeavesThePageUnmodified() = addThroughThePage { _, _, _ ->
        val adding = pages.single()
        assertTrue("the page reads modified while its Add is unapplied", adding.configurable.isModified)

        Page().apply()

        assertFalse("its Add is in the table now, so it has nothing to apply", adding.configurable.isModified)
    }

    fun testAViewedElixirEditorDoesNotUndoAnErlangRepair() {
        val oldHome = SdkFixtures.erlangHomeWithEbin("27", "27.3.4")
        val (erlang, elixir) = SdkFixtures.pairedInTable("Viewed Elixir", erlangHomePath = oldHome)
        service().resetForTests()
        val elixirPage = Page()
        val erlangPage = Page(ErlangSdksConfigurable())
        elixirPage.select(elixir)

        erlangPage.select(erlang).doSetHomePath(SdkFixtures.erlangHomeWithEbin("26", "26.2.5"), ErlangSdkType.instance)
        erlangPage.apply()
        settle()
        elixirPage.apply()
        settle()

        SdkFixtures.assertCodePathsFollowed(tableSdk(elixir.name), tableSdk(erlang.name), oldHome)
    }

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
        val list = UIUtil.findComponentOfType(configurable.createComponent(), List::class.java)!!
        configurable.reset()
        val model = service().getModel()
        fun listed() = (0 until list.model.size).map { list.model.getElementAt(it).name }

        model.addSdk(ProjectJdkImpl("Elixir Listener A", ElixirSdkType.instance))
        assertTrue("sanity: the open page lists an SDK its model adds; got ${listed()}", "Elixir Listener A" in listed())

        configurable.disposeUIResources()

        // One listener left on the shared, app-lifetime model would accumulate per Settings open.
        model.addSdk(ProjectJdkImpl("Elixir Listener B", ElixirSdkType.instance))
        assertFalse("listener must be removed on disposeUIResources; got ${listed()}", "Elixir Listener B" in listed())
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
     * list, the model the page held before the add, and the Elixir install. The page stays open for [check].
     */
    private fun addThroughThePage(check: (List, ProjectSdksModel, String) -> Unit) {
        ProcessOutput.isSmallIdeOverride = true
        try {
            val asked = Asked()
            val elixirHome = elixirInstall("1.18.4", "28")
            val install = install("28", "28.1")
            ScriptedErlangStep(asked, ScriptedErlangStep.pickInstall(install), installs = listOf(install)).install(testRootDisposable)
            ScriptedFileChooserFactory(asked, elixirHome).install(testRootDisposable)
            val page = Page()
            val listPanel = (page.root as Splitter).firstComponent
            val model = service().getModel()

            ToolbarDecorator.findAddButton(listPanel)!!.actionPerformed(TestActionEvent.createTestEvent())

            check(UIUtil.findComponentOfType(listPanel, List::class.java)!!, model, elixirHome)
        } finally {
            ProcessOutput.isSmallIdeOverride = null
        }
    }

    /** One SDKs page, as a Settings tab: built and reset, then closed by [cancel] or at the end of the test. */
    private inner class Page(val configurable: Configurable = ElixirSdksConfigurable()) {
        val root = configurable.createComponent()!!

        init {
            configurable.reset()
            pages += this
        }

        private val list: List get() = UIUtil.findComponentOfType(root, List::class.java)!!

        fun listed(): kotlin.collections.List<String> = (0 until list.model.size).map { list.model.getElementAt(it).name }

        /** Selects [sdk]'s copy, which builds and shows its editor, and returns that editor. */
        fun select(sdk: Sdk): Editor {
            list.setSelectedValue(copy(sdk), false)
            return UIUtil.findComponentOfType(root, ConfigurableCardPanel::class.java)!!.key as Editor
        }

        fun remove(sdk: Sdk) = configurable.removeSelectedSdk(copy(sdk))

        val isModified: Boolean get() = configurable.isModified

        fun apply() = configurable.apply()

        fun cancel() {
            pages -= this
            configurable.disposeUIResources()
        }
    }

    /** [sdk]'s copy in the shared model, found by the table SDK it is keyed by. */
    private fun copy(sdk: Sdk): ProjectJdkImpl = service().getModel().projectSdks[sdk] as ProjectJdkImpl

    private fun tableSdk(name: String): Sdk = ProjectJdkTable.getInstance().findJdk(name)!!

    /** Renames [sdk]'s copy in the shared model, as an editor does through the copy's modificator. */
    private fun renameCopy(sdk: Sdk, name: String) = WriteAction.run<Throwable> {
        copy(sdk).sdkModificator.apply {
            this.name = name
            commitChanges()
        }
    }

    private fun assertApplyRefused(message: String? = null, apply: () -> Unit) =
        assertThrows(ConfigurationException::class.java, message) { apply() }

    /** Each table SDK still has its name, and no table SDK not given has it. */
    private fun assertTableUnchanged(vararg named: Pair<Sdk, String>) {
        val table = ProjectJdkTable.getInstance().allJdks
        for ((sdk, name) in named) {
            assertEquals("$name's name in the table", name, sdk.name)
            assertTrue("$name is in the table", table.any { it === sdk })
        }
        for ((name, count) in named.groupingBy { it.second }.eachCount()) {
            assertEquals("table SDKs named $name", count, table.count { it.name == name })
        }
    }
}
