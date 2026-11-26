package org.elixir_lang.sdk.choice

import com.intellij.openapi.Disposable
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.roots.ModuleJdkOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.SdkComboBox
import com.intellij.openapi.roots.ui.configuration.SdkComboBoxModel
import com.intellij.openapi.roots.ui.configuration.SdkDetector
import com.intellij.openapi.roots.ui.configuration.SdkListItem
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.replaceService
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import org.elixir_lang.Facet
import org.elixir_lang.facet.FacetModelFixtures
import org.elixir_lang.facet.SmallIdeFacetHeavyTestCase
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.elixir.SettingsPage
import org.elixir_lang.sdk.elixir.Type
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.ScriptedErlangStep
import org.elixir_lang.sdk.erlang_dependent.ScriptedFileChooserFactory
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.tool_manager.ToolEntry
import org.elixir_lang.tool_manager.ToolManagerOptIns
import org.elixir_lang.tool_manager.ToolManagerResult
import org.elixir_lang.tool_manager.ToolManagerSettings
import org.elixir_lang.tool_manager.assertPairedWithErlangAt
import org.elixir_lang.tool_manager.elixirSdksAt
import org.elixir_lang.tool_manager.mise.MiseToolManager
import org.elixir_lang.tool_manager.pairedElixir
import org.elixir_lang.tool_manager.registeredErlang
import org.elixir_lang.tool_manager.twoSdksOnTheInstall
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JList

/** What a row shows: an SDK by name, [PROJECT_SDK], an entry's name, or [NONE]. */
private fun SdkListItem?.text(): String = when (this) {
    is SdkListItem.ProjectSdkItem -> PROJECT_SDK
    is SdkListItem.SdkItem -> sdk.name
    is SdkListItem.SdkReferenceItem -> name
    is SdkListItem.NoneSdkItem, null -> NONE
    is SdkListItem.InvalidSdkItem -> "invalid $sdkName"
    else -> toString()
}

private fun SdkComboBox.items(): List<SdkListItem> = model.listModel.items

private val SdkRowsDialog.Row.shown: String get() = combo.selectedItem.text()

/** The row's value as its field draws it, "Project SDK Elixir 1.18" or "<No Project SDK>". */
private val SdkRowsDialog.Row.drawn: String
    get() {
        val drawn = combo.renderer.getListCellRendererComponent(JList(), combo.selectedItem, -1, false, false)
        return UIUtil.uiTraverser(drawn).filter(SimpleColoredComponent::class.java).first()!!.getCharSequence(false).toString()
    }

/** The SDKs, entries and items "Project SDK" and none that [this] row lists, in its order. */
private val SdkRowsDialog.Row.listed: List<String>
    get() = combo.items().filter { it !is SdkListItem.ActionItem && it !is SdkListItem.GroupItem && it !is SdkListItem.SuggestedItem }
        .map { it.text() }

/** Whether [this] row lists no tool manager's entry. */
private val SdkRowsDialog.Row.listsNoEntry: Boolean get() = listed.none { it.startsWith("mise:") }

/** Picks the item [text] in [this] row, as the user does. */
private fun SdkRowsDialog.Row.pick(text: String) {
    combo.selectedItem = combo.items().single { it.text() == text }
}

private fun SdkRowsDialog.row(module: Module): SdkRowsDialog.Row = moduleRows.single { it.module == module }

/** The row's list as its popup opens it: the add actions, and any SDK found on disk. */
private fun SdkComboBox.openPopup(): List<SdkListItem> {
    firePopupMenuWillBecomeVisible()
    return items().also { firePopupMenuWillBecomeInvisible() }
}

/** The label [text] under this component, once it shows. */
private fun javax.swing.JComponent.waitForLine(text: String) = waitFor(text) { it == text }

