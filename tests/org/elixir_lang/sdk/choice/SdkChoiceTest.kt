package org.elixir_lang.sdk.choice

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.EelOsFamily
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import org.elixir_lang.Facet
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkEnvironment
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.SdkFixtures.storedErlangName
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.elixir.SettingsPage
import org.elixir_lang.sdk.elixir.Type as ElixirType
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.ScriptedErlangStep
import org.elixir_lang.sdk.erlang_dependent.ScriptedFileChooserFactory
import org.elixir_lang.sdk.erlang_dependent.StepAnswer
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.sdk.wsl.LEGACY_WSL_PREFIX
import org.elixir_lang.sdk.wsl.MODERN_WSL_PREFIX
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
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
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton

/** The wizards' chooser on IntelliJ IDEA: its state, its panel, and what its pick writes. */
class SdkChoiceTest : HeavyTestCase() {
    private val asked = Asked()
    private lateinit var table: SdkFixtures.TableSnapshot
    private val toolManager = FakeToolManager()

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        asked.recordMessages(testRootDisposable)
        toolManager.install(testRootDisposable)
        // As a module IntelliJ IDEA creates; a test module has no SDK entry.
        ModuleRootModificationUtil.setSdkInherited(module)
    }

    override fun tearDown() = runAll(
        { table.restore() },
        { super.tearDown() },
    )

    private fun elixir(name: String): Sdk = SdkFixtures.register(SdkFixtures.elixirSdk(name, fakeHome("elixir/$name")), testRootDisposable)

    private fun inheritingModule(name: String): Module = createModule(name).also { ModuleRootModificationUtil.setSdkInherited(it) }

    /** "Choice Erlang", at an OTP 27.3.4 home. */
    private fun erlang(): Sdk {
        val home = fakeHome("erlang/27.3.4")
        SdkVersionsStore.getInstance().setOtpVersion(home, "27.3.4")
        return SdkFixtures.register(SdkFixtures.erlangSdk("Choice Erlang", home), testRootDisposable)
    }

    /** Add...'s create flow scripted: the home chooser picks a new Elixir install, and the Erlang step answers [answer]. */
    private fun scriptAdd(answer: StepAnswer): ScriptedErlangStep {
        SdkFixtures.register(SdkFixtures.erlangSdk("Choice installed Erlang", install("27", "27.3.4")), testRootDisposable)
        ScriptedFileChooserFactory(asked, elixirInstall("1.18.4", "27")).install(testRootDisposable)
        return ScriptedErlangStep(asked, answer).install(testRootDisposable)
    }

    private val pickInstalledErlang: StepAnswer = ScriptedErlangStep.pick("Choice installed Erlang")

    private fun SdkChoiceState.names(): List<String> = entries.map { it.sdk.name }

    /** Writes [sdk] to [module] as a wizard writes its chooser's pick, with no opt-ins. */
    private fun write(sdk: Sdk) = writePick(project, listOf(module), sdk, null)

    /** Each state's last pick, as its panel's `onPick` told it: a wizard's Next is enabled while it is a pick. */
    private val lastPicks = mutableMapOf<SdkChoiceState, SdkChoicePick?>()

    /** Whether the panel's last `onPick` answer is a pick. */
    private val SdkChoicePanel.picked: Boolean get() = lastPicks[state] != null

    /** The panel's last `onPick` answer; fails if it has given none. */
    private val SdkChoicePanel.lastPick: SdkChoicePick? get() = lastPicks.getValue(state)

    /** The chooser's panel over the table's Elixir SDKs, with the tool managers asked about [folders]. */
    private fun panel(folders: List<Path> = emptyList()): SdkChoicePanel {
        val state = state(folders = folders)
        return SdkChoicePanel(state, testRootDisposable) { pick -> lastPicks[state] = pick }
    }

    fun testEveryElixirSdkIsListedByNameWithItsPairing() {
        val erlang = erlang()
        SdkFixtures.commitPairing(elixir("Choice B"), erlang)
        elixir("Choice A")

        val panel = panel()

        assertEquals(
            listOf("Choice A" to null, "Choice B" to ("Choice Erlang" to "27.3.4")),
            panel.state.entries.map { entry -> entry.sdk.name to entry.erlang?.let { it.name to it.otpVersion } },
        )
    }

    fun testEachRowShowsItsPairing() {
        SdkFixtures.commitPairing(elixir("Choice paired"), erlang())
        elixir("Choice unpaired")
        val panel = panel()

        assertEquals(
            listOf("Choice paired  Erlang SDK 'Choice Erlang', OTP 27.3.4", "Choice unpaired  no Erlang SDK"),
            panel.state.entries.map(panel::rowText),
        )
    }

    fun testNothingIsSelected() {
        elixir("Choice A")

        assertNull(panel().state.selected)
    }

    fun testNothingIsPickedUntilAPick() {
        elixir("Choice A")
        val panel = panel()

        assertFalse("nothing is picked yet", panel.picked)

        panel.pick(panel.state.entries.single())

        assertTrue(panel.picked)
    }

    fun testAnUnpairedSdkCanBePicked() {
        elixir("Choice unpaired")
        val panel = panel()

        panel.pick(panel.state.entries.single())

        assertEquals("Choice unpaired", panel.state.selected?.sdk?.name)
    }

    fun testOkWritesThePickToEveryModule() {
        val pick = elixir("Choice pick")
        val other = inheritingModule("Choice other module")

        writePick(project, listOf(module, other), pick, null)

        assertSame(pick, ProjectRootManager.getInstance(project).projectSdk)
        assertSame(pick, ModuleRootManager.getInstance(module).sdk)
        assertSame(pick, ModuleRootManager.getInstance(other).sdk)
    }

    private fun projectJdk(): Sdk = SdkFixtures.projectJdk(project, "Choice JDK", testRootDisposable)

    fun testAModuleInheritingAProjectJdkKeepsIt() {
        val jdk = projectJdk()
        val java = inheritingModule("Choice Java module")
        val pick = elixir("Choice pick")

        write(pick)

        assertSame(pick, ProjectRootManager.getInstance(project).projectSdk)
        assertSame(jdk, ModuleRootManager.getInstance(java).sdk)
    }

    fun testAFacetModuleInheritingAProjectJdkKeepsIt() {
        val jdk = projectJdk()
        val facetModule = SdkFixtures.inheritingFacetModule(createModule("Choice facet module"))

        write(elixir("Choice pick"))

        assertFalse(ModuleRootManager.getInstance(facetModule).isSdkInherited)
        assertSame(jdk, ModuleRootManager.getInstance(facetModule).sdk)
    }

    fun testWithNoProjectSdkNoOtherModuleIsGivenAnEntry() {
        val other = inheritingModule("Choice other module")

        write(elixir("Choice pick"))

        assertTrue(ModuleRootManager.getInstance(other).isSdkInherited)
    }

    fun testThePickIsNeverPaired() {
        val unpaired = elixir("Choice unpaired")
        erlang()

        write(unpaired)

        assertNull(storedErlangName(unpaired))
    }

    fun testAddSelectsTheSdkItCreates() {
        scriptAdd(pickInstalledErlang)
        val panel = panel()

        panel.addButton.doClick()

        val created = Facet.sdks().single()
        assertEquals(listOf(created.name), panel.state.names())
        assertEquals(created.name, panel.state.selected?.sdk?.name)
        assertTrue(panel.picked)
    }

    fun testAnSdkAddedIsKeptOnCancel() {
        scriptAdd(pickInstalledErlang)

        panel().addButton.doClick()

        assertEquals(1, Facet.sdks().size)
        assertNull(ProjectRootManager.getInstance(project).projectSdk)
    }

    fun testAnSdkAddedIsNamedAfterItsErlang() {
        scriptAdd(pickInstalledErlang)

        panel().addButton.doClick()

        val created = Facet.sdks().single()
        assertTrue(created.name, created.name.endsWith("(Erlang 27.3.4)"))
    }

    fun testOkAfterAddWritesTheSdkCreated() {
        scriptAdd(pickInstalledErlang)

        val panel = panel()
        panel.addButton.doClick()

        write(panel.state.selected!!.sdk)

        val created = Facet.sdks().single()
        assertSame(created, ProjectRootManager.getInstance(project).projectSdk)
    }

    fun testAnAddCancelledKeepsThePick() {
        elixir("Choice A")
        scriptAdd(ScriptedErlangStep.cancel)
        val panel = panel()
        panel.pick(panel.state.entries.single())

        panel.addButton.doClick()

        assertEquals(listOf("Choice A"), Facet.sdks().map { it.name })
        assertEquals("Choice A", panel.state.selected?.sdk?.name)
        assertTrue(panel.picked)
    }

    fun testManageSdksOpensTheSdksPage() {
        val recorded = RecordedChooser(testRootDisposable)

        panel().manageSdksLink.doClick()

        assertEquals(listOf(project to SettingsPage.SDKS), recorded.settingsOpened)
    }

    fun testAnSdkAddedToTheTableIsListedAndThePickKept() {
        elixir("Choice A")
        val panel = panel()
        panel.pick(panel.state.entries.single())

        elixir("Choice B")
        SdkFixtures.settle()

        assertEquals(listOf("Choice A", "Choice B"), panel.state.names())
        assertEquals("Choice A", panel.state.selected?.sdk?.name)
        assertTrue(panel.picked)
    }

    fun testRemovingThePickedSdkFromTheTableClearsThePick() {
        val picked = elixir("Choice A")
        elixir("Choice B")
        val panel = panel()
        panel.pick(panel.state.entries.first())

        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(picked) }
        SdkFixtures.settle()

        assertEquals(listOf("Choice B"), panel.state.names())
        assertNull(panel.state.selected)
        assertFalse(panel.picked)
    }

    fun testAPairingCommittedWhileTheChooserIsOpenIsShown() {
        val unpaired = elixir("Choice unpaired")
        val erlang = erlang()
        val panel = panel()

        SdkFixtures.commitPairing(unpaired, erlang)
        SdkFixtures.settle()

        assertEquals(listOf("Choice unpaired  Erlang SDK 'Choice Erlang', OTP 27.3.4"), panel.state.entries.map(panel::rowText))
    }

    fun testAddKeepsAPairingCommittedWhileTheChooserIsOpen() {
        scriptAdd(pickInstalledErlang)
        val unpaired = elixir("Choice unpaired")
        val erlang = erlang()
        val panel = panel()

        SdkFixtures.commitPairing(unpaired, erlang)
        panel.addButton.doClick()

        assertEquals("Choice Erlang", storedErlangName(unpaired))
    }

    fun testRenamingThePickedSdkKeepsThePick() {
        elixir("Choice A")
        val panel = panel()
        panel.pick(panel.state.entries.single())

        SdkFixtures.renameInTable("Choice A", "Choice A renamed")
        SdkFixtures.settle()

        assertEquals("Choice A renamed", panel.state.selected?.sdk?.name)
        assertTrue(panel.picked)
    }

    fun testADefaultProjectStateWithAWslTargetListsThatDistrosSdksOnceAcrossReloads() {
        val home = "${LEGACY_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\elixir"
        SdkFixtures.register(SdkFixtures.elixirSdk("Choice WSL", home), testRootDisposable)
        val state = wslTargetState()

        state.reload()
        state.reload()

        // Without WSL both paths are local, and `reset` keeps the SDK; with it, the sync adds it back. Either way, once.
        assertEquals(listOf("Choice WSL"), state.names())
    }

    fun testAnSdkWithNoHomeIsListedOnce() {
        SdkFixtures.register(ProjectJdkImpl("Choice homeless", ElixirType.instance), testRootDisposable)
        val state = wslTargetState()

        state.reload()

        assertEquals(listOf("Choice homeless"), state.names())
    }

    fun testRetargetingToAnotherEnvironmentListsTheTableAgain() {
        val state = state()
        elixir("Choice later")
        assertEmpty(state.names())

        state.retarget(Elsewhere, emptyList())

        assertEquals(listOf("Choice later"), state.names())
    }

    fun testAddsErlangStepIsGivenTheTargetsErlangSdks() {
        SdkFixtures.register(
            SdkFixtures.erlangSdk("Choice WSL Erlang", "${LEGACY_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\erlang"),
            testRootDisposable,
        )
        val step = scriptAdd(ScriptedErlangStep.cancel)

        wslTargetState().add(JPanel())

        assertContainsElements(step.offered.single().mapNotNull { it.sdk?.name }, "Choice WSL Erlang", "Choice installed Erlang")
    }

    fun testMisesPinsAreOfferedAndNothingIsSelected() {
        toolManager.pinsTheOffer()

        val panel = offerPanel()

        val first = panel.waitFor(OFFER)
        val second = panel.waitFor(ANOTHER)
        assertInstanceOf(first, JRadioButton::class.java)
        assertInstanceOf(second, JRadioButton::class.java)
        assertEquals("the radios", listOf(false, false), listOf(first.isSelected, second.isSelected))
        assertNull(panel.state.selected)
        assertFalse(panel.picked)
    }

    fun testNoOfferShowsNoRadios() {
        val panel = offerPanel()

        dispatchForAWhile()

        assertNull(panel.shownOffer())
        assertNull(panel.shown { it == ANOTHER })
        assertNull(panel.shown { it == CHECKING })
    }

    fun testTheBoxesOpenWithTheFirstRadioAndCloseWithTheSecond() {
        toolManager.pinsTheOffer()
        val panel = offerPanel()
        val first = panel.waitFor(OFFER)
        val second = panel.waitFor(ANOTHER)
        val inStep = panel.waitFor(IN_STEP)
        assertFalse("neither radio is chosen", inStep.isEnabled)

        first.doClick()
        assertTrue("the first radio", inStep.isEnabled)

        second.doClick()
        assertTrue(second.isSelected)
        assertFalse("the second radio", inStep.isEnabled)
    }

    fun testTheSecondRadioBringsBackTheRowSelectedBefore() {
        elixir(EARLIER)
        toolManager.pinsTheOffer()
        val panel = offerPanel()
        panel.takeTheOffer(OFFER, EARLIER)
        assertFalse(EARLIER == panel.selectedModel().name)

        panel.waitFor(ANOTHER).doClick()

        assertEquals(EARLIER, panel.selectedModel().name)
        assertTrue(panel.picked)
        assertEquals("the radios", listOf(false, true), listOf(panel.waitFor(OFFER).isSelected, panel.waitFor(ANOTHER).isSelected))
    }

    fun testTheSecondRadioWithNoEarlierRowLeavesNoPick() {
        toolManager.pinsTheOffer()
        val panel = offerPanel()
        panel.takeTheOffer(OFFER, null)
        assertTrue(panel.picked)

        panel.waitFor(ANOTHER).doClick()

        assertNull(panel.state.selected)
        assertFalse(panel.picked)
        assertTrue(panel.waitFor(ANOTHER).isSelected)
    }

    fun testSelectingARowInTheListChoosesTheSecondRadio() {
        elixir(EARLIER)
        toolManager.pinsTheOffer()
        val panel = offerPanel()
        val first = panel.waitFor(OFFER).also { it.doClick() }
        val second = panel.waitFor(ANOTHER)

        UIUtil.uiTraverser(panel).filter(JBList::class.java).first()!!.setSelectedValue(panel.state.entries.single { it.sdk.name == EARLIER }, true)

        assertEquals(EARLIER, panel.state.selected?.sdk?.name)
        assertEquals("the radios", listOf(false, true), listOf(first.isSelected, second.isSelected))
    }

    fun testAddChoosesTheSecondRadio() {
        scriptAdd(pickInstalledErlang)
        toolManager.pins(elixirInstall("1.17.3", "27"), install("27", "27.3.4"))
        val panel = offerPanel()
        panel.waitFor(OFFER)
        val second = panel.waitFor(ANOTHER)

        panel.addButton.doClick()

        assertNotNull(panel.state.selected)
        assertTrue(second.isSelected)
    }

    fun testTheOffersModelUnderTheSecondRadioHandsOverNothing() {
        toolManager.pinsTheOffer()
        val panel = offerPanel()
        panel.takeTheOffer(OFFER, null)
        val made = panel.state.selected!!.sdk
        val inStep = panel.waitFor(IN_STEP).also { it.doClick() }
        val second = panel.waitFor(ANOTHER).also { it.doClick() }

        panel.pickRow(made.name)

        assertSame(made, panel.lastPick?.entry?.sdk)
        assertNull(panel.lastPick?.optIns)
        assertTrue(second.isSelected)
        assertEquals("the box", listOf(false, false), listOf(inStep.isEnabled, inStep.isSelected))
    }

    fun testSeveralKeepTheFirstRadioAndSayToPickOne() {
        val several = severalOnTheInstall()
        val panel = offerPanel()
        val first = panel.waitFor(ELIXIR_ONLY_OFFER)
        val second = panel.waitFor(ANOTHER)
        val inStep = panel.waitFor(IN_STEP)

        first.doClick()

        panel.waitFor(SEVERAL) { it == SEVERAL }
        assertTrue(first.isSelected)
        assertNull(panel.state.selected)
        assertFalse(panel.picked)

        panel.pickRow(several[1].name)

        assertSame(several[1], panel.state.selected?.sdk)
        assertTrue(panel.picked)
        assertEquals("the radios", listOf(true, false), listOf(first.isSelected, second.isSelected))
        assertTrue("the boxes", inStep.isEnabled)
        assertNull("the line, with one picked", panel.shown { it == SEVERAL })
    }

    fun testAnotherRowThanTheSeveralMovesToTheSecondRadio() {
        severalOnTheInstall()
        elixir(EARLIER)
        val panel = offerPanel()
        val first = panel.waitFor(ELIXIR_ONLY_OFFER).also { it.doClick() }
        val second = panel.waitFor(ANOTHER)
        val inStep = panel.waitFor(IN_STEP)

        panel.pickRow(EARLIER)

        assertEquals("the radios", listOf(false, true), listOf(first.isSelected, second.isSelected))
        assertFalse("the boxes", inStep.isEnabled)
    }

    fun testACancelledErlangStepPutsTheChoiceBack() {
        ScriptedErlangStep(asked, ScriptedErlangStep.cancel, ScriptedErlangStep.cancel, ScriptedErlangStep.cancel).install(testRootDisposable)
        toolManager.pins(elixirInstall("1.18.4", "27"), null)

        withAndWithoutAnEarlierRow { earlier ->
            val panel = offerPanel()
            panel.takeTheOffer(ELIXIR_ONLY_OFFER, earlier)

            panel.assertPutBack(ELIXIR_ONLY_OFFER, earlier)

            if (earlier == null) {
                asked.events.clear()
                panel.waitFor(ELIXIR_ONLY_OFFER).doClick()
                assertEquals("the first radio runs the step again", listOf("erlang"), asked.events)
            }
        }
    }

    fun testCancellingTheOffersProgressPutsTheChoiceBack() {
        val cancelled: StepAnswer = { _, _ -> throw CancellationException() }
        ScriptedErlangStep(asked, cancelled, cancelled).install(testRootDisposable)
        toolManager.pins(elixirInstall("1.18.4", "27"), null)

        withAndWithoutAnEarlierRow { earlier ->
            val panel = offerPanel()

            val thrown = runCatching { panel.takeTheOffer(ELIXIR_ONLY_OFFER, earlier) }.exceptionOrNull()

            assertInstanceOf(thrown, CancellationException::class.java)
            panel.assertPutBack(ELIXIR_ONLY_OFFER, earlier)
        }
    }

    fun testRemovingTheTakenModelPutsTheChoiceBack() {
        toolManager.pinsTheOffer()

        withAndWithoutAnEarlierRow { earlier ->
            val panel = offerPanel()
            panel.takeTheOffer(OFFER, earlier)
            val made = panel.selectedModel()

            WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(made) }
            SdkFixtures.settle()

            panel.assertPutBack(OFFER, earlier)
            if (earlier == null) panel.assertTheFirstRadioTakesAgain(OFFER)
        }
    }

    fun testEditingTheTakenModelsElixirHomePutsTheChoiceBack() =
        editingTheTakenModelPutsTheChoiceBack { made -> SdkFixtures.commit(made) { homePath = elixirInstall("1.19.0", "27") } }

    fun testEditingTheTakenModelsErlangPairingPutsTheChoiceBack() {
        val another = SdkFixtures.register(SdkFixtures.erlangSdk("Choice another Erlang", fakeHome("erlang/28.0")), testRootDisposable)
        editingTheTakenModelPutsTheChoiceBack { made -> SdkFixtures.commitPairing(made, another) }
    }

    private fun editingTheTakenModelPutsTheChoiceBack(edit: (Sdk) -> Unit) {
        toolManager.pinsTheOffer()

        withAndWithoutAnEarlierRow { earlier ->
            val panel = offerPanel()
            panel.takeTheOffer(OFFER, earlier)
            val made = panel.selectedModel()

            try {
                edit(made)
                SdkFixtures.settle()

                panel.assertPutBack(OFFER, earlier)
            } finally {
                WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(made) }
            }
        }
    }

    fun testRenamingTheTakenModelKeepsTheChoice() {
        toolManager.pinsTheOffer()
        val panel = offerPanel()
        panel.takeTheOffer(OFFER, null)
        val renamed = SdkFixtures.renameInTable(panel.selectedModel().name, "Choice renamed")

        SdkFixtures.settle()

        assertTrue("the first radio stands", panel.waitFor(OFFER).isSelected)
        assertSame(renamed, panel.state.selected?.sdk)
        assertTrue(panel.picked)
    }

    fun testEditingThePickedOneOfTheSeveralPutsTheChoiceBack() {
        val several = severalOnTheInstall()
        val panel = offerPanel()
        panel.waitFor(ELIXIR_ONLY_OFFER).doClick()
        panel.pickRow(several[0].name)

        SdkFixtures.commit(several[0]) { homePath = elixirInstall("1.19.0", "27") }
        SdkFixtures.settle()

        panel.assertPutBack(ELIXIR_ONLY_OFFER, null)
    }

    fun testEditingEveryOneOfTheSeveralWithNonePickedPutsTheChoiceBack() {
        val several = severalOnTheInstall()
        val panel = offerPanel()
        panel.waitFor(ELIXIR_ONLY_OFFER).doClick()

        several.forEachIndexed { index, sdk -> SdkFixtures.commit(sdk) { homePath = elixirInstall("1.19.$index", "27") } }
        SdkFixtures.settle()

        panel.assertPutBack(ELIXIR_ONLY_OFFER, null)
    }

    fun testPickingAnEditedOneOfTheSeveralMovesToTheSecondRadio() {
        val several = severalOnTheInstall()
        val panel = offerPanel()
        val first = panel.waitFor(ELIXIR_ONLY_OFFER).also { it.doClick() }
        val second = panel.waitFor(ANOTHER)

        SdkFixtures.commit(several[0]) { homePath = elixirInstall("1.19.0", "27") }
        SdkFixtures.settle()
        assertTrue("another of the Several still stands behind the radio", first.isSelected)
        panel.pickRow(several[0].name)

        assertEquals("the radios", listOf(false, true), listOf(first.isSelected, second.isSelected))
    }

    fun testAnOfferOfOtherPinsAtANewLocationPutsTheChoiceBack() {
        val folder = newFolder()
        toolManager.pinsAnotherIn(folder)

        withAndWithoutAnEarlierRow { earlier ->
            val panel = offerPanel()
            panel.takeTheOffer(OFFER, earlier)

            panel.retarget(project.getEelDescriptor(), listOf(folder))

            panel.assertPutBack(FOLDER_OFFER, earlier)
            if (earlier == null) panel.assertTheFirstRadioTakesAgain(FOLDER_OFFER)
        }
    }

    fun testARetargetThatDropsTheTakenModelPutsTheChoiceBack() {
        toolManager.pinsTheOffer()

        withAndWithoutAnEarlierRow { earlier ->
            val panel = offerPanel()
            panel.takeTheOffer(OFFER, earlier)
            val made = panel.selectedModel()

            // Gone before the panel's own reload can run, so only the retarget can find it gone.
            WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(made) }
            panel.retarget(Elsewhere, listOf(offerFolder))

            assertFalse("the retarget puts the choice back", panel.state.offerChosen)
            panel.assertPutBack(OFFER, earlier)
        }
    }

    fun testALocationWithNoOfferPutsTheChoiceBack() {
        assertAnAnswerPutsTheChoiceBack(null)
    }

    fun testAnErrorAnswerPutsTheChoiceBack() {
        assertAnAnswerPutsTheChoiceBack(ToolManagerResult.Error(toolManager.name, MiseToolManager.untrustedConfigDescription("/project/mise.toml")))
    }

    /** With the offer taken after [EARLIER] or no row, [answer] at a new location offers nothing: no radio, and that row or none. */
    private fun assertAnAnswerPutsTheChoiceBack(answer: ToolManagerResult?) {
        val pins = toolManager.offeredPins()

        withAndWithoutAnEarlierRow { earlier ->
            val folder = newFolder()
            toolManager.answer = { if (it == folder) answer else pins }
            val panel = offerPanel()
            panel.takeTheOffer(OFFER, earlier)

            panel.retarget(project.getEelDescriptor(), listOf(folder))

            PlatformTestUtil.waitWithEventsDispatching("the choice is back on $earlier", { panel.state.selected?.sdk?.name == earlier }, 10)
            assertEquals("picked", earlier != null, panel.picked)
            assertNull(panel.shownOffer())
            assertNull(panel.shown { it == ANOTHER })
        }
    }

    fun testAnAnswerOfOtherPinsLeavesARowPickedUnderTheSecondRadio() {
        elixir(EARLIER)
        val folder = newFolder()
        toolManager.pinsAnotherIn(folder)
        val panel = offerPanel()
        panel.takeTheOffer(OFFER, null)
        panel.pickRow(EARLIER)

        panel.retarget(project.getEelDescriptor(), listOf(folder))

        panel.waitFor(FOLDER_OFFER)
        assertEquals(EARLIER, panel.state.selected?.sdk?.name)
        assertTrue(panel.picked)
        assertTrue(panel.waitFor(ANOTHER).isSelected)
    }

    fun testRemovingAnotherOfTheSeveralKeepsThePick() {
        val several = severalOnTheInstall()
        val panel = offerPanel()
        val first = panel.waitFor(ELIXIR_ONLY_OFFER).also { it.doClick() }
        val second = panel.waitFor(ANOTHER)
        panel.pickRow(several[0].name)

        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(several[1]) }
        SdkFixtures.settle()

        assertSame(several[0], panel.state.selected?.sdk)
        assertTrue(panel.picked)
        assertEquals("the radios", listOf(true, false), listOf(first.isSelected, second.isSelected))
    }

    fun testChoosingTheFirstRadioAgainKeepsTheRowPickedInTheSeveral() {
        val several = severalOnTheInstall()
        val panel = offerPanel()
        val first = panel.waitFor(ELIXIR_ONLY_OFFER).also { it.doClick() }
        panel.pickRow(several[1].name)

        first.doClick()

        assertSame(several[1], panel.state.selected?.sdk)
        assertTrue(panel.picked)
    }

    fun testAReloadLeavesTheRadioAlone() {
        elixir(EARLIER)
        toolManager.pinsTheOffer()
        val panel = offerPanel()
        val first = panel.waitFor(OFFER)
        val second = panel.waitFor(ANOTHER)

        fun assertAReloadKeeps(which: String, expected: List<Boolean>) {
            elixir("Choice reload $which")
            SdkFixtures.settle()
            assertEquals(which, expected, listOf(first.isSelected, second.isSelected))
        }

        assertAReloadKeeps("neither", listOf(false, false))
        first.doClick()
        assertAReloadKeeps("the first", listOf(true, false))
        second.doClick()
        panel.pickRow(EARLIER)
        assertAReloadKeeps("the second", listOf(false, true))
        assertEquals(EARLIER, panel.state.selected?.sdk?.name)
    }

    fun testASlowCheckShowsCheckingMiseAfterItsDelay() {
        var release = toolManager.holdsTheOffer()
        try {
            val panel = offerPanel()

            assertNull("straight after construction", panel.shown { it == CHECKING })
            assertFalse(panel.waitFor(CHECKING).isEnabled)
            panel.waitFor(ANOTHER)

            release.countDown()
            panel.waitFor(OFFER)
            assertNull("once it has answered", panel.shown { it == CHECKING })

            release = toolManager.holdsTheOffer()
            panel.retarget(project.getEelDescriptor(), listOf(newFolder()))

            assertNull("straight after a retarget", panel.shown { it == CHECKING })
            assertFalse(panel.waitFor(CHECKING).isEnabled)
        } finally {
            release.countDown()
        }
    }

    fun testARowPickedDuringTheCheckKeepsTheSecondRadio() {
        pickWhileTheCheckRuns(answered = { panel -> assertTrue(panel.waitFor(ANOTHER).isSelected) })
    }

    /** With the check held, picks [EARLIER] and does [whileHeld]; once the offer shows, it is still picked, and [answered]. */
    private fun pickWhileTheCheckRuns(whileHeld: (SdkChoicePanel) -> Unit = {}, answered: (SdkChoicePanel) -> Unit = {}) {
        elixir(EARLIER)
        val release = toolManager.holdsTheOffer()
        try {
            val panel = offerPanel()
            panel.pickRow(EARLIER)
            whileHeld(panel)

            release.countDown()

            panel.waitFor(OFFER)
            assertEquals(EARLIER, panel.state.selected?.sdk?.name)
            answered(panel)
        } finally {
            release.countDown()
        }
    }

    fun testTakingTheOfferSelectsTheModelPairedAsMisePins() {
        val elixirHome = elixirInstall("1.18.4", "27")
        val erlangHome = install("27", "27.3.4")
        toolManager.pins(elixirHome, erlangHome)
        val panel = offerPanel()

        panel.waitFor(OFFER).doClick()

        val made = panel.selectedModel()
        assertEquals(listOf(made), elixirSdksAt(elixirHome))
        assertPairedWithErlangAt(made, erlangHome)
        assertTrue(panel.picked)

        val again = offerPanel()
        again.waitFor(OFFER).doClick()

        assertSame(ProjectJdkTable.getInstance().findJdk(made.name), again.state.selected?.sdk)
        assertEquals(listOf(made), elixirSdksAt(elixirHome))
    }

    fun testTakingTheOfferRepairsNoModel() {
        val elixirHome = elixirInstall("1.18.4", "27")
        val otherErlang = registeredErlang("Choice other Erlang", install("26", "26.2.5"))
        val other = pairedElixir("Choice other pairing", elixirHome, otherErlang)
        val erlangHome = install("27", "27.3.4")
        toolManager.pins(elixirHome, erlangHome)
        val panel = offerPanel()

        panel.waitFor(OFFER).doClick()

        assertEquals(otherErlang.name, storedErlangName(other))
        val selected = panel.selectedModel()
        assertNotSame(other, selected)
        assertPairedWithErlangAt(selected, erlangHome)
    }

    fun testAnElixirPinAloneWithNoModelAsksTheErlangStep() {
        val elixirHome = elixirInstall("1.18.4", "27")
        val erlang = registeredErlang("Choice step Erlang", install("27", "27.3.4"))
        ScriptedErlangStep(asked, ScriptedErlangStep.pick(erlang.name)).install(testRootDisposable)
        toolManager.pins(elixirHome, null)
        val panel = offerPanel()

        panel.waitFor(ELIXIR_ONLY_OFFER).doClick()

        assertEquals(listOf("erlang"), asked.events)
        val selected = panel.selectedModel()
        assertEquals(listOf(selected), elixirSdksAt(elixirHome))
        assertEquals(erlang.name, storedErlangName(selected))
    }

    fun testAnElixirPinAloneWithOneModelIsSelected() {
        val elixirHome = elixirInstall("1.18.4", "27")
        val existing = pairedElixir("Choice one on the install", elixirHome, registeredErlang("Choice one's Erlang", install("26", "26.2.5")))
        toolManager.pins(elixirHome, null)
        val panel = offerPanel()

        panel.waitFor(ELIXIR_ONLY_OFFER).doClick()

        assertEmpty(asked.events)
        assertSame(existing, panel.state.selected?.sdk)
    }

    fun testAnElixirPinAloneWithSeveralModelsSelectsNothing() {
        severalOnTheInstall()
        val panel = offerPanel()

        panel.waitFor(ELIXIR_ONLY_OFFER).doClick()

        assertEmpty(asked.events)
        assertNull(panel.state.selected)
        assertFalse(panel.picked)
    }

    fun testTheEnvironmentBoxFollowsTheToolManagerBox() {
        toolManager.pinsTheOffer()
        val panel = offerPanel()
        panel.waitFor(OFFER).doClick()
        val inStep = panel.waitFor(IN_STEP)
        val environment = panel.waitFor(ENVIRONMENT)

        assertEquals(listOf(true, false), listOf(inStep.isEnabled, inStep.isSelected))
        assertEquals(listOf(false, false), listOf(environment.isEnabled, environment.isSelected))

        inStep.doClick()
        assertTrue(environment.isEnabled)

        inStep.doClick()
        assertFalse(environment.isEnabled)
    }

    fun testWithTheToolManagerOnTheBoxesShowItAndAreDisabled() {
        ToolManagerSettings.getInstance(project).run {
            setEnabled(toolManager, true)
            setEnvironmentEnabled(toolManager, true)
        }
        toolManager.pinsTheOffer()
        val panel = offerPanel()

        panel.waitFor(OFFER).doClick()

        for (box in listOf(panel.waitFor(IN_STEP), panel.waitFor(ENVIRONMENT))) {
            assertEquals(box.text, listOf(false, true), listOf(box.isEnabled, box.isSelected))
        }
    }

    fun testAnUntrustedConfigShowsItsMessageAndNoOffer() {
        toolManager.answer = { ToolManagerResult.Error(toolManager.name, MiseToolManager.untrustedConfigDescription("/project/mise.toml")) }

        val panel = offerPanel()

        panel.waitFor("is not trusted") { "is not trusted" in it }
        panel.waitFor(CHECK_AGAIN)
        assertNull(panel.shownOffer())
    }

    fun testCheckAgainRunsTheCheckAgain() {
        toolManager.answer = { ToolManagerResult.Error(toolManager.name, MiseToolManager.untrustedConfigDescription("/project/mise.toml")) }
        val panel = offerPanel()
        panel.waitFor("is not trusted") { "is not trusted" in it }
        toolManager.pinsTheOffer()

        panel.waitFor(CHECK_AGAIN).doClick()

        panel.waitFor(OFFER)
        assertNull(panel.shown { "is not trusted" in it })
    }

    fun testTheListWorksWhileTheCheckRuns() {
        pickWhileTheCheckRuns(whileHeld = { panel ->
            assertTrue(panel.picked)
            assertNull(panel.shownOffer())
        })
    }

    fun testAChangedLocationTakesTheOldOfferAway() {
        val pins = toolManager.offeredPins()
        val elsewhere = newFolder()
        val release = CountDownLatch(1)
        toolManager.answer = { folder ->
            if (folder != offerFolder) release.await(10, TimeUnit.SECONDS)
            pins
        }
        try {
            val panel = offerPanel()
            panel.waitFor(OFFER)

            panel.retarget(project.getEelDescriptor(), listOf(elsewhere))

            assertNull("the old location's offer", panel.shownOffer())
            assertNull("the old location's boxes", panel.shown { it == IN_STEP })

            release.countDown()

            panel.waitFor(OFFER)
        } finally {
            release.countDown()
        }
    }

    fun testATakenOfferStandsAtALocationPinningTheSame() {
        toolManager.pinsTheOffer()
        val panel = offerPanel()
        panel.waitFor(OFFER).doClick()
        assertTrue("the boxes once the offer is taken", panel.waitFor(IN_STEP).isEnabled)

        panel.retarget(project.getEelDescriptor(), listOf(newFolder()))
        assertNull("the old location's offer", panel.shownOffer())

        assertTrue("the offer's radio at a location pinning the same", panel.waitFor(OFFER).isSelected)
        assertTrue("the boxes at a location pinning the same", panel.waitFor(IN_STEP).isEnabled)
    }

    fun testOkWithTheOffersModelWritesTheTickedOptIns() {
        val elixirHome = elixirInstall("1.18.4", "27")
        toolManager.pins(elixirHome, install("27", "27.3.4"))
        val changes = settingsChanges(project, testRootDisposable) {}

        val written = pickWithTheOffer { panel ->
            panel.waitFor(OFFER).doClick()
            panel.waitFor(IN_STEP).doClick()
            panel.waitFor(ENVIRONMENT).doClick()
        }

        assertEquals(elixirSdksAt(elixirHome), listOfNotNull(written))
        assertSame(written, ModuleRootManager.getInstance(module).sdk)
        assertEquals(listOf(true, true), toolManagerSettings())
        assertEquals("settings changes", 1, changes.size)
    }

    fun testBoxesTickedAfterThePickAreHandedOver() {
        toolManager.pinsTheOffer()
        val panel = offerPanel()
        panel.waitFor(OFFER).doClick()
        assertNull("nothing ticked", panel.lastPick?.optIns)

        panel.waitFor(IN_STEP).doClick()
        assertEquals(ToolManagerOptIns(toolManager.name, environment = false), panel.lastPick?.optIns)

        panel.waitFor(ENVIRONMENT).doClick()
        assertEquals(ToolManagerOptIns(toolManager.name, environment = true), panel.lastPick?.optIns)

        panel.waitFor(IN_STEP).doClick()
        assertNull("the tool manager unticked", panel.lastPick?.optIns)
        assertSame(panel.state.selected, panel.lastPick?.entry)
    }

    fun testAListedPickHandsOverTheOptInsAndAnUnlistedOneDisablesTheBoxes() {
        val several = severalOnTheInstall()
        elixir("Choice A")
        val panel = offerPanel()
        panel.waitFor(ELIXIR_ONLY_OFFER).doClick()
        val inStep = panel.waitFor(IN_STEP)
        val environment = panel.waitFor(ENVIRONMENT)
        assertTrue("the boxes follow the radio, with nothing picked yet", inStep.isEnabled)

        panel.pickRow(several[1].name)
        assertTrue(inStep.isEnabled)
        inStep.doClick()
        environment.doClick()

        assertEquals(ToolManagerOptIns(toolManager.name, environment = true), panel.lastPick?.optIns)

        panel.pickRow("Choice A")

        assertEquals(listOf(false, false), listOf(inStep.isEnabled, environment.isEnabled))
        assertEquals("Choice A", panel.lastPick?.entry?.sdk?.name)
        assertNull(panel.lastPick?.optIns)
    }

    fun testATickedBoxShowsUntickedWhileItHandsOverNothing() {
        elixir("Choice A")
        toolManager.pinsTheOffer()
        val panel = offerPanel()
        val first = panel.waitFor(OFFER).also { it.doClick() }
        val inStep = panel.waitFor(IN_STEP)
        val environment = panel.waitFor(ENVIRONMENT)
        inStep.doClick()
        environment.doClick()

        panel.pickRow("Choice A")

        assertNull(panel.lastPick?.optIns)
        assertEquals("shown ticked", listOf(false, false), listOf(inStep.isSelected, environment.isSelected))

        first.doClick()

        assertEquals(ToolManagerOptIns(toolManager.name, environment = true), panel.lastPick?.optIns)
        assertEquals("shown ticked", listOf(true, true), listOf(inStep.isSelected, environment.isSelected))
    }

    fun testUntickingTheToolManagerShowsTheEnvironmentUnticked() {
        toolManager.pinsTheOffer()
        val panel = offerPanel()
        panel.waitFor(OFFER).doClick()
        val inStep = panel.waitFor(IN_STEP)
        val environment = panel.waitFor(ENVIRONMENT)
        inStep.doClick()
        environment.doClick()

        inStep.doClick()

        assertNull(panel.lastPick?.optIns)
        assertFalse("the environment shown ticked", environment.isSelected)
    }

    fun testAStoredEnvironmentFlagFollowsTheUntickedBox() {
        ToolManagerSettings.getInstance(project).setEnvironmentEnabled(toolManager, true)
        toolManager.pinsTheOffer()

        pickWithTheOffer { panel ->
            panel.waitFor(OFFER).doClick()
            panel.waitFor(IN_STEP).doClick()
        }

        assertEquals(listOf(true, false), toolManagerSettings())
    }

    fun testCancelWritesNoOptIn() {
        val elixirHome = elixirInstall("1.18.4", "27")
        toolManager.pins(elixirHome, install("27", "27.3.4"))
        val changes = settingsChanges(project, testRootDisposable) {}

        val written = pickWithTheOffer(ok = false) { panel ->
            panel.waitFor(OFFER).doClick()
            panel.waitFor(IN_STEP).doClick()
            panel.waitFor(ENVIRONMENT).doClick()
        }

        assertNull(written)
        assertTrue(ModuleRootManager.getInstance(module).isSdkInherited)
        assertEquals(listOf(false, false), toolManagerSettings())
        assertEquals("settings changes", 0, changes.size)
        assertEquals("the Model the offer made stays", 1, elixirSdksAt(elixirHome).size)
    }

    fun testUntickedBoxesLeaveTheToolManagerOff() {
        toolManager.pinsTheOffer()
        val changes = settingsChanges(project, testRootDisposable) {}

        val written = pickWithTheOffer { panel -> panel.waitFor(OFFER).doClick() }

        assertSame(written, ModuleRootManager.getInstance(module).sdk)
        assertEquals(listOf(false, false), toolManagerSettings())
        assertEquals("settings changes", 0, changes.size)
    }

    /** The tool manager's settings in this project: whether it is on, and whether its environment is. */
    private fun toolManagerSettings(): List<Boolean> =
        ToolManagerSettings.getInstance(project).run { listOf(isEnabled(toolManager), isEnvironmentEnabled(toolManager)) }

    /**
     * Does [answer] in the chooser's panel with the tool managers asked about the offer folder, then writes its last
     * pick to [module] as a wizard does, unless not [ok]; answers what is written.
     */
    private fun pickWithTheOffer(ok: Boolean = true, answer: (SdkChoicePanel) -> Unit): Sdk? {
        val panel = offerPanel()
        answer(panel)
        val pick = lastPicks[panel.state]?.takeIf { ok } ?: return null
        writePick(project, listOf(module), pick.entry.sdk, pick.optIns)
        return pick.entry.sdk
    }

    /** The chooser's panel, with the tool managers asked about the offer folder. */
    private fun offerPanel(): SdkChoicePanel = panel(listOf(offerFolder))

    private val offerFolder: Path by lazy { newFolder() }

    /** The offer's radio, or one of its boxes, when one shows. */
    private fun SdkChoicePanel.shownOffer(): JComponent? = shown { it.startsWith("Use mise's") }

    private fun newFolder(): Path = createTempDirectory().toPath()

    /** Two Elixir SDKs on the install mise pins, with no Erlang pin to tell them apart. */
    private fun severalOnTheInstall(): List<Sdk> {
        val elixirHome = elixirInstall("1.18.4", "27")
        return twoSdksOnTheInstall(elixirHome, "Choice several").also { toolManager.pins(elixirHome, null) }
    }

    /** Once [offer] shows, picks the row [earlier], if any, then chooses the offer's radio. */
    private fun SdkChoicePanel.takeTheOffer(offer: String, earlier: String?) {
        val radio = waitFor(offer)
        earlier?.let { pickRow(it) }
        radio.doClick()
    }

    /** Registers the row [EARLIER], then [case] twice, whichever way the first goes: with that row picked before the offer is taken, and with none. */
    private fun withAndWithoutAnEarlierRow(case: (earlier: String?) -> Unit) {
        elixir(EARLIER)
        runAll({ case(EARLIER) }, { case(null) })
    }

    /** The radios show, with [earlier] selected under the second, or with no row and no radio chosen. */
    private fun SdkChoicePanel.assertPutBack(offer: String, earlier: String?) {
        val first = waitFor(offer)
        val second = waitFor(ANOTHER)
        assertEquals(earlier, state.selected?.sdk?.name)
        assertEquals("picked", earlier != null, picked)
        assertEquals("the radios", listOf(false, earlier != null), listOf(first.isSelected, second.isSelected))
        assertTrue("the first radio can be chosen", first.isEnabled)
    }

    private fun SdkChoicePanel.assertTheFirstRadioTakesAgain(offer: String) {
        val first = waitFor(offer)
        first.doClick()
        assertTrue(first.isSelected)
        assertNotNull(state.selected)
    }

    /** The Model selected in the panel's list. */
    private fun SdkChoicePanel.selectedModel(): Sdk = state.selected?.sdk ?: throw AssertionError("a Model is selected")

    /** A wizard's state: the default project's, for a project directory in a WSL distro. */
    private fun wslTargetState(): SdkChoiceState {
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            MockWslCompatService(),
            testRootDisposable,
        )
        val target = SdkEnvironment.eelDescriptor("${MODERN_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\project")
        return state(ProjectManager.getInstance().defaultProject, target)
    }

    private fun state(
        project: Project = this.project,
        target: EelDescriptor? = project.getEelDescriptor(),
        folders: List<Path> = emptyList(),
    ): SdkChoiceState =
        SdkChoiceState(project, target, folders, hostCommits = true).also { Disposer.register(testRootDisposable, it) }

    /** An environment no Eel resolver knows, so every SDK is visible for it. */
    private object Elsewhere : EelDescriptor {
        override val name = "elsewhere"
        override val osFamily = EelOsFamily.Posix
    }
}

private const val CHECK_AGAIN = "Check again"
private const val ANOTHER = "Use another SDK:"
private const val CHECKING = "Checking mise..."
private const val SEVERAL = "Several SDKs use this Elixir. Pick one in the list."
private const val EARLIER = "Choice A"
