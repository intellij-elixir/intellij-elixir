package org.elixir_lang.facet.sdks

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ex.ConfigurableCardPanel
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
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
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.ScriptedErlangStep
import org.elixir_lang.sdk.erlang_dependent.ScriptedFileChooserFactory
import org.elixir_lang.sdk.erlang_dependent.elixirAdditionalData
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
 *
 * Pages are opened as Settings tabs, so the pass that brings the shared model in line with the table runs when the EDT
 * is pumped ([settle]).
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

    fun testACommitOutsideTheModelReachesItsCopy() {
        val sdk = registerElixirSdk("Elixir Test Committed Outside")
        val before = service().getModel()

        commitHome(sdk, "/fake/elixir/committed-outside")
        settle()

        assertSame("open pages keep the model", before, service().getModel())
        assertEquals("/fake/elixir/committed-outside", before.findSdk("Elixir Test Committed Outside")?.homePath)
    }

    fun testApplyingTheCachedModelKeepsIt() {
        registerElixirSdk("Elixir Test Applied")
        val model = service().getModel()

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

    fun testAnSdkRenamedByAnotherWriterWhileApplyingReachesItsCopy() {
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
        settle()
        assertSame(model, service().getModel())
        assertEquals("the copy has the table's name", renamed.name, model.projectSdks[renamed]?.name)
    }

    fun testAnSdkAddedToTheTableWhileApplyingReachesTheModel() {
        val sdk = registerElixirSdk("Elixir Test Apply Trigger For Add")
        val model = service().getModel()
        val added = AtomicBoolean()
        // "Configure from mise" registering an SDK while the dialog applies.
        sdk.rootProvider.addRootSetChangedListener({
            if (added.compareAndSet(false, true)) addElixirSdkToTable("Elixir Test Added During Apply")
        }, testRootDisposable)

        service().apply(model)

        assertTrue("precondition: the SDK was added while the model was being applied", added.get())
        settle()
        assertTrue(
            "an SDK another writer added during the apply must reach the model; got ${listedNames()}",
            "Elixir Test Added During Apply" in listedNames(),
        )
    }

    fun testAnSdkAddedByApplyingTheModelIsFollowed() {
        val model = service().getModel()
        model.addSdk(ProjectJdkImpl("Elixir Test Added By Apply", ElixirSdkType.instance))
        service().apply(model)
        val addedSdk = ProjectJdkTable.getInstance().findJdk("Elixir Test Added By Apply")!!

        commitHome(addedSdk, "/fake/elixir/added-by-apply")
        settle()

        assertSame(model, service().getModel())
        assertEquals(
            "a commit outside the model to an SDK it added reaches its copy",
            "/fake/elixir/added-by-apply",
            model.projectSdks[addedSdk]?.homePath,
        )
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

    fun testAnOutsideCommitReachesAnOpenPageAndApplyKeepsIt() {
        val sdk = registerElixirSdk("Elixir Outside Commit", "/fake/elixir/a")
        val page = Page()
        page.select(sdk)

        commitHome(sdk, "/fake/elixir/outside")
        settle()
        page.apply()

        assertTableHome("/fake/elixir/outside", sdk)
    }

    fun testApplyBeforeTheDeferredPassWritesNothingStale() {
        val sdk = registerElixirSdk("Elixir Outside Commit Before The Pass", "/fake/elixir/a")
        val page = Page()
        page.select(sdk)

        commitHome(sdk, "/fake/elixir/outside")
        page.apply()

        assertTableHome("/fake/elixir/outside", sdk)
    }

    fun testAnSdkRemovedOutsideStaysGoneAfterApply() {
        val sdk = registerElixirSdk("Elixir Removed Outside", "/fake/elixir/a")
        val page = Page()

        SdkConfigurationUtil.removeSdk(sdk)
        settle()
        page.apply()

        assertFalse("an SDK removed outside is not brought back", inTable(sdk.name))
    }

    fun testAnSdkEditedOnThePageAndRemovedOutsideStaysGone() {
        val sdk = registerPairedElixirSdk("Elixir Edited Then Removed Outside")
        val page = Page()
        page.select(sdk).doSetHomePath(SdkFixtures.elixirHome("1.19.5"), ElixirSdkType.instance)

        SdkConfigurationUtil.removeSdk(sdk)
        settle()
        page.apply()

        assertFalse("an SDK removed outside is not brought back by the page's edit", inTable(sdk.name))
        assertFalse("the page no longer lists it; got ${page.listed()}", sdk.name in page.listed())
    }

    fun testAnSdkAddedOutsideIsListedOnAnOpenPage() {
        val page = Page()

        addElixirSdkToTable("Elixir Added Outside")
        settle()

        assertTrue("the open page lists it; got ${page.listed()}", "Elixir Added Outside" in page.listed())
        assertFalse("an outside add is no edit of the page", page.isModified)
    }

    fun testAnOutsideCommitLeavesAnUneditedPageUnmodified() {
        val sdk = registerElixirSdk("Elixir Outside Commit Unedited", "/fake/elixir/a")
        val page = Page()
        page.select(sdk)

        commitHome(sdk, "/fake/elixir/b")
        settle()

        assertFalse("an outside commit is no edit of the page", page.isModified)
    }

    fun testTwoOutsideCommitsInARowReachAnUneditedPage() {
        val sdk = registerElixirSdk("Elixir Two Outside Commits", "/fake/elixir/a")
        val page = Page()
        page.select(sdk)

        commitHome(sdk, "/fake/elixir/b")
        settle()
        assertFalse("after the first commit", page.isModified)
        commitHome(sdk, "/fake/elixir/c")
        settle()
        assertFalse("after the second commit", page.isModified)
        page.apply()

        assertTableHome("/fake/elixir/c", sdk)
    }

    fun testAnUnappliedEditWinsOverAnOutsideCommit() {
        val homeB = SdkFixtures.elixirHome("1.19.5")
        val sdk = registerPairedElixirSdk("Elixir Edited And Committed Outside")
        val homeA = sdk.homePath!!
        val pagesErlang = SdkFixtures.storedErlangName(sdk)
        val outsideErlang = SdkFixtures.register(SdkFixtures.erlangSdk("Outside Erlang", SdkFixtures.erlangHomeWithEbin("26", "26.2.5")))
        val page = Page()
        page.select(sdk).doSetHomePath(homeB, ElixirSdkType.instance)

        SdkFixtures.commitPairing(sdk, outsideErlang)
        settle()
        page.apply()

        val applied = tableSdk(sdk.name)
        assertTableHome(homeB, sdk, "the page's home")
        assertEquals("the page's pairing", pagesErlang, SdkFixtures.storedErlangName(applied))
        val roots = applied.rootProvider.getFiles(OrderRootType.CLASSES)
        assertNotEmpty(SdkFixtures.pathsUnder(roots, homeB))
        assertEmpty("no root under the old home", SdkFixtures.pathsUnder(roots, homeA))
    }

    fun testAnotherPagesApplyKeepsAnOutsideCommitToAnSdkAPageEdited() {
        val sdk = registerPairedElixirSdk("Elixir Edited Here Committed Outside")
        val editing = Page()
        val other = Page()
        editing.select(sdk).doSetHomePath(SdkFixtures.elixirHome("1.19.5"), ElixirSdkType.instance)

        commitHome(sdk, "/fake/elixir/outside")
        settle()
        other.apply()
        editing.cancel()

        assertTableHome("/fake/elixir/outside", sdk, "another page's Apply keeps the outside commit")
    }

    fun testAnApplyWritesItsOwnEditOfAnSdkAnotherPageAlsoEdited() {
        val sdk = registerPairedElixirSdk("Elixir Edited On Both Pages")
        val home = SdkFixtures.elixirHome("1.19.5")
        val other = Page()
        val applying = Page()
        other.select(sdk).doSetHomePath(home, ElixirSdkType.instance)
        applying.select(sdk).doSetHomePath(home, ElixirSdkType.instance)

        applying.apply()

        assertTableHome(home, sdk, "the applying page's own edit reaches the table")
        other.cancel()
        settle()
        assertTableHome(home, sdk, "and stays after the other page cancels")
    }

    fun testAnUndoneEditLetsApplyKeepAnOutsideCommit() {
        val sdk = registerElixirSdk("Elixir Undone Edit", "/fake/elixir/a")
        val other = registerPairedElixirSdk("Elixir Undone Edit Other")
        val page = Page()
        page.select(sdk)
        page.clickSuppressOtpWarning()

        commitHome(sdk, "/fake/elixir/outside")
        settle()
        page.clickSuppressOtpWarning()
        assertFalse("precondition: the edit is undone", page.isModified)
        page.select(other).doSetHomePath(SdkFixtures.elixirHome("1.19.5"), ElixirSdkType.instance)
        page.apply()

        assertTableHome("/fake/elixir/outside", sdk)
    }

    fun testAnUndoneEditOnAClosedPageLetsTheNextPageKeepAnOutsideCommit() {
        val sdk = registerPairedElixirSdk("Elixir Undone On Closed Page")
        val closing = Page()
        closing.select(sdk)
        closing.clickSuppressOtpWarning()
        commitHome(sdk, "/fake/elixir/outside")
        settle()
        closing.clickSuppressOtpWarning()
        closing.cancel()
        settle()

        val next = Page()
        next.select(sdk)
        next.clickSuppressOtpWarning()
        next.apply()

        assertTableHome("/fake/elixir/outside", sdk)
    }

    fun testAnUndoneEditLetsApplyDropWhatARefusedPageWrote() {
        val sdk = registerPairedElixirSdk("Elixir Undone Over Refused")
        val nameless = registerPairedElixirSdk("Elixir Undone Over Refused Nameless")
        val editing = Page()
        val refused = Page()
        editing.select(sdk)
        editing.clickSuppressOtpWarning()
        refused.select(sdk)
        refused.clickSuppressOtpWarning()
        renameCopy(nameless, "")
        assertApplyRefused { refused.apply() }
        refused.cancel()
        settle()

        editing.clickSuppressOtpWarning()
        assertFalse("precondition: the edit is undone", editing.isModified)
        editing.apply()

        assertEquals(false, suppressed(sdk))
    }

    fun testAFailedApplyOnOnePageLeavesTheOtherUnmodified() {
        val a = registerElixirSdk("Elixir Failed Apply A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Failed Apply B", "/fake/elixir/b")
        val refused = Page()
        val other = Page()
        refused.select(a)
        renameCopy(a, b.name)

        assertApplyRefused { refused.apply() }

        assertTrue("the refused page still has its edit", refused.isModified)
        assertFalse("the other page has none", other.isModified)
        refused.cancel()
        settle()
        other.apply()
        assertTableUnchanged(a to "Elixir Failed Apply A", b to "Elixir Failed Apply B")
    }

    fun testARefusedApplyLeavesAnOptionItWroteModified() {
        val a = registerElixirSdk("Elixir Refused Option A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Refused Option B", "/fake/elixir/b")
        val c = registerElixirSdk("Elixir Refused Option C", "/fake/elixir/c")
        val page = Page()
        page.select(a)
        page.clickSuppressOtpWarning()
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

    fun testARefusedApplyLeavesAPageRemoveCancellable() {
        val a = registerElixirSdk("Elixir Refused Remove A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Refused Remove B", "/fake/elixir/b")
        val c = registerElixirSdk("Elixir Refused Remove C", "/fake/elixir/c")
        val refused = Page()
        val other = Page()
        refused.remove(a)
        refused.select(b)
        renameCopy(b, c.name)

        assertApplyRefused { refused.apply() }
        refused.cancel()
        settle()
        other.apply()

        assertTrue("the refused page's Remove is not applied by another page", inTable(a.name))
        assertTrue("the other page lists it; got ${other.listed()}", a.name in other.listed())
        assertTableUnchanged(b to "Elixir Refused Remove B", c to "Elixir Refused Remove C")
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

    fun testAnAddTakenOutByCancelLeavesTheOtherPage() = addThroughThePage { list, _, _ ->
        val adding = pages.single()
        val added = list.selectedValue
        val other = Page()
        other.show(added).doSetHomePath(SdkFixtures.elixirHome("1.19.5"), ElixirSdkType.instance)

        adding.cancel()

        assertFalse("the other page no longer lists it; got ${other.listed()}", added.name in other.listed())
        assertFalse("nor holds an edit of it", other.isModified)
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

    fun testAnEditAfterAnotherPagesApplyKeepsItsErlangRepair() {
        val oldHome = SdkFixtures.erlangHomeWithEbin("27", "27.3.4")
        val (erlang, elixir) = SdkFixtures.pairedInTable("Edited Elixir", erlangHomePath = oldHome)
        service().resetForTests()
        val elixirPage = Page()
        val erlangPage = Page(ErlangSdksConfigurable())
        elixirPage.select(elixir)

        erlangPage.select(erlang).doSetHomePath(SdkFixtures.erlangHomeWithEbin("26", "26.2.5"), ErlangSdkType.instance)
        erlangPage.apply()
        settle()
        elixirPage.select(elixir)
        elixirPage.clickSuppressOtpWarning()
        elixirPage.apply()
        settle()

        SdkFixtures.assertCodePathsFollowed(tableSdk(elixir.name), tableSdk(erlang.name), oldHome)
    }

    fun testAnEditBeforeAnotherPagesApplyKeepsItsErlangRepair() {
        val moved = moveTheErlangOfAHeldElixirSdk("Elixir Edited Before")

        moved.elixirPage.apply()

        moved.assertRepaired()
        assertEquals("the Elixir page's own edit", true, suppressed(moved.elixir))
    }

    fun testCancelAfterAnEditKeepsAnotherPagesErlangRepair() {
        val moved = moveTheErlangOfAHeldElixirSdk("Cancelled Elixir")

        moved.elixirPage.cancel()
        settle()

        moved.assertRepaired()
    }

    fun testAnErlangRepairOfAHeldCopyCommitsNoUnappliedWarningToggle() {
        val moved = moveTheErlangOfAHeldElixirSdk("Held Elixir Warning")
        assertEquals("after the Erlang page's Apply", false, suppressed(moved.elixir))

        moved.elixirPage.cancel()
        settle()

        assertEquals("after the Elixir page's Cancel", false, suppressed(moved.elixir))
    }

    fun testAnErlangRepairOfAHeldCopyKeepsAnOutsideCommitToIt() {
        val outsideRoot = outsideRoot()
        val moved = moveTheErlangOfAHeldElixirSdk("Held Elixir Committed Outside") { elixir ->
            SdkFixtures.commit(elixir) { addRoot(outsideRoot, OrderRootType.CLASSES) }
            WriteAction.run<Throwable> { ElixirSdkMutation.setOtpMismatchWarningSuppressed(elixir, true) }
            settle()
        }
        assertContainsElements(classUrls(moved.elixir), outsideRoot.url)
        assertEquals("the outside warning flag", true, suppressed(moved.elixir))
        moved.assertRepaired()

        moved.elixirPage.cancel()
        settle()

        assertContainsElements(classUrls(moved.elixir), outsideRoot.url)
        assertEquals("the outside warning flag, after the Cancel", true, suppressed(moved.elixir))
    }

    fun testAnErlangRepairOfAHeldCopyLeavesAPairingCommittedOutside() {
        val name = "Held Elixir Paired Outside"
        lateinit var other: Sdk
        val moved = moveTheErlangOfAHeldElixirSdk(name) { elixir ->
            other = otherErlang(name)
            SdkFixtures.commitPairing(elixir, other)
            settle()
        }
        assertPairedWith(moved.elixir, other, moved.newHome, "after the Erlang page's Apply")

        moved.elixirPage.cancel()
        settle()

        assertPairedWith(moved.elixir, other, moved.newHome, "after the Elixir page's Cancel")
    }

    /** A second Erlang SDK, with its code paths, for the Elixir SDK [name] to be paired with. */
    private fun otherErlang(name: String): Sdk = SdkFixtures.withCodePaths(
        SdkFixtures.register(SdkFixtures.erlangSdk("$name Other Erlang", SdkFixtures.erlangHomeWithEbin("25", "25.3.2")))
    )

    /** The table's [elixir] is paired with [other], at its home, with no code path under [movedHome]. */
    private fun assertPairedWith(elixir: Sdk, other: Sdk, movedHome: String, message: String) {
        val tableElixir = tableSdk(elixir.name)
        assertEquals("the stored Erlang SDK, $message", other.name, SdkFixtures.storedErlangName(tableElixir))
        assertEquals("the stored Erlang home, $message", FileUtil.toSystemIndependentName(other.homePath!!), erlangHome(tableElixir))
        assertEmpty("code paths under the move's home, $message", SdkFixtures.codePathsUnder(tableElixir, movedHome))
    }

    private fun outsideRoot() =
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(FileUtil.createTempDirectory("outside", "root", true))!!

    private fun classUrls(sdk: Sdk): kotlin.collections.List<String> =
        tableSdk(sdk.name).rootProvider.getUrls(OrderRootType.CLASSES).asList()

    private fun suppressed(sdk: Sdk): Boolean? = tableSdk(sdk.name).elixirAdditionalData?.isSuppressOtpMismatchWarning()

    private class Moved(val elixirPage: Page, val elixir: Sdk, val erlang: Sdk, val oldHome: String, val newHome: String)

    /**
     * The table's [Moved.elixir] is paired at its Erlang SDK's [newHome] and has that home's code paths, not [oldHome]'s.
     */
    private fun Moved.assertRepaired(newHome: String = this.newHome, oldHome: String = this.oldHome) {
        val tableElixir = tableSdk(elixir.name)
        assertEquals(
            "the stored Erlang home",
            FileUtil.toSystemIndependentName(newHome),
            erlangHome(tableElixir),
        )
        SdkFixtures.assertCodePathsFollowed(tableElixir, tableSdk(erlang.name), oldHome)
    }

    /**
     * Toggles the warning on the Elixir SDK [name] on one page, unapplied, and runs [meanwhile]; then another page moves
     * its Erlang SDK's home and applies.
     */
    private fun moveTheErlangOfAHeldElixirSdk(name: String, meanwhile: (Sdk) -> Unit = {}): Moved {
        val oldHome = SdkFixtures.erlangHomeWithEbin("27", "27.3.4")
        val (erlang, elixir) = SdkFixtures.pairedInTable(name, erlangHomePath = oldHome)
        service().resetForTests()
        val elixirPage = Page()
        val erlangPage = Page(ErlangSdksConfigurable())
        elixirPage.select(elixir)
        elixirPage.clickSuppressOtpWarning()
        meanwhile(elixir)

        val newHome = SdkFixtures.erlangHomeWithEbin("26", "26.2.5")
        erlangPage.select(erlang).doSetHomePath(newHome, ErlangSdkType.instance)
        erlangPage.apply()
        settle()
        return Moved(elixirPage, elixir, erlang, oldHome, newHome)
    }

    fun testAnErlangRepairARefusedApplyWroteIsLeftToItsPage() {
        val refused = refuseAnErlangMove("Elixir Repaired By A Refused Apply")

        refused.applyTheElixirPage()

        refused.assertNotRepaired()
    }

    fun testAnErlangRepairARefusedApplyWroteIsLeftOutOfAnotherPagesEdit() {
        val refused = refuseAnErlangMove("Elixir Edited While Repaired By A Refused Apply") { elixirPage, elixir ->
            elixirPage.select(elixir)
            elixirPage.clickSuppressOtpWarning()
        }

        refused.applyTheElixirPage()

        refused.assertNotRepaired()
        assertEquals("the Elixir page's own edit", true, suppressed(refused.elixir))
    }

    fun testARetriedErlangApplyStoresTheRepairItsRefusedApplyWrote() {
        val refused = refuseAnErlangMove("Elixir Repaired On Retry")
        refused.applyTheElixirPage()
        val closing = Page()
        closing.select(refused.elixir)
        closing.clickSuppressOtpWarning()
        closing.cancel()
        settle()

        refused.erlangPage.apply()

        refused.moved.assertRepaired()
    }

    fun testAnErlangSdkMovedAgainAfterARefusedApplyRepairsToItsLatestHome() {
        val refused = refuseAnErlangMove("Elixir Repaired After A Second Move")
        val latestHome = SdkFixtures.erlangHomeWithEbin("28", "28.0.2")
        refused.erlangPage.select(refused.moved.erlang).doSetHomePath(latestHome, ErlangSdkType.instance)
        refused.applyTheElixirPage()

        refused.erlangPage.apply()

        refused.moved.assertRepaired(newHome = latestHome, oldHome = refused.moved.newHome)
    }

    fun testARefusedErlangRepairIsReplayedOverAnotherPagesAppliedWarning() = inASmallIde {
        val refused = refuseAnErlangMove("Elixir Repaired After A Warning Applied") { elixirPage, elixir ->
            elixirPage.select(elixir)
            elixirPage.clickSuppressOtpWarning()
        }
        refused.applyTheElixirPage()

        refused.erlangPage.apply()

        refused.moved.assertRepaired()
        assertEquals("the Elixir page's applied warning", true, suppressed(refused.elixir))
    }

    fun testARefusedErlangRepairIsReplayedOverACodePathCommittedSince() {
        val refused = refuseAnErlangMove("Elixir Repaired After A Code Path Committed")
        val outsideRoot = outsideRoot()
        SdkFixtures.commit(tableSdk(refused.elixir.name)) { addRoot(outsideRoot, OrderRootType.CLASSES) }
        settle()
        refused.applyTheElixirPage()

        refused.erlangPage.apply()

        refused.moved.assertRepaired()
        assertContainsElements(classUrls(refused.elixir), outsideRoot.url)
    }

    fun testARefusedErlangRepairIsNotReplayedOverAPairingCommittedOutside() =
        assertARefusedRepairIsNotReplayedOver("Elixir Repaired Then Paired Outside") { refused, other ->
            SdkFixtures.commitPairing(tableSdk(refused.elixir.name), other)
            settle()
        }

    fun testARefusedErlangRepairIsNotReplayedOverAPairingAnotherPageApplied() = inASmallIde {
        assertARefusedRepairIsNotReplayedOver("Elixir Repaired Then Paired On A Page") { refused, other ->
            refused.elixirPage.pickErlang(refused.elixir, other)
        }
    }

    /**
     * Runs [test] as the small IDEs, where the pages are: a full IDE's documentation roots have no path editor on them,
     * so an Elixir editor writing its Erlang SDK's code paths fails there.
     */
    private fun inASmallIde(test: () -> Unit) {
        ProcessOutput.isSmallIdeOverride = true
        try {
            test()
        } finally {
            ProcessOutput.isSmallIdeOverride = null
        }
    }

    /**
     * Refuses an Erlang move of the Elixir SDK [name], adds a second Erlang SDK with its code paths, runs [pair] to pair
     * the Elixir SDK with it, and applies both pages. The table's Elixir SDK stays paired with the second Erlang SDK at
     * its home, with no code path under the refused move's home.
     */
    private fun assertARefusedRepairIsNotReplayedOver(name: String, pair: (Refused, Sdk) -> Unit) {
        val refused = refuseAnErlangMove(name)
        val other = otherErlang(name)
        settle()
        pair(refused, other)
        refused.applyTheElixirPage()

        refused.erlangPage.apply()

        assertPairedWith(refused.elixir, other, refused.moved.newHome, "after both Applies")
    }

    fun testClosingAPageWhoseApplyWasRefusedRestoresTheElixirSdkItRepaired() {
        val refused = refuseAnErlangMove("Elixir Restored On Close")

        refused.erlangPage.cancel()
        settle()

        assertEquals("the copy's Erlang home", refused.oldHome, erlangHome(copy(refused.elixir)))
        refused.applyTheElixirPage()
        refused.assertNotRepaired()
    }

    private fun erlangHome(sdk: Sdk): String? = SdkFixtures.storedErlangHome(sdk)?.let(FileUtil::toSystemIndependentName)

    private class Refused(val erlangPage: Page, val moved: Moved, val pairedNowhere: Sdk) {
        val elixirPage get() = moved.elixirPage
        val elixir get() = moved.elixir
        val oldHome: String get() = FileUtil.toSystemIndependentName(moved.oldHome)
    }

    /** The Elixir page removes the SDK paired nowhere, so its Apply validates, and applies. */
    private fun Refused.applyTheElixirPage() {
        elixirPage.remove(pairedNowhere)
        elixirPage.apply()
    }

    /** The table's Elixir SDK is still paired at its Erlang SDK's old home. */
    private fun Refused.assertNotRepaired() =
        assertEquals("the stored Erlang home", oldHome, erlangHome(tableSdk(elixir.name)))

    /**
     * Moves the Erlang SDK of the Elixir SDK [name] on one page and applies, refused because another Elixir SDK's Erlang
     * SDK resolves nowhere, so the Erlang page's repair of the Elixir copy is written back out of it. [meanwhile] runs
     * on the Elixir page before the move.
     */
    private fun refuseAnErlangMove(name: String, meanwhile: (Page, Sdk) -> Unit = { _, _ -> }): Refused {
        val oldHome = SdkFixtures.erlangHomeWithEbin("27", "27.3.4")
        val (erlang, elixir) = SdkFixtures.pairedInTable(name, erlangHomePath = oldHome)
        val pairedNowhere = registerElixirSdkPairedNowhere("$name Paired Nowhere")
        val erlangPage = Page(ErlangSdksConfigurable())
        val elixirPage = Page()
        meanwhile(elixirPage, elixir)
        val newHome = SdkFixtures.erlangHomeWithEbin("26", "26.2.5")
        erlangPage.select(erlang).doSetHomePath(newHome, ErlangSdkType.instance)
        assertApplyRefused { erlangPage.apply() }
        return Refused(erlangPage, Moved(elixirPage, elixir, erlang, oldHome, newHome), pairedNowhere).also {
            assertEquals("the copy's Erlang home", it.oldHome, erlangHome(copy(elixir)))
        }
    }

    fun testAnSdkAnotherPageRemovedLeavesThisPage() {
        val sdk = registerPairedElixirSdk("Elixir Removed By Another Page")
        val removing = Page()
        val other = Page()
        other.select(sdk).doSetHomePath(SdkFixtures.elixirHome("1.19.5"), ElixirSdkType.instance)

        removing.remove(sdk)
        removing.apply()
        settle()

        assertFalse("the SDK left the table", inTable(sdk.name))
        assertFalse("the other page no longer lists it; got ${other.listed()}", sdk.name in other.listed())
        assertFalse("nor holds an edit of it", other.isModified)
    }

    fun testExternalSdkAdditionRefreshesCachedModel() {
        // Prime the cached model without the SDK.
        assertFalse("Elixir Test D" in listedNames())

        // Register directly in the JDK table (as the tool-manager "Configure from mise" action does)
        // WITHOUT resetForTests - the JDK-table listener in SdksService must bring the cached
        // model up to date so it no longer takes an IDE restart for the SDK to appear in Settings.
        addElixirSdkToTable("Elixir Test D")
        settle()

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
    private fun addThroughThePage(check: (List, ProjectSdksModel, String) -> Unit) = inASmallIde {
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
        fun select(sdk: Sdk): Editor = show(copy(sdk))

        /** Selects [copy], which builds and shows its editor, and returns that editor. */
        fun show(copy: ProjectJdkImpl): Editor {
            list.setSelectedValue(copy, false)
            return UIUtil.findComponentOfType(root, ConfigurableCardPanel::class.java)!!.key as Editor
        }

        /** Clicks the shown editor's "Suppress OTP version mismatch warning" checkbox. */
        fun clickSuppressOtpWarning() =
            UIUtil.findComponentsOfType(root, JBCheckBox::class.java)
                .single { it.text == "Suppress OTP version mismatch warning" }
                .doClick()

        /** Selects the Elixir SDK [sdk] and picks [erlang]'s copy in its editor's Erlang SDK combo. */
        fun pickErlang(sdk: Sdk, erlang: Sdk) {
            select(sdk)
            UIUtil.findComponentOfType(root, ComboBox::class.java)!!.selectedItem = copy(erlang)
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

    /** The table holds [home], in its system-independent form, as [sdk]'s home. */
    private fun assertTableHome(home: String, sdk: Sdk, message: String? = null) =
        assertEquals(message, FileUtil.toSystemIndependentName(home), tableSdk(sdk.name).homePath)

    /** Commits [home] to [sdk] straight in the table, as `SdkRegistrar` or a refresh action does. */
    private fun commitHome(sdk: Sdk, home: String) = SdkFixtures.commit(sdk) { homePath = home }

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