/** IntelliJ IDEA: a Project SDK row and a row per Elixir module, built unshown and driven as the user does. */
class SdkRowsDialogTest : HeavyTestCase() {
    private lateinit var table: SdkFixtures.TableSnapshot
    private val asked = Asked()
    private val toolManager = FakeToolManager()

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        asked.recordMessages(testRootDisposable)
        toolManager.install(testRootDisposable)
    }

    override fun tearDown() = runAll({ table.restore() }, { super.tearDown() })

    private fun elixirModule(name: String): Module =
        doCreateRealModuleIn(name, project, ModuleTypeManager.getInstance().findByID(ELIXIR_MODULE_TYPE_ID))

    /** An `ELIXIR_MODULE` inheriting the project SDK, as IntelliJ IDEA creates one. */
    private fun inheriting(name: String): Module = elixirModule(name).also { ModuleRootModificationUtil.setSdkInherited(it) }

    private fun owning(name: String, sdk: Sdk): Module = elixirModule(name).also { ModuleRootModificationUtil.setModuleSdk(it, sdk) }

    /** [module] made facet-only, inheriting, and given [sdk] as its own entry. */
    private fun facetOnlyOwning(sdk: Sdk) {
        SdkFixtures.inheritingFacetModule(module)
        ModuleRootModificationUtil.setModuleSdk(module, sdk)
    }

    /** An Elixir SDK [vsn] at [home] paired with an installed Erlang SDK. */
    private fun paired(name: String, vsn: String, home: String): Sdk =
        pairedElixir(name, home, registeredErlang("$name Erlang", install("27", "27.3.4")))
            .also { SdkFixtures.commit(it) { versionString = vsn } }

    /** An Elixir SDK [vsn] paired with an installed Erlang SDK, which the rows take. */
    private fun working(name: String, vsn: String = "1.18.4"): Sdk = paired(name, vsn, elixirInstall(vsn, "27"))

    /** An Elixir SDK paired with no Erlang SDK, which a changed row refuses. */
    private fun broken(): Sdk = SdkFixtures.register(SdkFixtures.elixirSdk(BROKEN, elixirInstall("1.18.4", "27")), testRootDisposable)

    /** An Elixir SDK [vsn] paired with an installed Erlang SDK, whose own home is gone, which a pick refuses. */
    private fun gone(vsn: String = "1.19.0"): Sdk = paired(GONE, vsn, fakeHome("elixir/$GONE"))

    private fun projectSdk(sdk: Sdk) = WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = sdk }

    private val projectSdk: Sdk? get() = ProjectRootManager.getInstance(project).projectSdk

    private fun rows(focus: Module? = null, modules: List<Module> = listOfNotNull(focus)): SdkRowsDialog =
        rowsDialog(project, testRootDisposable, modules, focus)

    private fun ask(
        focus: Module? = null,
        modules: List<Module> = listOfNotNull(focus),
        ok: Boolean = true,
        gestures: SdkRowsDialog.() -> Unit = {},
    ): SdkRowsAnswer? = askRows(project, testRootDisposable, modules, focus, ok, gestures)

    private val tableNames: List<String> get() = ProjectJdkTable.getInstance().allJdks.map { it.name }.sorted()

    private fun removeFromTable(sdk: Sdk) {
        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(sdk) }
        SdkFixtures.settle()
    }

    private fun folder(module: Module) {
        PsiTestUtil.addContentRoot(module, tempDir.createVirtualDir())
    }

    private fun folderOf(module: Module): Path = ModuleRootManager.getInstance(module).contentRoots.single().toNioPath()

    private fun inModuleOrder(vararg modules: Module): List<Module> = ModuleManager.getInstance(project).modules.filter { it in modules }

    private fun waitForEntry(row: SdkRowsDialog.Row) = SdkFixtures.waitUntil("the mise entry is listed") { ENTRY in row.listed }

    fun testModuleRowsFollowTheProjectSdkRow() {
        val older = working("Rows older", "1.17.3")
        working("Rows newer", "1.18.4")
        val alpha = inheriting("alpha")
        val rows = rows()

        rows.projectRow!!.pick("Rows older")

        val row = rows.row(alpha)
        assertEquals(PROJECT_SDK, row.shown)
        assertSame(older, row.combo.getSelectedSdk())
    }

    fun testJdkProjectSdkOffersKeep() {
        SdkFixtures.projectJdk(project, "JDK 21", testRootDisposable)
        working("Rows Elixir")

        val row = rows().projectRow!!

        assertEquals("Keep JDK 21", row.listed.first())
        assertEquals("Rows Elixir", row.shown)
    }

    fun testKeepWritesNothing() {
        val jdk = SdkFixtures.projectJdk(project, "JDK 21", testRootDisposable)
        working("Rows Elixir")
        val alpha = inheriting("alpha")

        val answer = ask { projectRow!!.pick("Keep JDK 21") }

        assertEquals(SdkRowsAnswer(null, emptyMap(), null), answer)
        assertSame(jdk, projectSdk)
        assertTrue(ModuleRootManager.getInstance(alpha).isSdkInherited)
    }

    fun testOnKeepAModuleRowOnProjectSdkShowsNone() {
        SdkFixtures.projectJdk(project, "JDK 21", testRootDisposable)
        working("Rows Elixir")
        val alpha = inheriting("alpha")
        val rows = rows()
        val row = rows.row(alpha)
        assertEquals(PROJECT_SDK, row.shown)

        rows.projectRow!!.pick("Keep JDK 21")

        assertEquals(NONE, row.shown)
    }

    fun testOnKeepAModuleRowChangedBackToProjectSdkShowsNone() {
        SdkFixtures.projectJdk(project, "JDK 21", testRootDisposable)
        working("Rows Elixir")
        val alpha = inheriting("alpha")
        val rows = rows()
        val row = rows.row(alpha)
        row.pick("Rows Elixir")
        row.pick(PROJECT_SDK)

        rows.projectRow!!.pick("Keep JDK 21")

        assertEquals(NONE, row.shown)
    }

    fun testNoElixirSdkShowsNoProjectSdk() {
        val alpha = inheriting("alpha")

        val rows = rows()

        val projectRow = rows.projectRow!!
        assertEquals(NO_PROJECT_SDK, projectRow.drawn)
        assertFalse(PROJECT_SDK in projectRow.listed)
        val row = rows.row(alpha)
        assertEquals(NONE, row.shown)
        assertFalse(PROJECT_SDK in row.listed)
    }

    fun testJdkWithNoElixirSdkKeepsTheJdk() {
        val jdk = SdkFixtures.projectJdk(project, "JDK 21", testRootDisposable)

        val answer = ask { assertEquals("Keep JDK 21", projectRow!!.shown) }

        assertEquals(SdkRowsAnswer(null, emptyMap(), null), answer)
        assertSame(jdk, projectSdk)
    }

    fun testTheProjectSdkRowNeverListsProjectSdk() {
        broken()
        val rows = rows()
        val projectRow = rows.projectRow!!

        projectRow.pick(BROKEN)

        assertFalse(PROJECT_SDK in projectRow.combo.openPopup().map { it.text() })
    }

    fun testAModuleRowListsProjectSdkOnceTheProjectSdkRowIsElixir() {
        broken()
        val alpha = inheriting("alpha")
        val rows = rows()
        val row = rows.row(alpha)
        assertFalse(PROJECT_SDK in row.listed)

        rows.projectRow!!.pick(BROKEN)

        assertTrue(PROJECT_SDK in row.listed)
    }

    fun testAModuleRowWithItsOwnSdkListsProjectSdkOnceTheProjectSdkRowIsElixir() {
        val alpha = owning("alpha", broken())
        val rows = rows()
        val row = rows.row(alpha)
        assertFalse(PROJECT_SDK in row.listed)

        rows.projectRow!!.pick(BROKEN)

        assertTrue(PROJECT_SDK in row.listed)
    }

    fun testAFacetOnlyModulesOwnEntryIsSetToInherit() {
        projectSdk(working("Rows project"))
        val own = working("Rows own")
        facetOnlyOwning(own)

        val answer = ask {
            val row = row(module)
            assertEquals(listOf(PROJECT_SDK), row.listed)
            assertEquals(PROJECT_SDK, row.shown)
        }

        assertEquals(RowPick.Inherit, answer!!.rows[module])
        assertTrue(ModuleRootManager.getInstance(module).isSdkInherited)
    }

    fun testOnKeepAFacetOnlyModuleKeepsItsOwnEntry() {
        SdkFixtures.projectJdk(project, "JDK 21", testRootDisposable)
        val own = working("Rows own")
        facetOnlyOwning(own)

        val answer = ask { projectRow!!.pick("Keep JDK 21") }

        assertFalse(module in answer!!.rows)
        assertSame(own, ModuleRootManager.getInstance(module).sdk)
    }

    fun testOverAJdkTheProjectSdkRowsElixirPickSetsAFacetOnlyModulesOwnEntryToInherit() {
        SdkFixtures.projectJdk(project, "JDK 21", testRootDisposable)
        val own = working("Rows own")
        facetOnlyOwning(own)

        val answer = ask { assertEquals("Rows own", projectRow!!.shown) }

        assertSame(own, answer!!.projectSdk)
        assertEquals(RowPick.Inherit, answer.rows[module])
        assertTrue(ModuleRootManager.getInstance(module).isSdkInherited)
    }

    fun testADanglingOwnSdkIsShownAndKept() {
        projectSdk(working("Rows project"))
        val gone = working("Rows gone")
        val alpha = owning("alpha", gone)
        removeFromTable(gone)

        val answer = ask { assertEquals("invalid Rows gone", row(alpha).shown) }

        assertFalse(alpha in answer!!.rows)
        assertFalse(ModuleRootManager.getInstance(alpha).isSdkInherited)
        assertEquals("Rows gone", ModuleRootManager.getInstance(alpha).orderEntries.filterIsInstance<ModuleJdkOrderEntry>().single().jdkName)
    }

    fun testAProjectSdkPickWritesInherit() {
        projectSdk(working("Rows project"))
        val alpha = owning("alpha", working("Rows own"))

        val answer = ask { row(alpha).pick(PROJECT_SDK) }

        assertEquals(RowPick.Inherit, answer!!.rows[alpha])
        assertTrue(ModuleRootManager.getInstance(alpha).isSdkInherited)
    }

    fun testAnUntouchedOkKeepsAModulesJdk() {
        projectSdk(working("Rows project"))
        val jdk = SdkFixtures.jdk("JDK 21", testRootDisposable)
        val alpha = owning("alpha", jdk)

        val answer = ask { assertEquals(PROJECT_SDK, row(alpha).shown) }

        assertFalse(alpha in answer!!.rows)
        assertSame(jdk, ModuleRootManager.getInstance(alpha).sdk)
    }

    fun testAnUntouchedOkLeavesAModuleWithNoSdkUninherited() {
        projectSdk(working("Rows project"))
        val alpha = elixirModule("alpha")

        val answer = ask { assertEquals(PROJECT_SDK, row(alpha).shown) }

        assertFalse(alpha in answer!!.rows)
        assertFalse(ModuleRootManager.getInstance(alpha).isSdkInherited)
    }

    fun testWithNoElixirSdkToOfferAFacetOnlyModuleKeepsItsOwnEntry() {
        val own = broken()
        facetOnlyOwning(own)

        val answer = ask { assertEquals(NONE, row(module).shown) }

        assertFalse(module in answer!!.rows)
        assertSame(own, ModuleRootManager.getInstance(module).sdk)
    }

    fun testOpeningOffers() {
        projectSdk(working("Rows project", "1.17.3"))
        working("Rows newer", "1.18.4")
        val own = working("Rows own", "1.16.3")
        val alpha = owning("alpha", own)
        val beta = inheriting("beta")
        val gamma = elixirModule("gamma")

        val rows = rows()

        assertEquals("Rows project", rows.projectRow!!.shown)
        assertEquals(listOf("Rows own", PROJECT_SDK, PROJECT_SDK), listOf(alpha, beta, gamma).map { rows.row(it).shown })
    }

    fun testWithNoElixirProjectSdkTheNewestWorkingIsOffered() {
        working("Rows older", "1.17.3")
        working("Rows newer", "1.18.4")
        broken().also { SdkFixtures.commit(it) { versionString = "1.19.0" } }

        assertEquals("Rows newer", rows().projectRow!!.shown)
    }

    fun testWithNoElixirProjectSdkAnSdkWhoseHomeIsGoneIsNotOffered() {
        gone()

        assertEquals(NO_PROJECT_SDK, rows().projectRow!!.drawn)
    }

    fun testFacetOnlyRowOffersOnlyProjectSdk() {
        working("Rows Elixir")
        SdkFixtures.inheritingFacetModule(module)

        val answer = ask {
            val row = row(module)
            assertEquals(listOf(PROJECT_SDK), row.listed)
            assertEquals(PROJECT_SDK, row.shown)
            assertEmpty(row.combo.openPopup().filterIsInstance<SdkListItem.ActionItem>())
        }

        assertFalse(module in answer!!.rows)
    }

    fun testPastTenRowsTheRowsScrollAndManageSdksStaysOutside() {
        working("Rows working")
        val modules = (1..12).map { inheriting("m$it") }
        val rows = rows()

        val scroll = UIUtil.getParentOfType(JBScrollPane::class.java, rows.row(modules.last()).combo)!!

        assertTrue("shorter than its rows", scroll.preferredSize.height < scroll.viewport.view.preferredSize.height)
        assertNull(UIUtil.getParentOfType(JBScrollPane::class.java, rows.manageSdksLink))
    }

    fun testUpToTenRowsShowWhole() {
        working("Rows working")
        inheriting("alpha")
        val rows = rows()

        val scroll = UIUtil.getParentOfType(JBScrollPane::class.java, rows.row(rows.moduleRows.single().module!!).combo)!!

        assertEquals(scroll.viewport.view.preferredSize.height, scroll.preferredSize.height)
    }

    fun testOkWritesOnlyChangedRows() {
        projectSdk(working("Rows project"))
        val own = working("Rows own")
        val other = working("Rows other")
        val alpha = owning("alpha", own)
        val beta = owning("beta", own)
        val gamma = inheriting("gamma")

        val answer = ask { row(alpha).pick("Rows other") }

        assertEquals(SdkRowsAnswer(null, mapOf(alpha to RowPick.Own(other)), null), answer)
        assertSame(other, ModuleRootManager.getInstance(alpha).sdk)
        assertSame(own, ModuleRootManager.getInstance(beta).sdk)
        assertTrue(ModuleRootManager.getInstance(gamma).isSdkInherited)
        assertEquals("Rows project", projectSdk?.name)
    }

    fun testCancelWritesNothing() {
        val own = working("Rows own")
        working("Rows other")
        val alpha = owning("alpha", own)

        val answer = ask(ok = false) {
            row(alpha).pick("Rows other")
            projectRow!!.pick("Rows other")
        }

        assertNull(answer)
        assertSame(own, ModuleRootManager.getInstance(alpha).sdk)
        assertNull(projectSdk)
    }

    fun testChangedBrokenRowRefusesOk() {
        val own = working("Rows own")
        projectSdk(own)
        broken()
        val alpha = owning("alpha", own)
        val rows = rows()
        val row = rows.row(alpha)

        row.pick(BROKEN)
        rows.clickOk()

        val refusal = rows.doValidateAll().single()
        assertSame(row.combo, refusal.component)
        assertEquals("Internal Erlang SDK is not configured. Set it before picking this Elixir SDK.", refusal.message)
        assertFalse(refusal.okEnabled)
        assertNull(rows.answer)
        assertSame(own, ModuleRootManager.getInstance(alpha).sdk)
    }

    fun testAChangedRowHoldingAnSdkWhoseHomeIsGoneRefusesOk() {
        val own = working("Rows own")
        projectSdk(own)
        val gone = gone()
        val alpha = owning("alpha", own)
        val rows = rows()
        val row = rows.row(alpha)

        row.pick(GONE)
        rows.clickOk()

        val refusal = rows.doValidateAll().single()
        assertSame(row.combo, refusal.component)
        assertEquals("Elixir SDK ($GONE) home directory (${gone.homePath}) does not exist. Did you uninstall it?", refusal.message)
        assertFalse(refusal.okEnabled)
        assertNull(rows.answer)
        assertSame(own, ModuleRootManager.getInstance(alpha).sdk)
    }

    fun testUnchangedBrokenRowAllowsOk() {
        projectSdk(working("Rows project"))
        val alpha = owning("alpha", broken())
        val rows = rows()

        val outline = rows.doValidateAll().single()
        rows.clickOk()

        assertSame(rows.row(alpha).combo, outline.component)
        assertTrue(outline.okEnabled)
        assertEquals(SdkRowsAnswer(null, emptyMap(), null), rows.answer)
    }

    fun testARowChangedToABrokenProjectSdkRefusesOk() {
        val own = working("Rows own")
        projectSdk(broken())
        val alpha = owning("alpha", own)
        val rows = rows()
        val row = rows.row(alpha)

        row.pick(PROJECT_SDK)
        rows.clickOk()

        val refusal = rows.doValidateAll().single { it.component === row.combo }
        assertFalse(refusal.okEnabled)
        assertNull(rows.answer)
        assertSame(own, ModuleRootManager.getInstance(alpha).sdk)
    }

    fun testAFacetOnlyModulesOwnEntryOverABrokenProjectSdkRefusesOk() {
        projectSdk(broken())
        val own = working("Rows own")
        facetOnlyOwning(own)
        val rows = rows()
        val row = rows.row(module)

        rows.clickOk()

        val refusal = rows.doValidateAll().single { it.component === row.combo }
        assertEquals("Internal Erlang SDK is not configured. Set it before picking this Elixir SDK.", refusal.message)
        assertFalse(refusal.okEnabled)
        assertNull(rows.answer)
        assertSame(own, ModuleRootManager.getInstance(module).sdk)
    }

    fun testOnKeepAModuleRowsProjectSdkPickRefusesOk() {
        SdkFixtures.projectJdk(project, "JDK 21", testRootDisposable)
        val own = working("Rows own")
        val alpha = owning("alpha", own)
        val rows = rows()
        val row = rows.row(alpha)
        rows.projectRow!!.pick("Keep JDK 21")
        row.pick(PROJECT_SDK)

        rows.clickOk()

        val refusal = rows.doValidateAll().singleOrNull { it.component === row.combo }
        assertEquals("No Elixir project SDK to inherit.", refusal?.message)
        assertFalse(refusal!!.okEnabled)
        assertNull(rows.answer)
        assertSame(own, ModuleRootManager.getInstance(alpha).sdk)
    }

    fun testWithNoProjectSdkAModuleRowsProjectSdkPickRefusesOk() {
        broken()
        val gone = working("Rows gone")
        val alpha = owning("alpha", gone)
        removeFromTable(gone)
        val rows = rows()
        val row = rows.row(alpha)
        rows.projectRow!!.pick(BROKEN)
        rows.projectRow!!.pick(NONE)
        row.pick(PROJECT_SDK)

        rows.clickOk()

        val refusal = rows.doValidateAll().singleOrNull { it.component === row.combo }
        assertEquals("No Elixir project SDK to inherit.", refusal?.message)
        assertFalse(refusal!!.okEnabled)
        assertNull(rows.answer)
        assertFalse(ModuleRootManager.getInstance(alpha).isSdkInherited)
    }

    fun testAFacetOnlyRowWithNoOwnEntryOverABrokenProjectSdkAllowsOk() {
        projectSdk(broken())
        SdkFixtures.inheritingFacetModule(module)

        val answer = ask()

        assertFalse(module in answer!!.rows)
        assertTrue(ModuleRootManager.getInstance(module).isSdkInherited)
    }

    fun testPinnedFolderRowOffersMiseEntry() {
        val alpha = inheriting("alpha")
        inheriting("beta")
        folder(alpha)
        toolManager.pinsTheOffer()
        val before = tableNames
        val rows = rows()
        val row = rows.row(alpha)

        waitForEntry(row)

        assertEquals(ENTRY, row.shown)
        assertEquals(before, tableNames)
    }

    fun testARowWithNoFolderLeavesTheProjectSdkRowTheOtherRowsPins() {
        elixirModule("alpha").also(::folder)
        elixirModule("beta")
        toolManager.pinsTheOffer()
        val rows = rows()

        waitForEntry(rows.projectRow!!)

        assertEquals(ENTRY, rows.projectRow.shown)
    }

    fun testSamePinsEverywhereWriteInherit() {
        val alpha = elixirModule("alpha").also(::folder)
        val beta = elixirModule("beta").also(::folder)
        val offer = toolManager.pinsTheOffer()

        val answer = ask {
            val projectRow = projectRow!!
            waitForEntry(projectRow)
            assertEquals(listOf(ENTRY, ENTRY, ENTRY), (listOf(projectRow) + moduleRows).map { it.shown })
        }

        val made = elixirSdksAt(offer.elixir!!.installPath).single()
        assertEquals(SdkRowsAnswer(made, mapOf(alpha to RowPick.Inherit, beta to RowPick.Inherit), null), answer?.copy(optIns = null))
        assertSame(made, projectSdk)
        assertTrue(listOf(alpha, beta).all { ModuleRootManager.getInstance(it).isSdkInherited })
    }

    fun testAModuleRowsProjectSdkPickOnTheProjectSdkRowsEntryInheritsWhatItMakes() {
        val alpha = owning("alpha", working("Rows own")).also(::folder)
        val offer = toolManager.pinsTheOffer()

        val answer = ask {
            waitForEntry(projectRow!!)
            projectRow.pick(ENTRY)
            row(alpha).pick(PROJECT_SDK)
        }

        val made = elixirSdksAt(offer.elixir!!.installPath).single()
        assertSame(made, answer!!.projectSdk)
        assertEquals(RowPick.Inherit, answer.rows[alpha])
        assertTrue(ModuleRootManager.getInstance(alpha).isSdkInherited)
    }

    fun testWhileTheProjectSdkRowHoldsTheEntryAFacetOnlyRowShowsIt() {
        elixirModule("alpha").also(::folder)
        SdkFixtures.inheritingFacetModule(module)
        folder(module)
        toolManager.pinsTheOffer()
        val rows = rows()

        waitForEntry(rows.projectRow!!)

        assertEquals(ENTRY, rows.projectRow.shown)
        assertEquals("Project SDK $ENTRY", rows.row(module).drawn)
    }

    fun testAFacetOnlyModulesPinsOfferTheProjectSdkRowItsEntry() {
        SdkFixtures.inheritingFacetModule(module)
        folder(module)
        toolManager.pinsTheOffer()
        val rows = rows()

        waitForEntry(rows.projectRow!!)

        assertTrue(rows.row(module).listsNoEntry)
    }

    fun testAFacetOnlyModulesUninstalledErlangPinIsSaid() {
        SdkFixtures.inheritingFacetModule(module)
        folder(module)
        pinsAnUninstalledErlang()
        val rows = rows()

        rows.rowsPanel.waitForLine("${module.name}: mise pins Erlang 27.3, which isn't installed")
    }

    fun testWithNoElixirSdkTheProjectSdkRowCanLeaveItsEntry() {
        elixirModule("alpha").also(::folder)
        toolManager.pinsTheOffer()

        val answer = ask {
            val projectRow = projectRow!!
            waitForEntry(projectRow)
            assertEquals(ENTRY, projectRow.shown)
            projectRow.pick(NONE)
        }

        assertNotNull(answer)
        assertNull(projectSdk)
    }

    fun testANoneModuleRowPickedOnceThereIsAProjectSdkShowsProjectSdk() {
        elixirModule("alpha").also(::folder)
        val beta = elixirModule("beta")
        toolManager.pinsTheOffer()
        val rows = rows()
        val row = rows.row(beta)
        assertEquals(NONE, row.shown)
        waitForEntry(rows.projectRow!!)
        assertEquals(PROJECT_SDK, row.shown)

        row.pick(NONE)

        assertEquals(PROJECT_SDK, row.shown)
        assertFalse(row.changed)
    }

    fun testLateMiseAnswerKeepsChangedRow() {
        working("Rows Elixir")
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta")
        val release = toolManager.holdsTheOffer()
        val rows = rows()
        val row = rows.row(alpha)

        row.pick("Rows Elixir")
        release.countDown()
        waitForEntry(row)

        assertEquals("Rows Elixir", row.shown)
    }

    fun testSdksMadeAtOkOnly() {
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta").also(::folder)
        val offer = toolManager.pinsTheOffer(onlyIn = folderOf(alpha))
        val before = tableNames

        val answer = ask {
            waitForEntry(row(alpha))
            assertEquals(before, tableNames)
        }

        val made = elixirSdksAt(offer.elixir!!.installPath).single()
        assertEquals(RowPick.Own(made), answer!!.rows[alpha])
        assertPairedWithErlangAt(made, offer.erlang!!.installPath)
    }

    fun testCancelMakesNoSdk() {
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta")
        toolManager.pinsTheOffer()
        val before = tableNames

        ask(ok = false) { waitForEntry(row(alpha)) }

        assertEquals(before, tableNames)
    }

    /** Pins of an installed Elixir and of an Erlang 27.3 that isn't installed; answers the Elixir install. */
    private fun pinsAnUninstalledErlang(): String {
        val elixirHome = elixirInstall("1.18.4", "27")
        toolManager.answer = { toolManager.success(ToolEntry("1.18.4-otp-27", elixirHome, true), ToolEntry("27.3", fakeHome("erlang/27.3"), false)) }
        return elixirHome
    }

    /** Pins whose Erlang registers nothing at OK, so the install alone decides what they make. */
    private fun pinsWhoseErlangIsGone(): String = elixirInstall("1.18.4", "27").also { toolManager.pins(it, fakeHome("erlang/gone")) }

    fun testAnEntryThatMakesNoSdkKeepsTheChooserOpen() {
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta")
        pinsWhoseErlangIsGone()
        ScriptedErlangStep(asked, ScriptedErlangStep.cancel, ScriptedErlangStep.cancel).install(testRootDisposable)
        val rows = rows()
        val row = rows.row(alpha)
        waitForEntry(row)

        rows.clickOk()

        assertNull(rows.answer)
        assertFalse("the entry is still offered", row.listsNoEntry)
        assertEquals(NONE, row.shown)
        rows.rowsPanel.waitForLine("alpha: no SDK could be made of mise's Elixir 1.18.4-otp-27 and Erlang 27.3.4")
        assertTrue(ModuleRootManager.getInstance(alpha).isSdkInherited)

        row.pick(ENTRY)
        rows.clickOk()

        assertEquals(listOf("erlang", "erlang"), asked.events)
        assertNull(rows.answer)
    }

    fun testAnEntryThatMakesARefusedSdkKeepsTheChooserOpenOnIt() {
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta")
        val elixirHome = pinsWhoseErlangIsGone()
        val unpaired = SdkFixtures.register(SdkFixtures.elixirSdk("Rows unpaired", elixirHome), testRootDisposable)
        val rows = rows()
        val row = rows.row(alpha)
        waitForEntry(row)

        rows.clickOk()

        assertNull(rows.answer)
        assertEquals(unpaired.name, row.shown)
        val refusal = rows.doValidateAll().single { it.component === row.combo }
        assertFalse(refusal.okEnabled)
        assertTrue(ModuleRootManager.getInstance(alpha).isSdkInherited)
    }

    fun testAModuleRowOnAProjectSdkEntryThatMakesNoSdkKeepsItsOwnSdk() {
        val own = working("Rows own")
        val alpha = owning("alpha", own).also(::folder)
        pinsWhoseErlangIsGone()
        ScriptedErlangStep(asked, ScriptedErlangStep.cancel).install(testRootDisposable)
        val rows = rows()
        waitForEntry(rows.projectRow!!)
        assertEquals(ENTRY, rows.projectRow.shown)

        rows.row(alpha).pick(PROJECT_SDK)
        rows.clickOk()

        assertNull(rows.answer)
        rows.rowsPanel.waitForLine("Project SDK: no SDK could be made of mise's Elixir 1.18.4-otp-27 and Erlang 27.3.4")
        assertSame(own, ModuleRootManager.getInstance(alpha).sdk)
        assertNull(projectSdk)
    }

    fun testWithNoElixirSdkAProjectSdkRowEntryThatMakesNoSdkOpensAsItDid() {
        val alpha = inheriting("alpha").also(::folder)
        pinsWhoseErlangIsGone()
        ScriptedErlangStep(asked, ScriptedErlangStep.cancel).install(testRootDisposable)
        val rows = rows()
        val projectRow = rows.projectRow!!
        waitForEntry(projectRow)
        assertEquals(ENTRY, projectRow.shown)

        rows.clickOk()

        assertNull(rows.answer)
        assertEquals(NO_PROJECT_SDK, projectRow.drawn)
        assertFalse(projectRow.changed)
        assertEquals(NONE, rows.row(alpha).shown)
    }

    fun testAfterAFailedOkAModuleRowChangedBackToProjectSdkShowsNone() {
        val alpha = inheriting("alpha").also(::folder)
        pinsWhoseErlangIsGone()
        ScriptedErlangStep(asked, ScriptedErlangStep.cancel).install(testRootDisposable)
        val rows = rows()
        val row = rows.row(alpha)
        waitForEntry(row)
        row.pick(ENTRY)
        row.pick(PROJECT_SDK)

        rows.clickOk()

        assertNull(rows.answer)
        assertEquals(NONE, row.shown)
    }

    fun testAProjectSdkRowEntryThatMakesNoSdkOpensOnTheSavedSdkAsTheTableNowHasIt() {
        projectSdk(working("Rows saved"))
        inheriting("alpha").also(::folder)
        pinsWhoseErlangIsGone()
        ScriptedErlangStep(asked, ScriptedErlangStep.cancel).install(testRootDisposable)
        val rows = rows()
        val projectRow = rows.projectRow!!
        waitForEntry(projectRow)
        projectRow.pick(ENTRY)
        SdkFixtures.renameInTable("Rows saved", "Rows renamed")
        SdkFixtures.settle()

        rows.clickOk()

        assertNull(rows.answer)
        assertEquals("Rows renamed", projectRow.shown)
        assertFalse(projectRow.changed)
    }

    fun testAnEntryThatMakesAnSdkOnlyItsErlangRepairsIsTaken() {
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta").also(::folder)
        val offer = toolManager.pinsTheOffer(onlyIn = folderOf(alpha))
        // Paired with the pinned Erlang's home, whose SDK the table has lost, so a pick refuses it until OK registers one.
        val repaired = pairedElixir("Rows repaired", offer.elixir!!.installPath, SdkFixtures.erlangSdk("Rows lost Erlang", offer.erlang!!.installPath))
        assertNotNull(pairingRefusal(repaired))
        val rows = rows()
        waitForEntry(rows.row(alpha))

        rows.clickOk()

        assertEquals(RowPick.Own(repaired), rows.answer!!.rows[alpha])
    }

    fun testUninstalledErlangPinNotOffered() {
        val alpha = inheriting("alpha").also(::folder)
        pinsAnUninstalledErlang()
        val rows = rows()

        rows.rowsPanel.waitForLine("alpha: mise pins Erlang 27.3, which isn't installed")

        assertTrue(rows.row(alpha).listsNoEntry)
    }

    fun testAnUninstalledErlangPinPicksTheInstallsOneSdk() {
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta")
        val onInstall = pairedElixir("Rows on the install", pinsAnUninstalledErlang(), registeredErlang("Rows on the install Erlang", install("27", "27.3.4")))
        working("Rows newer", "1.19.0")
        val rows = rows()
        val row = rows.row(alpha)

        SdkFixtures.waitUntil("the install's SDK is offered") { row.shown == onInstall.name }

        assertTrue(row.listsNoEntry)
    }

    fun testElixirPinWithNoErlangNotOffered() {
        val alpha = inheriting("alpha").also(::folder)
        toolManager.pins(elixirInstall("1.18.4", "27"), null)
        val rows = rows()

        rows.rowsPanel.waitForLine("alpha: mise pins Elixir 1.18.4-otp-27 but no Erlang")

        assertTrue(rows.row(alpha).listsNoEntry)
    }

    fun testMiseErrorIsSaidUntilCheckAgainOffers() {
        val alpha = inheriting("alpha").also(::folder)
        val pins = toolManager.offeredPins()
        var trusted = false
        toolManager.answer = { if (trusted) pins else ToolManagerResult.Error(toolManager.name, MiseToolManager.untrustedConfigDescription("/project/mise.toml")) }
        val rows = rows()
        val row = rows.row(alpha)

        rows.rowsPanel.waitFor("alpha's error") { "alpha: " in it && "is not trusted." in it }
        assertTrue(row.listsNoEntry)

        trusted = true
        rows.rowsPanel.waitFor("Check again").doClick()

        waitForEntry(row)
        assertNull(rows.rowsPanel.shown { "is not trusted." in it })
    }

    fun testCheckAgainKeepsOnlyTheLatestCheck() {
        val alpha = inheriting("alpha").also(::folder)
        val pins = toolManager.offeredPins()
        val untrusted = ToolManagerResult.Error(toolManager.name, MiseToolManager.untrustedConfigDescription("/project/mise.toml"))
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        toolManager.answer = {
            when (calls.incrementAndGet()) {
                1 -> untrusted
                2 -> untrusted.also { release.await(10, TimeUnit.SECONDS) }
                else -> pins
            }
        }
        val rows = rows()
        val row = rows.row(alpha)
        rows.rowsPanel.waitFor("alpha's error") { "is not trusted." in it }

        rows.rowsPanel.waitFor("Check again").doClick()
        SdkFixtures.waitUntil("the second check is asking") { calls.get() == 2 }
        val second = row.check!!
        rows.rowsPanel.waitFor("Check again").doClick()
        waitForEntry(row)
        assertFalse("the second check must still be pending", second.isCompleted)
        release.countDown()
        SdkFixtures.waitUntil("the second check has ended") { second.isCompleted }
        dispatchForAWhile()

        assertNull(rows.rowsPanel.shown { "is not trusted." in it })
        assertEquals(ENTRY, row.shown)
    }

    fun testOneSdkOnAnUnpairedPinsInstallIsOffered() {
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta")
        val elixirHome = elixirInstall("1.18.4", "27")
        val onInstall = pairedElixir("Rows on the install", elixirHome, registeredErlang("Rows on the install Erlang", install("27", "27.3.4")))
        working("Rows newer", "1.19.0")
        toolManager.pins(elixirHome, null)
        val rows = rows()
        val row = rows.row(alpha)

        SdkFixtures.waitUntil("the install's SDK is offered") { row.shown == onInstall.name }

        assertTrue(row.listsNoEntry)
    }

    fun testSeveralSdksOnPinnedInstallOffered() {
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta")
        val elixirHome = elixirInstall("1.18.4", "27")
        val several = twoSdksOnTheInstall(elixirHome, "Rows several")
        toolManager.pins(elixirHome, null)
        val rows = rows()
        val row = rows.row(alpha)
        val before = row.shown

        SdkFixtures.waitUntil("the folder's check has ended") { row.check?.isCompleted == true }

        assertTrue(row.listed.containsAll(several.map { it.name }))
        assertTrue(row.listsNoEntry)
        assertEquals(before, row.shown)
        assertNull(rows.rowsPanel.shown { it.startsWith("alpha: mise pins") })
    }

    fun testAnEntryThatMakesSeveralSdksListsThemInItsPlace() {
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta")
        val several = twoSdksOnTheInstall(pinsWhoseErlangIsGone(), "Rows several")
        val rows = rows()
        val row = rows.row(alpha)
        waitForEntry(row)

        rows.clickOk()

        assertNull(rows.answer)
        assertTrue(row.listsNoEntry)
        assertTrue(row.listed.containsAll(several.map { it.name }))
        rows.rowsPanel.waitForLine("alpha: mise's Elixir 1.18.4-otp-27 install has several SDKs; pick one")
        assertTrue(ModuleRootManager.getInstance(alpha).isSdkInherited)
    }

    fun testBoxesShowSettings() {
        ToolManagerSettings.adopt(project, ToolManagerOptIns(toolManager.name, environment = false))
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta")
        toolManager.pinsTheOffer()
        val rows = rows()
        assertNull(rows.rowsPanel.shown { it == IN_STEP })

        waitForEntry(rows.row(alpha))

        assertTrue(rows.rowsPanel.waitFor(IN_STEP).isSelected)
        assertFalse(rows.rowsPanel.waitFor(ENVIRONMENT).isSelected)
    }

    fun testWithMiseOffTheBoxIsUntickedAndAnUntouchedOkLeavesItOff() {
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta")
        toolManager.pinsTheOffer()

        val answer = ask {
            waitForEntry(row(alpha))
            assertFalse(rowsPanel.waitFor(IN_STEP).isSelected)
        }

        assertNull(answer!!.optIns)
        assertFalse(ToolManagerSettings.getInstance(project).isEnabled(toolManager))
    }

    fun testTheBoxesHideOnceNoRowHoldsAnEntry() {
        working("Rows Elixir")
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta").also(::folder)
        toolManager.pinsTheOffer(onlyIn = folderOf(alpha))
        val rows = rows()
        waitForEntry(rows.row(alpha))
        rows.rowsPanel.waitFor(IN_STEP)

        rows.row(alpha).pick("Rows Elixir")

        assertNull(rows.rowsPanel.shown { it == IN_STEP })
    }

    fun testUntickingTurnsMiseOff() {
        ToolManagerSettings.adopt(project, ToolManagerOptIns(toolManager.name, environment = true))
        val alpha = inheriting("alpha").also(::folder)
        inheriting("beta")
        toolManager.pinsTheOffer()

        val answer = ask {
            waitForEntry(row(alpha))
            rowsPanel.waitFor(IN_STEP).doClick()
        }

        assertEquals(ToolManagerOptIns(toolManager.name, environment = true, enabled = false), answer!!.optIns)
        val settings = ToolManagerSettings.getInstance(project)
        assertFalse(settings.isEnabled(toolManager))
        assertFalse(settings.isEnvironmentEnabled(toolManager))
    }

    fun testNoDetectedRow() {
        val alpha = inheriting("alpha")
        val detected = elixirInstall("1.18.4", "27")
        ApplicationManager.getApplication().replaceService(SdkDetector::class.java, DetectingSdkDetector(detected), testRootDisposable)
        val control = SdkComboBox(SdkComboBoxModel.createSdkComboBoxModel(project, ProjectSdksModel().apply { reset(project) }, elixirOnly))
        assertTrue("the control lists the SDK detected", control.openPopup().any { it is SdkListItem.SuggestedItem })

        val items = rows().row(alpha).combo.openPopup()

        assertEmpty(items.filterIsInstance<SdkListItem.SuggestedItem>())
        assertTrue("Add is kept", items.any { it is SdkListItem.ActionItem && it.role == SdkListItem.ActionRole.ADD && it.action.sdkType === Type.instance })
    }

    /** Picks the row's "Add Elixir SDK from disk...", with the install and the Erlang step scripted. */
    private fun SdkRowsDialog.Row.addFromDisk() {
        combo.selectedItem = combo.openPopup().single { it is SdkListItem.ActionItem && it.role == SdkListItem.ActionRole.ADD }
    }

    private fun scriptTheAdd(): String {
        val erlangHome = install("27", "27.3.4")
        SdkFixtures.register(SdkFixtures.erlangSdk("Rows installed Erlang", erlangHome), testRootDisposable)
        ScriptedFileChooserFactory(asked, elixirInstall("1.18.4", "27")).install(testRootDisposable)
        ScriptedErlangStep(asked, ScriptedErlangStep.pick("Rows installed Erlang")).install(testRootDisposable)
        return erlangHome
    }

    fun testAddFromDiskSelectsTheNewSdk() {
        val alpha = inheriting("alpha")
        val erlangHome = scriptTheAdd()

        val answer = ask {
            val row = row(alpha)
            row.addFromDisk()
            val created = Facet.sdks().single()
            assertEquals(created.name, row.shown)
            assertTrue("the other rows list it", created.name in projectRow!!.listed)
        }

        val created = Facet.sdks().single()
        assertEquals(RowPick.Own(created), answer!!.rows[alpha])
        assertPairedWithErlangAt(created, erlangHome)
    }

    fun testCancelKeepsTheAddedSdk() {
        val alpha = inheriting("alpha")
        scriptTheAdd()

        ask(ok = false) { row(alpha).addFromDisk() }

        assertEquals(1, Facet.sdks().size)
        assertTrue(ModuleRootManager.getInstance(alpha).isSdkInherited)
    }

    fun testManageSdksOpensTheSdksPage() {
        val recorded = RecordedChooser(testRootDisposable)

        rows().manageSdksLink.doClick()

        assertEquals(listOf(project to SettingsPage.SDKS), recorded.settingsOpened)
    }

    fun testAnSdkAddedToTheTableIsListedAndThePickKept() {
        working("Rows A")
        val alpha = inheriting("alpha")
        val rows = rows()
        val row = rows.row(alpha)
        row.pick("Rows A")

        working("Rows B")
        SdkFixtures.settle()

        assertEquals(listOf("Rows A", "Rows B"), row.listed.filter { it.startsWith("Rows ") })
        assertEquals("Rows A", row.shown)
        assertEquals(1, tableNames.count { it == "Rows B" })
    }

    fun testRemovingAPickedSdkFromTheTableWritesNothing() {
        val own = working("Rows own")
        val picked = working("Rows picked")
        val alpha = owning("alpha", own)

        val answer = ask {
            val row = row(alpha)
            row.pick("Rows picked")
            removeFromTable(picked)
            assertFalse("Rows picked" in row.listed)
            assertEquals("invalid Rows picked", row.shown)
        }

        assertFalse(alpha in answer!!.rows)
        assertSame(own, ModuleRootManager.getInstance(alpha).sdk)
    }

    fun testRemovingTheProjectSdkRowsPickTakesItFromTheModuleRows() {
        val picked = working("Rows picked")
        val alpha = inheriting("alpha")
        val rows = rows()
        assertEquals("Project SDK Rows picked", rows.row(alpha).drawn)

        removeFromTable(picked)

        assertEquals("invalid Rows picked", rows.projectRow!!.shown)
        assertEquals(NONE, rows.row(alpha).shown)
    }

    fun testAPairingCommittedWhileTheChooserIsOpenLetsItsPickThrough() {
        val own = working("Rows own")
        projectSdk(own)
        val unpaired = broken()
        val alpha = owning("alpha", own)
        // Registered first, so the table's one change while the chooser is open is the pairing's edit.
        val erlang = registeredErlang("Rows Erlang", install("27", "27.3.4"))
        val rows = rows()
        rows.row(alpha).pick(BROKEN)
        assertNotEmpty(rows.doValidateAll())

        SdkFixtures.commitPairing(unpaired, erlang)
        SdkFixtures.settle()
        assertEmpty(rows.doValidateAll())
        rows.clickOk()

        assertEquals(RowPick.Own(unpaired), rows.answer!!.rows[alpha])
    }

    fun testARenamedSdkKeepsItsRow() {
        val own = working("Rows own")
        val alpha = owning("alpha", own)
        val rows = rows()

        SdkFixtures.renameInTable("Rows own", "Rows renamed")
        SdkFixtures.settle()

        assertEquals("Rows renamed", rows.row(alpha).shown)
        rows.clickOk()

        assertFalse(alpha in rows.answer!!.rows)
        assertSame(own, ModuleRootManager.getInstance(alpha).sdk)
    }

    fun testBannerRowFirstAndFocused() {
        val alpha = inheriting("alpha")
        val beta = inheriting("beta")
        val gamma = inheriting("gamma")

        val rows = rows(focus = beta)

        assertEquals(listOf(beta) + inModuleOrder(alpha, gamma), rows.moduleRows.map { it.module })
        assertSame(rows.row(beta).combo, rows.preferredFocusedComponent)
    }

    fun testOtherAsksKeepModuleOrder() {
        SdkFixtures.inheritingFacetModule(module)
        val alpha = inheriting("alpha")
        val beta = inheriting("beta")

        val rows = rows()

        assertEquals(inModuleOrder(module, alpha, beta), rows.moduleRows.map { it.module })
        assertSame(rows.row(alpha).combo, rows.preferredFocusedComponent)
    }

    fun testAnAskedModuleThatIsNotElixirsGetsARow() {
        working("Rows Elixir")

        val rows = rows(focus = module)

        assertEquals(listOf(module), rows.moduleRows.map { it.module })
        assertSame(rows.projectRow!!.combo, rows.preferredFocusedComponent)
    }

    fun testAModuleAskedForWithNoBannerGetsARow() {
        working("Rows Elixir")

        val rows = rows(modules = listOf(module))

        assertEquals(listOf(module), rows.moduleRows.map { it.module })
    }

    fun testWithNoElixirModuleRowTheProjectSdkRowIsFocused() {
        SdkFixtures.inheritingFacetModule(module)

        val rows = rows()

        assertSame(rows.projectRow!!.combo, rows.preferredFocusedComponent)
    }

    fun testFacetOnlyBannerFocusesTheProjectSdkRow() {
        val alpha = inheriting("alpha")
        SdkFixtures.inheritingFacetModule(module)

        val rows = rows(focus = module)

        assertEquals(listOf(module, alpha), rows.moduleRows.map { it.module })
        assertSame(rows.projectRow!!.combo, rows.preferredFocusedComponent)
    }
}

