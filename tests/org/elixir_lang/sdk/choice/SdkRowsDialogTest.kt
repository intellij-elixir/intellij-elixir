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
import org.elixir_lang.tool_manager.assertPairedWithErlangAt
import org.elixir_lang.tool_manager.pairedElixir
import org.elixir_lang.tool_manager.registeredErlang
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

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        asked.recordMessages(testRootDisposable)
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

    private fun rows(focus: Module? = null): SdkRowsDialog =
        SdkRowsDialog(project, null).also { Disposer.register(testRootDisposable, it.disposable) }

    /** Asks the chooser, its rows dialog driven by [gestures] then closed by OK, or Cancel unless [ok]; answers its answer. */
    private fun ask(focus: Module? = null, ok: Boolean = true, gestures: SdkRowsDialog.() -> Unit = {}): SdkRowsAnswer? {
        var answered: SdkRowsAnswer? = null
        SdkChoice { _, _, _, focused ->
            rows(focused).run {
                gestures()
                if (ok) clickOk() else doCancelAction()
                answer.also { answered = it }
            }
        }.ask(project, emptyList(), focus = focus)
        return answered
    }

    private fun removeFromTable(sdk: Sdk) {
        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(sdk) }
        SdkFixtures.settle()
    }

    private fun inModuleOrder(vararg modules: Module): List<Module> = ModuleManager.getInstance(project).modules.filter { it in modules }

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

    fun testPastTenRowsTheRowsScroll() {
        working("Rows working")
        val modules = (1..12).map { inheriting("m$it") }
        val rows = rows()

        val scroll = UIUtil.getParentOfType(JBScrollPane::class.java, rows.row(modules.last()).combo)!!

        assertTrue("shorter than its rows", scroll.preferredSize.height < scroll.viewport.view.preferredSize.height)
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

    fun testAFacetOnlyRowWithNoOwnEntryOverABrokenProjectSdkAllowsOk() {
        projectSdk(broken())
        SdkFixtures.inheritingFacetModule(module)

        val answer = ask()

        assertFalse(module in answer!!.rows)
        assertTrue(ModuleRootManager.getInstance(module).isSdkInherited)
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

}

/** A small IDE: a row per Elixir module, each writing its facet, and no Project SDK row. */
class SdkRowsDialogSmallIdeTest : SmallIdeFacetHeavyTestCase() {
    fun testSmallIdeRowsSetFacets() {
        val before = models.register("Rows before")
        val erlang = SdkFixtures.register(SdkFixtures.erlangSdk("Rows pick Erlang", install("27", "27.3.4")), testRootDisposable)
        SdkFixtures.commitPairing(SdkFixtures.register(SdkFixtures.elixirSdk("Rows pick", elixirInstall("1.18.4", "27")), testRootDisposable), erlang)
        FacetModelFixtures.assign(module, before)
        val other = createModule("other").also { FacetModelFixtures.assign(it, before) }
        var answered: SdkRowsAnswer? = null

        SdkChoice { _, _, _, focus ->
            SdkRowsDialog(project, null).also { Disposer.register(testRootDisposable, it.disposable) }.run {
                assertNull(projectRow)
                row(module).pick("Rows pick")
                clickOk()
                answer.also { answered = it }
            }
        }.ask(project, emptyList())

        assertEquals(mapOf(module to RowPick.Own(Facet.sdkNamed("Rows pick")!!)), answered!!.rows)
        assertEquals("Rows pick", FacetModelFixtures.storedName(module))
        assertEquals("Rows before", FacetModelFixtures.storedName(other))
    }

    fun testAFacetNamingAnSdkTheTableLostIsShownAndKept() {
        val gone = models.register("Rows gone")
        FacetModelFixtures.assign(module, gone)
        models.remove(gone)
        SdkFixtures.settle()
        var answered: SdkRowsAnswer? = null

        SdkChoice { _, _, _, _ ->
            SdkRowsDialog(project, null).also { Disposer.register(testRootDisposable, it.disposable) }.run {
                assertEquals("invalid Rows gone", row(module).shown)
                clickOk()
                answer.also { answered = it }
            }
        }.ask(project, emptyList())

        assertEmpty(answered!!.rows.keys)
        assertEquals("Rows gone", FacetModelFixtures.storedName(module))
    }
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
private const val BROKEN = "Rows broken"
private const val GONE = "Rows gone"
