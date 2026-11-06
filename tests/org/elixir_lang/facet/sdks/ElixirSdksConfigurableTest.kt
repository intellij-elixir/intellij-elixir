package org.elixir_lang.facet.sdks

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LeakHunter
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.common.runAll
import com.intellij.ui.ToolbarDecorator
import java.util.concurrent.atomic.AtomicBoolean
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.SdksService
import org.elixir_lang.facet.sdk.Editor
import org.elixir_lang.facet.sdk.Model
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.settle
import org.elixir_lang.sdk.StubSdkType
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

    private val pages = mutableListOf<SdksPage>()

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        service().resetForTests()
    }

    override fun tearDown() {
        runAll(
            { pages.toList().forEach(SdksPage::cancel) },
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

    fun testAnOutsideCommitToAnotherTypesSdkReachesItsCopy() {
        // Neither Elixir nor Erlang, as PyCharm's Python SDKs are.
        val type = StubSdkType("Other Test SDK")
        SdkType.EP_NAME.point.registerExtension(type, testRootDisposable)
        val sdk = SdkFixtures.register(ProjectJdkImpl("Other Type Committed Outside", type, "/fake/other/a", ""))
        service().resetForTests()
        val model = service().getModel()

        commitHome(sdk, "/fake/other/outside")
        settle()

        assertEquals(
            "a page's Apply writes every copy, so another type's copy follows the table too",
            "/fake/other/outside",
            model.projectSdks[sdk]?.homePath,
        )
    }

    fun testApplyingTheCachedModelKeepsIt() {
        registerElixirSdk("Elixir Test Applied")
        val model = service().getModel()

        service().apply()

        assertSame("a page's own Apply must not leave pages opened later with a second model", model, service().getModel())
    }

    fun testApplyingKeepsTheCopiesItApplied() {
        registerElixirSdk("Elixir Test Copy Identity")
        val model = service().getModel()
        val copy = model.findSdk("Elixir Test Copy Identity")

        service().apply()

        assertSame("the open page's list and editors hold these copies", copy, model.findSdk("Elixir Test Copy Identity"))
    }

    fun testApplyingAModelThatAddsAnSdkKeepsIt() {
        val model = service().getModel()
        model.addSdk(ProjectJdkImpl("Elixir Test Added By Its Own Apply", ElixirSdkType.instance))

        service().apply()

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

        service().apply()

        assertTrue("precondition: the rename ran while the model was being applied", done.get())
        settle()
        assertSame(model, service().getModel())
        assertEquals("the copy has the table's name", renamed.name, model.projectSdks[renamed]?.name)
    }

    fun testAnSdkAddedToTheTableWhileApplyingReachesTheModel() {
        val sdk = registerElixirSdk("Elixir Test Apply Trigger For Add")
        service().getModel()
        val added = AtomicBoolean()
        // "Configure from mise" registering an SDK while the dialog applies.
        sdk.rootProvider.addRootSetChangedListener({
            if (added.compareAndSet(false, true)) addElixirSdkToTable("Elixir Test Added During Apply")
        }, testRootDisposable)

        service().apply()

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
        service().apply()
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
        val sdk = registerElixirSdk("Elixir Test B")
        val page = openPage()

        // Regression: this used to NPE (findSdk(clone)!! returned null after model re-cloning).
        page.remove(sdk)

        assertFalse("the page should no longer list it; got ${page.listed()}", "Elixir Test B" in page.listed())
        assertTrue("the model keeps it until Apply; got ${listedNames()}", "Elixir Test B" in listedNames())
        assertTrue("the table keeps it until Apply", inTable("Elixir Test B"))

        page.apply()
        assertFalse("SDK should be gone from the JDK table", inTable("Elixir Test B"))
    }

    fun testCancelAfterARemoveKeepsTheSdk() = removeOnThePage("Elixir Test Remove Cancelled") { page, name ->
        page.cancel()

        assertTrue("the table keeps it", inTable(name))
        assertTrue("the next page lists it; got ${listedNames()}", name in listedNames())
        service().apply()
        assertTrue("a later Apply keeps it", inTable(name))
    }

    fun testAnSdkReAddedUnderTheRemovedNameIsRefused() = removeOnThePage("Elixir Test Remove Readded") { page, name ->
        val removed = tableSdk(name)
        service().getModel().addSdk(ProjectJdkImpl(name, ElixirSdkType.instance))

        assertApplyRefused("Apply the removal first") { page.apply() }

        assertSame("the table keeps the removed SDK until its removal is applied", removed, tableSdk(name))
    }

    /** An Elixir SDK removed with the page's Remove; [check] gets the page and the name. */
    private fun removeOnThePage(name: String, check: (SdksPage, String) -> Unit) {
        val sdk = registerElixirSdk(name)
        val page = openPage()
        page.remove(sdk)
        check(page, name)
    }

    private fun inTable(name: String): Boolean = ProjectJdkTable.getInstance().findJdk(name) != null

    fun testAnOutsideCommitReachesAnOpenPageAndApplyKeepsIt() {
        val sdk = registerElixirSdk("Elixir Outside Commit", "/fake/elixir/a")
        val page = openPage()
        page.select(sdk)

        commitHome(sdk, "/fake/elixir/outside")
        settle()
        page.apply()

        assertTableHome("/fake/elixir/outside", sdk)
    }

    fun testApplyBeforeTheDeferredPassWritesNothingStale() {
        val sdk = registerElixirSdk("Elixir Outside Commit Before The Pass", "/fake/elixir/a")
        val page = openPage()
        page.select(sdk)

        commitHome(sdk, "/fake/elixir/outside")
        page.apply()

        assertTableHome("/fake/elixir/outside", sdk)
    }

    fun testAnSdkRemovedOutsideStaysGoneAfterApply() {
        val sdk = registerElixirSdk("Elixir Removed Outside", "/fake/elixir/a")
        val page = openPage()

        SdkConfigurationUtil.removeSdk(sdk)
        settle()
        page.apply()

        assertFalse("an SDK removed outside is not brought back", inTable(sdk.name))
    }

    fun testAnSdkEditedOnThePageAndRemovedOutsideStaysGone() {
        val sdk = registerPairedElixirSdk("Elixir Edited Then Removed Outside")
        val page = openPage()
        page.browse(sdk, SdkFixtures.elixirHome("1.19.5"))

        SdkConfigurationUtil.removeSdk(sdk)
        settle()
        page.apply()

        assertFalse("an SDK removed outside is not brought back by the page's edit", inTable(sdk.name))
        assertFalse("the page no longer lists it; got ${page.listed()}", sdk.name in page.listed())
    }

    fun testAnSdkAddedOutsideIsListedOnAnOpenPage() {
        val page = openPage()

        addElixirSdkToTable("Elixir Added Outside")
        settle()

        assertTrue("the open page lists it; got ${page.listed()}", "Elixir Added Outside" in page.listed())
        assertFalse("an outside add is no edit of the page", page.isModified)
    }

    fun testAnOutsideCommitLeavesAnUneditedPageUnmodified() {
        val sdk = registerElixirSdk("Elixir Outside Commit Unedited", "/fake/elixir/a")
        val page = openPage()
        page.select(sdk)

        commitHome(sdk, "/fake/elixir/b")
        settle()

        assertFalse("an outside commit is no edit of the page", page.isModified)
    }

    fun testTwoOutsideCommitsInARowReachAnUneditedPage() {
        val sdk = registerElixirSdk("Elixir Two Outside Commits", "/fake/elixir/a")
        val page = openPage()
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
        val page = openPage()
        page.browse(sdk, homeB)

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
        val editing = openPage()
        val other = openPage()
        editing.browse(sdk, SdkFixtures.elixirHome("1.19.5"))

        commitHome(sdk, "/fake/elixir/outside")
        settle()
        other.apply()
        editing.cancel()

        assertTableHome("/fake/elixir/outside", sdk, "another page's Apply keeps the outside commit")
    }

    fun testAnApplyWritesItsOwnEditOfAnSdkAnotherPageAlsoEdited() {
        val sdk = registerPairedElixirSdk("Elixir Edited On Both Pages")
        val home = SdkFixtures.elixirHome("1.19.5")
        val other = openPage()
        val applying = openPage()
        other.browse(sdk, home)
        applying.browse(sdk, home)

        applying.apply()

        assertTableHome(home, sdk, "the applying page's own edit reaches the table")
        other.cancel()
        settle()
        assertTableHome(home, sdk, "and stays after the other page cancels")
    }

    fun testAnUndoneEditLetsApplyKeepAnOutsideCommit() {
        val sdk = registerElixirSdk("Elixir Undone Edit", "/fake/elixir/a")
        val other = registerPairedElixirSdk("Elixir Undone Edit Other")
        val page = openPage()
        page.select(sdk)
        page.clickSuppressOtpWarning()

        commitHome(sdk, "/fake/elixir/outside")
        settle()
        page.clickSuppressOtpWarning()
        assertFalse("precondition: the edit is undone", page.isModified)
        page.browse(other, SdkFixtures.elixirHome("1.19.5"))
        page.apply()

        assertTableHome("/fake/elixir/outside", sdk)
    }

    fun testAnUndoneEditOnAClosedPageLetsTheNextPageKeepAnOutsideCommit() {
        val sdk = registerPairedElixirSdk("Elixir Undone On Closed Page")
        val closing = openPage()
        closing.select(sdk)
        closing.clickSuppressOtpWarning()
        commitHome(sdk, "/fake/elixir/outside")
        settle()
        closing.clickSuppressOtpWarning()
        closing.cancel()
        settle()

        val next = openPage()
        next.select(sdk)
        next.clickSuppressOtpWarning()
        next.apply()

        assertTableHome("/fake/elixir/outside", sdk)
    }

    fun testAnUndoneEditLetsApplyDropWhatARefusedPageWrote() {
        val sdk = registerPairedElixirSdk("Elixir Undone Over Refused")
        val nameless = registerPairedElixirSdk("Elixir Undone Over Refused Nameless")
        val editing = openPage()
        val refused = openPage()
        editing.select(sdk)
        editing.clickSuppressOtpWarning()
        refused.select(sdk)
        refused.clickSuppressOtpWarning()
        refused.rename(nameless, "")
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
        val refused = openPage()
        val other = openPage()
        refused.rename(a, b.name)

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
        val page = openPage()
        page.select(a)
        page.clickSuppressOtpWarning()
        page.rename(b, c.name)

        assertApplyRefused { page.apply() }

        assertTrue("the option is not applied while Apply is refused", page.isModified)
    }

    fun testAnAppliedHomeChangeLeavesThePageUnmodified() {
        val sdk = registerPairedElixirSdk("Elixir Applied Home")
        val page = openPage()
        page.browse(sdk, SdkFixtures.elixirHome("1.19.5"))
        assertTrue("precondition: browsing modifies the page", page.isModified)

        page.apply()

        assertFalse("an applied page has nothing left to apply", page.isModified)
    }

    fun testCancelAfterARemoveLeavesTheSharedModelWhole() {
        val sdk = registerElixirSdk("Elixir Removed Then Cancelled", "/fake/elixir/a")
        val removing = openPage()
        val other = openPage()

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
        val refused = openPage()
        val other = openPage()
        refused.remove(a)
        refused.rename(b, c.name)

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
        val page = openPage()

        page.remove(sdk)
        page.apply()

        assertFalse("an SDK whose Erlang SDK resolves nowhere can still be removed", inTable(sdk.name))
    }

    fun testANameClashWithARecordedRemoveIsRefused() {
        val a = registerElixirSdk("Elixir Clash A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Clash B", "/fake/elixir/b")
        val page = openPage()
        page.remove(a)
        page.rename(b, a.name)

        assertApplyRefused("Apply the removal first") { page.apply() }

        assertTableUnchanged(a to "Elixir Clash A", b to "Elixir Clash B")
    }

    fun testANameARecordedRemoveSharesWithAnotherTypesSdkIsRefusedAsThatSdksName() {
        val erlang = SdkFixtures.register(SdkFixtures.erlangSdk("Elixir Clash Shared", SdkFixtures.erlangHomeWithEbin("26", "26.2.5")))
        val a = registerElixirSdk("Elixir Clash Shared", "/fake/elixir/shared")
        val b = registerElixirSdk("Elixir Clash Shared B", "/fake/elixir/shared-b")
        settle()
        val page = openPage()
        page.remove(a)
        page.rename(b, a.name)

        assertApplyRefused("\"${a.name}\" is still another SDK's name: rename that SDK and apply it first") { page.apply() }

        assertTableUnchanged(a to "Elixir Clash Shared", erlang to "Elixir Clash Shared", b to "Elixir Clash Shared B")
    }

    fun testARenameOntoAnotherSdksNameIsRefusedBeforeItWritesTheSharedCopy() {
        val a = registerElixirSdk("Elixir Clash Left A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Clash Left B", "/fake/elixir/b")
        val removing = openPage()
        val renaming = openPage()
        removing.select(b)
        renaming.rename(b, a.name)
        assertApplyRefused("\"Elixir Clash Left A\" is still another SDK's name") { renaming.apply() }

        assertFalse("the page showing B sees no edit of it", removing.isModified)
        removing.remove(a)
        removing.apply()

        assertFalse("A is removed", inTable(a.name))
        assertTableUnchanged(b to "Elixir Clash Left B")
    }

    fun testANameClashWithARemoveWhoseCopyWasRenamedIsRefused() {
        val a = registerElixirSdk("Elixir Clash Renamed A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Clash Renamed B", "/fake/elixir/b")
        val c = registerElixirSdk("Elixir Clash Renamed C", "/fake/elixir/c")
        val removing = openPage()
        val renaming = openPage()
        renaming.rename(a, c.name)
        assertApplyRefused { renaming.apply() }

        removing.remove(a)
        removing.rename(b, a.name)

        assertApplyRefused("Apply the removal first") { removing.apply() }
        assertTableUnchanged(a to "Elixir Clash Renamed A", b to "Elixir Clash Renamed B", c to "Elixir Clash Renamed C")
    }

    fun testARefusedNameClashWritesNothingAnotherPageApplies() {
        val a = registerElixirSdk("Elixir Clash Unwritten A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Clash Unwritten B", "/fake/elixir/b")
        val removing = openPage()
        val other = openPage()
        removing.remove(a)
        removing.rename(b, a.name)
        assertApplyRefused("Apply the removal first") { removing.apply() }

        other.apply()

        assertEquals("B's copy keeps its name", "Elixir Clash Unwritten B", SdksPage.copy(b).name)
        assertTableUnchanged(a to "Elixir Clash Unwritten A", b to "Elixir Clash Unwritten B")
    }

    fun testCancelAfterAnAddAnotherPageAppliedKeepsTheSdk() = addThroughThePage { adding, _, elixirHome ->
        val other = openPage()
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

    fun testAnAddAnotherPageAppliedLeavesThePageUnmodified() = addThroughThePage { adding, _, _ ->
        assertTrue("the page reads modified while its Add is unapplied", adding.isModified)

        openPage().apply()

        assertFalse("its Add is in the table now, so it has nothing to apply", adding.isModified)
    }

    fun testAnAddTakenOutByCancelLeavesTheOtherPage() = addThroughThePage { adding, _, _ ->
        val added = adding.selected!!
        val other = openPage()
        other.show(added).doSetHomePath(SdkFixtures.elixirHome("1.19.5"), ElixirSdkType.instance)

        adding.cancel()

        assertFalse("the other page no longer lists it; got ${other.listed()}", added.name in other.listed())
        assertFalse("nor holds an edit of it", other.isModified)
    }

    fun testAMoveInProjectStructureRepairsTheTableAndAnOpenPagesCopy() {
        val (erlang, elixir) = SdkFixtures.pairedInTable("Elixir Moved In Project Structure")
        val oldHome = erlang.homePath!!
        service().resetForTests()
        val page = openPage()
        page.browse(elixir, SdkFixtures.elixirHome("1.19.5"))

        moveInProjectStructure(erlang, SdkFixtures.erlangHomeWithEbin("26", "26.2.5"))
        settle()

        assertPairedAt(tableSdk(elixir.name), erlang, oldHome, "the table's Elixir SDK")
        assertPairedAt(SdksPage.copy(elixir), erlang, oldHome, "the page's copy")
        assertTrue("the page keeps its edit", page.isModified)
    }

    fun testARefusedApplyLeavesOtherPagesCopiesUntouched() {
        val refused = refuseAnErlangMove("Elixir Beside A Refused Move") { page, elixir ->
            page.browse(elixir, SdkFixtures.elixirHome("1.19.5"))
        }
        val elixir = refused.elixir
        val erlang = refused.moved.erlang
        val oldHome = refused.oldHome
        settle()

        val copy = SdksPage.copy(elixir)
        assertEquals("the other page's copy keeps its Erlang home", oldHome, erlangHome(copy))
        assertEquals("and its code paths", SdkFixtures.codePaths(erlang), SdkFixtures.codePathsUnder(copy, oldHome))
        assertEquals("the table's Erlang home", oldHome, FileUtil.toSystemIndependentName(tableSdk(erlang.name).homePath!!))
        refused.assertNotRepaired()
    }

    fun testAMoveCommittedOutsideRepairsAnUneditedPagesCopy() = inASmallIde {
        val (erlang, elixir) = SdkFixtures.pairedInTable("Elixir Of An Erlang Moved Outside")
        val oldHome = erlang.homePath!!
        service().resetForTests()
        val page = openPage()
        page.select(elixir)

        SdkFixtures.moveHome(erlang, SdkFixtures.erlangHomeWithEbin("26", "26.2.5"))
        settle()

        assertPairedAt(tableSdk(elixir.name), erlang, oldHome, "the table's Elixir SDK")
        assertPairedAt(SdksPage.copy(elixir), erlang, oldHome, "the page's copy")
        assertFalse("a repair is no edit of the page", page.isModified)
    }

    /** [elixir] is paired at [home], [erlang]'s by default, and has [erlang]'s code paths, and none under [oldHome]. */
    private fun assertPairedAt(elixir: Sdk, erlang: Sdk, oldHome: String, message: String, home: String = erlang.homePath!!) {
        assertEquals("$message: the stored Erlang home", FileUtil.toSystemIndependentName(home), erlangHome(elixir))
        SdkFixtures.assertCodePathsFollowed(elixir, erlang, oldHome)
    }

    /**
     * Moves [erlang]'s home to [newHome] in Project Structure open over a model of its own, as its SDK editor does: the
     * copy committed with the new home's code paths, then the model applied.
     */
    private fun moveInProjectStructure(erlang: Sdk, newHome: String) {
        val model = SdkFixtures.projectStructure(project, testRootDisposable)
        SdkFixtures.moveHome(model.findSdk(erlang.name)!!, newHome)
        model.apply()
    }

    fun testAnErlangMoveOnAnotherPageLeavesAViewedElixirEditorUnmodified() = inASmallIde {
        assertAnErlangRepairSurvives("Viewed Elixir Unmodified") { elixirPage, _ ->
            assertFalse("the Elixir page holds no edit", elixirPage.isModified)
        }
    }

    /** A code path committed to the Elixir SDK just after the Erlang page's Apply is kept. */
    fun testACodePathCommittedJustAfterAnErlangMoveIsKept() {
        val (erlang, elixir) = SdkFixtures.pairedInTable("Elixir Committed Just After")
        val oldHome = erlang.homePath!!
        service().resetForTests()
        val erlangPage = openPage(ErlangSdksConfigurable())
        erlangPage.browse(erlang, SdkFixtures.erlangHomeWithEbin("26", "26.2.5"))
        erlangPage.apply()
        val outsideRoot = outsideRoot()
        SdkFixtures.commit(tableSdk(elixir.name)) { addRoot(outsideRoot, OrderRootType.CLASSES) }

        settle()

        assertContainsElements(classUrls(elixir), outsideRoot.url)
        SdkFixtures.assertCodePathsFollowed(tableSdk(elixir.name), tableSdk(erlang.name), oldHome)
    }

    fun testAViewedElixirEditorDoesNotUndoAnErlangRepair() = assertAnErlangRepairSurvives("Viewed Elixir") { _, _ -> }

    fun testAnEditAfterAnotherPagesApplyKeepsItsErlangRepair() =
        assertAnErlangRepairSurvives("Edited Elixir") { elixirPage, elixir ->
            elixirPage.select(elixir)
            elixirPage.clickSuppressOtpWarning()
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
        assertEquals("the outside warning flag", true, suppressed(moved.elixir))
        moved.assertRepaired()

        moved.elixirPage.cancel()
        settle()

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

    private class Moved(val elixirPage: SdksPage, val elixir: Sdk, val erlang: Sdk, val oldHome: String, val newHome: String)

    /**
     * The table's [Moved.elixir] is paired at its Erlang SDK's [newHome] and has that home's code paths, not [oldHome]'s.
     */
    private fun Moved.assertRepaired(newHome: String = this.newHome, oldHome: String = this.oldHome) =
        assertPairedAt(tableSdk(elixir.name), tableSdk(erlang.name), oldHome, "the table's Elixir SDK", newHome)

    /**
     * Views the Elixir SDK [name] on one page, toggling its warning, unapplied, when [toggle], and runs [meanwhile]; then
     * another page moves its Erlang SDK's home and applies.
     */
    private fun moveTheErlangOfAHeldElixirSdk(
        name: String,
        toggle: Boolean = true,
        meanwhile: (Sdk) -> Unit = {},
    ): Moved {
        val (erlang, elixir) = SdkFixtures.pairedInTable(name)
        val oldHome = erlang.homePath!!
        service().resetForTests()
        val elixirPage = openPage()
        val erlangPage = openPage(ErlangSdksConfigurable())
        elixirPage.select(elixir)
        if (toggle) elixirPage.clickSuppressOtpWarning()
        meanwhile(elixir)

        val newHome = SdkFixtures.erlangHomeWithEbin("26", "26.2.5")
        erlangPage.browse(erlang, newHome)
        erlangPage.apply()
        settle()
        return Moved(elixirPage, elixir, erlang, oldHome, newHome)
    }

    fun testARefusedErlangRenameLeavesItsElixirSdksPairingToAnotherPagesApply() {
        val (erlang, elixir) = SdkFixtures.pairedInTable("Elixir Refused Erlang Rename")
        val other = registerPairedElixirSdk("Elixir Refused Erlang Rename Other")
        val erlangPage = openPage(ErlangSdksConfigurable())
        val elixirPage = openPage()
        val erlangName = erlang.name
        erlangPage.rename(erlang, "")
        assertApplyRefused { erlangPage.apply() }

        elixirPage.select(other)
        elixirPage.clickSuppressOtpWarning()
        elixirPage.apply()

        assertEquals(erlangName, SdkFixtures.storedErlangName(tableSdk(elixir.name)))
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
        val closing = openPage()
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
        refused.erlangPage.browse(refused.moved.erlang, latestHome)
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

        assertEquals("the copy's Erlang home", refused.oldHome, erlangHome(SdksPage.copy(refused.elixir)))
        refused.applyTheElixirPage()
        refused.assertNotRepaired()
    }

    private fun erlangHome(sdk: Sdk): String? = SdkFixtures.storedErlangHome(sdk)?.let(FileUtil::toSystemIndependentName)

    private class Refused(val erlangPage: SdksPage, val moved: Moved, val pairedNowhere: Sdk) {
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
     * SDK resolves nowhere. [meanwhile] runs on the Elixir page before the move.
     */
    private fun refuseAnErlangMove(name: String, meanwhile: (SdksPage, Sdk) -> Unit = { _, _ -> }): Refused {
        val (erlang, elixir) = SdkFixtures.pairedInTable(name)
        val oldHome = erlang.homePath!!
        val pairedNowhere = registerElixirSdkPairedNowhere("$name Paired Nowhere")
        val erlangPage = openPage(ErlangSdksConfigurable())
        val elixirPage = openPage()
        meanwhile(elixirPage, elixir)
        val newHome = SdkFixtures.erlangHomeWithEbin("26", "26.2.5")
        erlangPage.browse(erlang, newHome)
        assertApplyRefused { erlangPage.apply() }
        return Refused(erlangPage, Moved(elixirPage, elixir, erlang, oldHome, newHome), pairedNowhere).also {
            assertEquals("the copy's Erlang home", it.oldHome, erlangHome(SdksPage.copy(elixir)))
        }
    }

    /**
     * Views the Elixir SDK [name] on one page while another page moves its Erlang SDK's home and applies; then runs
     * [beforeElixirApply] and applies the Elixir page. The Elixir SDK keeps the new home's code paths.
     */
    private fun assertAnErlangRepairSurvives(name: String, beforeElixirApply: (SdksPage, Sdk) -> Unit) {
        val moved = moveTheErlangOfAHeldElixirSdk(name, toggle = false)
        beforeElixirApply(moved.elixirPage, moved.elixir)
        moved.elixirPage.apply()
        settle()

        SdkFixtures.assertCodePathsFollowed(tableSdk(moved.elixir.name), tableSdk(moved.erlang.name), moved.oldHome)
    }

    fun testAnSdkAnotherPageRemovedLeavesThisPage() {
        val sdk = registerPairedElixirSdk("Elixir Removed By Another Page")
        val removing = openPage()
        val other = openPage()
        other.browse(sdk, SdkFixtures.elixirHome("1.19.5"))

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
        val page = openPage()
        val model = service().getModel()

        model.addSdk(ProjectJdkImpl("Elixir Listener A", ElixirSdkType.instance))
        assertTrue("sanity: the open page lists an SDK its model adds; got ${page.listed()}", "Elixir Listener A" in page.listed())

        page.cancel()

        // One listener left on the shared, app-lifetime model would accumulate per Settings open.
        model.addSdk(ProjectJdkImpl("Elixir Listener B", ElixirSdkType.instance))
        assertFalse("listener must be removed on disposeUIResources; got ${page.listed()}", "Elixir Listener B" in page.listed())
    }

    fun testAClosedPagesEditorsStopFollowingTheModel() {
        val elixir = registerPairedElixirSdk("Elixir Editor Closed")
        val page = openPage()
        page.select(elixir)
        val erlangCombo = page.erlangCombo
        val model = service().getModel()
        fun offered() = (0 until erlangCombo.itemCount).mapNotNull { (erlangCombo.getItemAt(it) as? Sdk)?.name }

        model.addSdk(ProjectJdkImpl("Erlang Added While Open", ErlangSdkType.instance))
        assertTrue("sanity: the open editor offers an Erlang SDK its model adds; got ${offered()}", "Erlang Added While Open" in offered())

        page.cancel()

        // An editor left listening to the shared, app-lifetime model would accumulate per Settings open.
        LeakHunter.checkLeak(model, Editor::class.java)
        model.addSdk(ProjectJdkImpl("Erlang Added After Close", ErlangSdkType.instance))
        assertFalse("the closed page's editor no longer follows the model; got ${offered()}", "Erlang Added After Close" in offered())
    }

    fun testRemovedSdkDoesNotGhostInChooser() {
        val sdk = registerElixirSdk("Elixir Test C")

        val model = Model()
        assertTrue("chooser should list it initially; got ${comboNames(model)}", "Elixir Test C" in comboNames(model))

        service().getModel().removeSdk(SdksPage.copy(sdk))

        assertFalse("removed SDK must not ghost in the chooser; got ${comboNames(model)}", "Elixir Test C" in comboNames(model))
    }

    fun testAnSdkRemovedAfterARefusedRenameDoesNotGhostInChooser() {
        val a = registerElixirSdk("Elixir Ghost A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Ghost B", "/fake/elixir/b")
        val chooser = Model()
        val page = openPage()
        page.rename(a, b.name)
        assertApplyRefused { page.apply() }

        page.remove(a)
        page.apply()

        assertEquals(
            "the chooser lists B once, and not the removed A under the name its refused rename left",
            listOf(b.name),
            comboNames(chooser).filter { it.startsWith("Elixir Ghost") },
        )
    }

    fun testARemoveAppliedOnOnePageKeepsAnSdkAnotherPageEditsInTheChooser() {
        val edited = registerElixirSdk("Elixir Held Edited", "/fake/elixir/edited")
        val removed = registerElixirSdk("Elixir Held Removed", "/fake/elixir/removed")
        val chooser = Model()
        val editing = openPage()
        val removing = openPage()
        editing.rename(edited, "Elixir Held Edited renamed")

        removing.remove(removed)
        removing.apply()

        assertEquals(
            "the chooser keeps the SDK the other page edits, and drops only the removed one",
            listOf(edited.name),
            comboNames(chooser).filter { it.startsWith("Elixir Held") },
        )
    }

    fun testSwappingTwoNamesIsRefused() {
        val a = registerElixirSdk("Elixir Swap A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Swap B", "/fake/elixir/b")
        val page = openPage()
        page.rename(a, b.name)
        page.rename(b, a.name)

        assertApplyRefused("is still another SDK's name") { page.apply() }

        assertTableUnchanged(a to "Elixir Swap A", b to "Elixir Swap B")
    }

    fun testTakingTheNameAnotherSdkRenamesAwayIsRefused() {
        val a = registerElixirSdk("Elixir Taken A", "/fake/elixir/a")
        val b = registerElixirSdk("Elixir Taken B", "/fake/elixir/b")
        val page = openPage()
        page.rename(a, b.name)
        page.rename(b, "Elixir Taken C")

        assertApplyRefused("\"Elixir Taken B\" is still another SDK's name") { page.apply() }

        assertTableUnchanged(a to "Elixir Taken A", b to "Elixir Taken B")
    }

    fun testAnAddWithAnInstallPickKeepsThePagesModel() = addThroughThePage { page, model, elixirHome ->
        assertTrue(
            "the new Elixir SDK is listed",
            page.listedSdks().any { wslCompat.pathsEqualWslAware(elixirHome, it.homePath) },
        )
        assertSame("registering the picked install keeps the page's model", model, service().getModel())
    }

    fun testAnAddedSdkIsSelected() = addThroughThePage { page, _, elixirHome ->
        val selected = page.selected
        assertNotNull("the new Elixir SDK is selected", selected)
        assertTrue(wslCompat.pathsEqualWslAware(elixirHome, selected!!.homePath))
    }

    /**
     * Adds an Elixir SDK through the page's "+", picking an Erlang install in the step, then hands [check] the page,
     * the model the page held before the add, and the Elixir install. The page stays open for [check].
     */
    private fun addThroughThePage(check: (SdksPage, ProjectSdksModel, String) -> Unit) = inASmallIde {
        val asked = Asked()
        val elixirHome = elixirInstall("1.18.4", "28")
        val install = install("28", "28.1")
        ScriptedErlangStep(asked, ScriptedErlangStep.pickInstall(install), installs = listOf(install)).install(testRootDisposable)
        ScriptedFileChooserFactory(asked, elixirHome).install(testRootDisposable)
        val page = openPage()
        val listPanel = (page.root as Splitter).firstComponent
        val model = service().getModel()

        ToolbarDecorator.findAddButton(listPanel)!!.actionPerformed(TestActionEvent.createTestEvent())

        check(page, model, elixirHome)
    }

    private fun openPage(configurable: Configurable = ElixirSdksConfigurable()) = SdksPage(configurable, pages)

    private fun tableSdk(name: String): Sdk = ProjectJdkTable.getInstance().findJdk(name)!!

    /** The table holds [home], in its system-independent form, as [sdk]'s home. */
    private fun assertTableHome(home: String, sdk: Sdk, message: String? = null) =
        assertEquals(message, FileUtil.toSystemIndependentName(home), tableSdk(sdk.name).homePath)

    /** Commits [home] to [sdk] straight in the table, as `SdkRegistrar` or a refresh action does. */
    private fun commitHome(sdk: Sdk, home: String) = SdkFixtures.commit(sdk) { homePath = home }

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