/** A small IDE: a row per Elixir module, each writing its facet, and no Project SDK row. */
class SdkRowsDialogSmallIdeTest : SmallIdeFacetHeavyTestCase() {
    fun testSmallIdeRowsSetFacets() {
        val before = models.register("Rows before")
        rowsPick()
        FacetModelFixtures.assign(module, before)
        val other = createModule("other").also { FacetModelFixtures.assign(it, before) }

        val answered = askRows(project, testRootDisposable) {
            assertNull(projectRow)
            assertSame(moduleRows.first().combo, preferredFocusedComponent)
            row(module).pick("Rows pick")
        }

        assertEquals(mapOf(module to RowPick.Own(Facet.sdkNamed("Rows pick")!!)), answered!!.rows)
        assertEquals("Rows pick", FacetModelFixtures.storedName(module))
        assertEquals("Rows before", FacetModelFixtures.storedName(other))
    }

    fun testTheBannersModuleWithNoFacetGetsARowAndOkGivesItTheFacet() {
        rowsPick()

        askRows(project, testRootDisposable, listOf(module), focus = module) {
            assertSame(row(module).combo, preferredFocusedComponent)
            row(module).pick("Rows pick")
        }

        assertEquals("Rows pick", FacetModelFixtures.storedName(module))
    }

    fun testAFacetNamingAnSdkTheTableLostIsShownAndKept() {
        val gone = models.register("Rows gone")
        FacetModelFixtures.assign(module, gone)
        models.remove(gone)
        SdkFixtures.settle()

        val answered = askRows(project, testRootDisposable) { assertEquals("invalid Rows gone", row(module).shown) }

        assertEmpty(answered!!.rows.keys)
        assertEquals("Rows gone", FacetModelFixtures.storedName(module))
    }

    /** "Rows pick", an Elixir SDK paired with an installed Erlang SDK. */
    private fun rowsPick() {
        pairedForNext("Rows pick", "Rows pick Erlang", testRootDisposable)
    }
}

/** The rows dialog over [project] for [modules], focused by [focus], disposed with [disposable]. */
private fun rowsDialog(project: Project, disposable: Disposable, modules: List<Module>, focus: Module?): SdkRowsDialog =
    SdkRowsDialog(project, null, modules, focus).also { Disposer.register(disposable, it.disposable) }

/**
 * Asks [project]'s chooser for [modules], focused by [focus], its rows dialog driven by [gestures] then closed by OK, or
 * Cancel unless [ok]; answers its answer.
 */
private fun askRows(
    project: Project,
    disposable: Disposable,
    modules: List<Module> = emptyList(),
    focus: Module? = null,
    ok: Boolean = true,
    gestures: SdkRowsDialog.() -> Unit = {},
): SdkRowsAnswer? {
    var answered: SdkRowsAnswer? = null
    SdkChoice { _, askedFor, _, focused ->
        rowsDialog(project, disposable, askedFor, focused).run {
            gestures()
            if (ok) clickOk() else doCancelAction()
            answer.also { answered = it }
        }
    }.ask(project, modules, focus = focus)
    return answered
}

/** A detector that finds an Elixir install at [home] whenever a field asks. */
private class DetectingSdkDetector(private val home: String) : SdkDetector() {
    override fun getDetectedSdksWithUpdate(project: Project?, lifetime: Disposable, callbackModality: ModalityState, listener: DetectedSdkListener) {
        listener.onSearchStarted()
        listener.onSdkDetected(Type.instance as SdkType, "1.18.4", home)
        listener.onSearchCompleted()
    }
}

private const val PROJECT_SDK = "Project SDK"
private const val NONE = "<None>"
private const val NO_PROJECT_SDK = "<No Project SDK>"
private const val ENTRY = "mise: Elixir 1.18.4-otp-27, Erlang 27.3.4"
private const val BROKEN = "Rows broken"
private const val GONE = "Rows gone"
