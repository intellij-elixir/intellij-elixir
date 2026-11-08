package org.elixir_lang.sdk.choice

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.EelOsFamily
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import org.elixir_lang.Facet
import org.elixir_lang.facet.FacetModelFixtures
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

/** The chooser on IntelliJ IDEA, asked for this test's modules, with its dialog answered by the test. */
class SdkChoiceTest : HeavyTestCase() {
    private val shown = mutableListOf<SdkChoiceState>()
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

    private fun pick(name: String): (SdkChoiceState) -> SdkChoiceEntry? = { state -> state.entries.single { it.sdk.name == name } }

    /** Asks the chooser for [modules], its dialog answered by [answer] from what it shows, with no opt-ins. */
    private fun ask(answer: (SdkChoiceState) -> SdkChoiceEntry?, modules: List<Module> = listOf(module)): Sdk? =
        SdkChoice { _, _, state ->
            shown += state
            answer(state)?.let { SdkChoicePick(it, null) }
        }.ask(project, modules)

    fun testEveryElixirSdkIsListedByNameWithItsPairing() {
        val erlang = erlang()
        SdkFixtures.commitPairing(elixir("Choice B"), erlang)
        elixir("Choice A")

        ask({ null })

        assertEquals(
            listOf("Choice A" to null, "Choice B" to ("Choice Erlang" to "27.3.4")),
            shown.single().entries.map { entry -> entry.sdk.name to entry.erlang?.let { it.name to it.otpVersion } },
        )
    }

    fun testEachRowShowsItsPairing() {
        SdkFixtures.commitPairing(elixir("Choice paired"), erlang())
        elixir("Choice unpaired")
        val dialog = dialog()

        assertEquals(
            listOf("Choice paired  Erlang SDK 'Choice Erlang', OTP 27.3.4", "Choice unpaired  no Erlang SDK"),
            dialog.panel.state.entries.map(dialog.panel::rowText),
        )
    }

    fun testNothingIsSelected() {
        elixir("Choice A")

        ask({ null })

        assertNull(shown.single().selected)
    }

    fun testOkIsDisabledUntilAPick() {
        elixir("Choice A")
        val dialog = dialog()

        assertFalse("nothing is picked yet", dialog.isOKActionEnabled)

        dialog.panel.pick(dialog.panel.state.entries.single())

        assertTrue(dialog.isOKActionEnabled)
    }

    fun testAnUnpairedSdkCanBePicked() {
        elixir("Choice unpaired")
        val dialog = dialog()

        dialog.panel.pick(dialog.panel.state.entries.single())

        assertEquals("Choice unpaired", dialog.panel.state.selected?.sdk?.name)
    }

    fun testOnIntellijIdeaTheSdkTheFacetNamesGoesFirst() {
        elixir("Choice A")
        val named = elixir("Choice B")
        FacetModelFixtures.assign(module, named)

        ask({ null })

        assertEquals(listOf("Choice B", "Choice A"), shown.single().names())
    }

    fun testOkWritesThePickToEveryModule() {
        val pick = elixir("Choice pick")
        val other = inheritingModule("Choice other module")

        val returned = ask(pick("Choice pick"), listOf(module, other))

        assertSame(pick, returned)
        assertSame(pick, ProjectRootManager.getInstance(project).projectSdk)
        assertSame(pick, ModuleRootManager.getInstance(module).sdk)
        assertSame(pick, ModuleRootManager.getInstance(other).sdk)
    }

    private fun projectJdk(): Sdk {
        val jdk = SdkFixtures.register(ProjectJdkImpl("Choice JDK", JavaSdk.getInstance()), testRootDisposable)
        WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = jdk }
        return jdk
    }

    fun testAModuleInheritingAProjectJdkKeepsIt() {
        val jdk = projectJdk()
        val java = inheritingModule("Choice Java module")
        val pick = elixir("Choice pick")

        ask(pick("Choice pick"))

        assertSame(pick, ProjectRootManager.getInstance(project).projectSdk)
        assertSame(jdk, ModuleRootManager.getInstance(java).sdk)
    }

    fun testAnElixirModuleInheritingAProjectJdkGetsThePick() {
        projectJdk()
        val facetModule = inheritingModule("Choice facet module")
        FacetModelFixtures.facet(facetModule)
        val pick = elixir("Choice pick")

        ask(pick("Choice pick"))

        assertSame(pick, ModuleRootManager.getInstance(facetModule).sdk)
    }

    fun testWithNoProjectSdkNoOtherModuleIsGivenAnEntry() {
        val other = inheritingModule("Choice other module")
        elixir("Choice pick")

        ask(pick("Choice pick"))

        assertTrue(ModuleRootManager.getInstance(other).isSdkInherited)
    }

    fun testCancelWritesNothing() {
        elixir("Choice A")

        assertNull(ask({ null }))

        assertNull(ProjectRootManager.getInstance(project).projectSdk)
    }

    fun testThePickIsNeverPaired() {
        val unpaired = elixir("Choice unpaired")
        erlang()

        ask(pick("Choice unpaired"))

        assertNull(storedErlangName(unpaired))
    }

    fun testAddSelectsTheSdkItCreates() {
        scriptAdd(pickInstalledErlang)
        val dialog = dialog()

        dialog.panel.addButton.doClick()

        val created = Facet.sdks().single()
        assertEquals(listOf(created.name), dialog.panel.state.names())
        assertEquals(created.name, dialog.panel.state.selected?.sdk?.name)
        assertTrue(dialog.isOKActionEnabled)
    }

    fun testAnSdkAddedIsKeptOnCancel() {
        scriptAdd(pickInstalledErlang)

        ask({ state -> state.add(JPanel()); null })

        assertEquals(1, Facet.sdks().size)
        assertNull(ProjectRootManager.getInstance(project).projectSdk)
    }

    fun testOkAfterAddWritesTheSdkCreated() {
        scriptAdd(pickInstalledErlang)

        val returned = ask({ state -> state.add(JPanel()) })

        val created = Facet.sdks().single()
        assertSame(created, returned)
        assertSame(created, ProjectRootManager.getInstance(project).projectSdk)
    }

    fun testAnAddCancelledKeepsThePick() {
        elixir("Choice A")
        scriptAdd(ScriptedErlangStep.cancel)
        val dialog = dialog()
        dialog.panel.pick(dialog.panel.state.entries.single())

        dialog.panel.addButton.doClick()

        assertEquals(listOf("Choice A"), Facet.sdks().map { it.name })
        assertEquals("Choice A", dialog.panel.state.selected?.sdk?.name)
        assertTrue(dialog.isOKActionEnabled)
    }

    fun testManageSdksOpensTheSdksPage() {
        val recorded = RecordedChooser(testRootDisposable)

        dialog().panel.manageSdksLink.doClick()

        assertEquals(listOf(project to SettingsPage.SDKS), recorded.settingsOpened)
    }

    fun testAnSdkAddedToTheTableIsListedAndThePickKept() {
        elixir("Choice A")
        val dialog = dialog()
        dialog.panel.pick(dialog.panel.state.entries.single())

        elixir("Choice B")
        SdkFixtures.settle()

        assertEquals(listOf("Choice A", "Choice B"), dialog.panel.state.names())
        assertEquals("Choice A", dialog.panel.state.selected?.sdk?.name)
        assertTrue(dialog.isOKActionEnabled)
    }

    fun testRemovingThePickedSdkFromTheTableClearsThePick() {
        val picked = elixir("Choice A")
        elixir("Choice B")
        val dialog = dialog()
        dialog.panel.pick(dialog.panel.state.entries.first())

        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(picked) }
        SdkFixtures.settle()

        assertEquals(listOf("Choice B"), dialog.panel.state.names())
        assertNull(dialog.panel.state.selected)
        assertFalse(dialog.isOKActionEnabled)
    }

    fun testAPairingCommittedWhileTheChooserIsOpenIsShown() {
        val unpaired = elixir("Choice unpaired")
        val erlang = erlang()
        val dialog = dialog()

        SdkFixtures.commitPairing(unpaired, erlang)
        SdkFixtures.settle()

        assertEquals(listOf("Choice unpaired  Erlang SDK 'Choice Erlang', OTP 27.3.4"), dialog.panel.state.entries.map(dialog.panel::rowText))
    }

    fun testAddKeepsAPairingCommittedWhileTheChooserIsOpen() {
        scriptAdd(pickInstalledErlang)
        val unpaired = elixir("Choice unpaired")
        val erlang = erlang()
        val dialog = dialog()

        SdkFixtures.commitPairing(unpaired, erlang)
        dialog.panel.addButton.doClick()

        assertEquals("Choice Erlang", storedErlangName(unpaired))
    }

    fun testRenamingThePickedSdkKeepsThePick() {
        elixir("Choice A")
        val dialog = dialog()
        dialog.panel.pick(dialog.panel.state.entries.single())

        SdkFixtures.renameInTable("Choice A", "Choice A renamed")
        SdkFixtures.settle()

        assertEquals("Choice A renamed", dialog.panel.state.selected?.sdk?.name)
        assertTrue(dialog.isOKActionEnabled)
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

        val dialog = offerDialog()

        val first = dialog.panel.waitFor(OFFER)
        val second = dialog.panel.waitFor(ANOTHER)
        assertInstanceOf(first, JRadioButton::class.java)
        assertInstanceOf(second, JRadioButton::class.java)
        assertEquals("the radios", listOf(false, false), listOf(first.isSelected, second.isSelected))
        assertNull(dialog.panel.state.selected)
        assertFalse(dialog.isOKActionEnabled)
    }

    fun testNoOfferShowsNoRadios() {
        val panel = offerPanel {}

        dispatchForAWhile()

        assertNull(panel.shownOffer())
        assertNull(panel.shown { it == ANOTHER })
        assertNull(panel.shown { it == CHECKING })
    }

    fun testTheBoxesOpenWithTheFirstRadioAndCloseWithTheSecond() {
        toolManager.pinsTheOffer()
        val panel = offerPanel {}
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
        val dialog = offerDialog()
        dialog.panel.takeTheOffer(OFFER, EARLIER)
        assertFalse(EARLIER == dialog.selectedModel().name)

        dialog.panel.waitFor(ANOTHER).doClick()

        assertEquals(EARLIER, dialog.selectedModel().name)
        assertTrue(dialog.isOKActionEnabled)
        assertEquals("the radios", listOf(false, true), listOf(dialog.panel.waitFor(OFFER).isSelected, dialog.panel.waitFor(ANOTHER).isSelected))
    }

    fun testTheSecondRadioWithNoEarlierRowLeavesOkOff() {
        toolManager.pinsTheOffer()
        val dialog = offerDialog()
        dialog.panel.takeTheOffer(OFFER, null)
        assertTrue(dialog.isOKActionEnabled)

        dialog.panel.waitFor(ANOTHER).doClick()

        assertNull(dialog.panel.state.selected)
        assertFalse(dialog.isOKActionEnabled)
        assertTrue(dialog.panel.waitFor(ANOTHER).isSelected)
    }

    fun testSelectingARowInTheListChoosesTheSecondRadio() {
        elixir(EARLIER)
        toolManager.pinsTheOffer()
        val panel = offerPanel {}
        val first = panel.waitFor(OFFER).also { it.doClick() }
        val second = panel.waitFor(ANOTHER)

        UIUtil.uiTraverser(panel).filter(JBList::class.java).first()!!.setSelectedValue(pick(EARLIER)(panel.state), true)

        assertEquals(EARLIER, panel.state.selected?.sdk?.name)
        assertEquals("the radios", listOf(false, true), listOf(first.isSelected, second.isSelected))
    }

    fun testAddChoosesTheSecondRadio() {
        scriptAdd(pickInstalledErlang)
        toolManager.pins(elixirInstall("1.17.3", "27"), install("27", "27.3.4"))
        val panel = offerPanel {}
        panel.waitFor(OFFER)
        val second = panel.waitFor(ANOTHER)

        panel.addButton.doClick()

        assertNotNull(panel.state.selected)
        assertTrue(second.isSelected)
    }

    fun testTheOffersModelUnderTheSecondRadioHandsOverNothing() {
        toolManager.pinsTheOffer()
        val picks = mutableListOf<SdkChoicePick?>()
        val panel = offerPanel { picks += it }
        panel.takeTheOffer(OFFER, null)
        val made = panel.state.selected!!.sdk
        val inStep = panel.waitFor(IN_STEP).also { it.doClick() }
        val second = panel.waitFor(ANOTHER).also { it.doClick() }

        panel.pickRow(made.name)

        assertSame(made, picks.last()?.entry?.sdk)
        assertNull(picks.last()?.optIns)
        assertTrue(second.isSelected)
        assertEquals("the box", listOf(false, false), listOf(inStep.isEnabled, inStep.isSelected))
    }

    fun testSeveralKeepTheFirstRadioAndSayToPickOne() {
        val several = severalOnTheInstall()
        val dialog = offerDialog()
        val panel = dialog.panel
        val first = panel.waitFor(ELIXIR_ONLY_OFFER)
        val second = panel.waitFor(ANOTHER)
        val inStep = panel.waitFor(IN_STEP)

        first.doClick()

        panel.waitFor(SEVERAL) { it == SEVERAL }
        assertTrue(first.isSelected)
        assertNull(panel.state.selected)
        assertFalse(dialog.isOKActionEnabled)

        panel.pickRow(several[1].name)

        assertSame(several[1], panel.state.selected?.sdk)
        assertTrue(dialog.isOKActionEnabled)
        assertEquals("the radios", listOf(true, false), listOf(first.isSelected, second.isSelected))
        assertTrue("the boxes", inStep.isEnabled)
        assertNull("the line, with one picked", panel.shown { it == SEVERAL })
    }

    fun testAnotherRowThanTheSeveralMovesToTheSecondRadio() {
        severalOnTheInstall()
        elixir(EARLIER)
        val panel = offerPanel {}
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
            val dialog = offerDialog()
            dialog.panel.takeTheOffer(ELIXIR_ONLY_OFFER, earlier)

            dialog.assertPutBack(ELIXIR_ONLY_OFFER, earlier)

            if (earlier == null) {
                asked.events.clear()
                dialog.panel.waitFor(ELIXIR_ONLY_OFFER).doClick()
                assertEquals("the first radio runs the step again", listOf("erlang"), asked.events)
            }
        }
    }

    fun testCancellingTheOffersProgressPutsTheChoiceBack() {
        val cancelled: StepAnswer = { _, _ -> throw CancellationException() }
        ScriptedErlangStep(asked, cancelled, cancelled).install(testRootDisposable)
        toolManager.pins(elixirInstall("1.18.4", "27"), null)

        withAndWithoutAnEarlierRow { earlier ->
            val dialog = offerDialog()

            val thrown = runCatching { dialog.panel.takeTheOffer(ELIXIR_ONLY_OFFER, earlier) }.exceptionOrNull()

            assertInstanceOf(thrown, CancellationException::class.java)
            dialog.assertPutBack(ELIXIR_ONLY_OFFER, earlier)
        }
    }

    fun testRemovingTheTakenModelPutsTheChoiceBack() {
        toolManager.pinsTheOffer()

        withAndWithoutAnEarlierRow { earlier ->
            val dialog = offerDialog()
            dialog.panel.takeTheOffer(OFFER, earlier)
            val made = dialog.selectedModel()

            WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(made) }
            SdkFixtures.settle()

            dialog.assertPutBack(OFFER, earlier)
            if (earlier == null) dialog.assertTheFirstRadioTakesAgain(OFFER)
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
            val dialog = offerDialog()
            dialog.panel.takeTheOffer(OFFER, earlier)
            val made = dialog.selectedModel()

            try {
                edit(made)
                SdkFixtures.settle()

                dialog.assertPutBack(OFFER, earlier)
            } finally {
                WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(made) }
            }
        }
    }

    fun testRenamingTheTakenModelKeepsTheChoice() {
        toolManager.pinsTheOffer()
        val dialog = offerDialog()
        dialog.panel.takeTheOffer(OFFER, null)
        val renamed = SdkFixtures.renameInTable(dialog.selectedModel().name, "Choice renamed")

        SdkFixtures.settle()

        assertTrue("the first radio stands", dialog.panel.waitFor(OFFER).isSelected)
        assertSame(renamed, dialog.panel.state.selected?.sdk)
        assertTrue(dialog.isOKActionEnabled)
    }

    fun testEditingThePickedOneOfTheSeveralPutsTheChoiceBack() {
        val several = severalOnTheInstall()
        val dialog = offerDialog()
        dialog.panel.waitFor(ELIXIR_ONLY_OFFER).doClick()
        dialog.panel.pickRow(several[0].name)

        SdkFixtures.commit(several[0]) { homePath = elixirInstall("1.19.0", "27") }
        SdkFixtures.settle()

        dialog.assertPutBack(ELIXIR_ONLY_OFFER, null)
    }

    fun testEditingEveryOneOfTheSeveralWithNonePickedPutsTheChoiceBack() {
        val several = severalOnTheInstall()
        val dialog = offerDialog()
        dialog.panel.waitFor(ELIXIR_ONLY_OFFER).doClick()

        several.forEachIndexed { index, sdk -> SdkFixtures.commit(sdk) { homePath = elixirInstall("1.19.$index", "27") } }
        SdkFixtures.settle()

        dialog.assertPutBack(ELIXIR_ONLY_OFFER, null)
    }

    fun testPickingAnEditedOneOfTheSeveralMovesToTheSecondRadio() {
        val several = severalOnTheInstall()
        val dialog = offerDialog()
        val first = dialog.panel.waitFor(ELIXIR_ONLY_OFFER).also { it.doClick() }
        val second = dialog.panel.waitFor(ANOTHER)

        SdkFixtures.commit(several[0]) { homePath = elixirInstall("1.19.0", "27") }
        SdkFixtures.settle()
        assertTrue("another of the Several still stands behind the radio", first.isSelected)
        dialog.panel.pickRow(several[0].name)

        assertEquals("the radios", listOf(false, true), listOf(first.isSelected, second.isSelected))
    }

    fun testAnOfferOfOtherPinsAtANewLocationPutsTheChoiceBack() {
        val folder = newFolder()
        toolManager.pinsAnotherIn(folder)

        withAndWithoutAnEarlierRow { earlier ->
            val dialog = offerDialog()
            dialog.panel.takeTheOffer(OFFER, earlier)

            dialog.panel.retarget(project.getEelDescriptor(), listOf(folder))

            dialog.assertPutBack(FOLDER_OFFER, earlier)
            if (earlier == null) dialog.assertTheFirstRadioTakesAgain(FOLDER_OFFER)
        }
    }

    fun testARetargetThatDropsTheTakenModelPutsTheChoiceBack() {
        toolManager.pinsTheOffer()

        withAndWithoutAnEarlierRow { earlier ->
            val dialog = offerDialog()
            dialog.panel.takeTheOffer(OFFER, earlier)
            val made = dialog.selectedModel()

            // Gone before the panel's own reload can run, so only the retarget can find it gone.
            WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(made) }
            dialog.panel.retarget(Elsewhere, listOf(offerFolder))

            assertFalse("the retarget puts the choice back", dialog.panel.state.offerChosen)
            dialog.assertPutBack(OFFER, earlier)
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
            val dialog = offerDialog()
            val panel = dialog.panel
            panel.takeTheOffer(OFFER, earlier)

            panel.retarget(project.getEelDescriptor(), listOf(folder))

            PlatformTestUtil.waitWithEventsDispatching("the choice is back on $earlier", { panel.state.selected?.sdk?.name == earlier }, 10)
            assertEquals("OK", earlier != null, dialog.isOKActionEnabled)
            assertNull(panel.shownOffer())
            assertNull(panel.shown { it == ANOTHER })
        }
    }

    fun testAnAnswerOfOtherPinsLeavesARowPickedUnderTheSecondRadio() {
        elixir(EARLIER)
        val folder = newFolder()
        toolManager.pinsAnotherIn(folder)
        val dialog = offerDialog()
        val panel = dialog.panel
        panel.takeTheOffer(OFFER, null)
        panel.pickRow(EARLIER)

        panel.retarget(project.getEelDescriptor(), listOf(folder))

        panel.waitFor(FOLDER_OFFER)
        assertEquals(EARLIER, panel.state.selected?.sdk?.name)
        assertTrue(dialog.isOKActionEnabled)
        assertTrue(panel.waitFor(ANOTHER).isSelected)
    }

    fun testRemovingAnotherOfTheSeveralKeepsThePick() {
        val several = severalOnTheInstall()
        val dialog = offerDialog()
        val panel = dialog.panel
        val first = panel.waitFor(ELIXIR_ONLY_OFFER).also { it.doClick() }
        val second = panel.waitFor(ANOTHER)
        panel.pickRow(several[0].name)

        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(several[1]) }
        SdkFixtures.settle()

        assertSame(several[0], panel.state.selected?.sdk)
        assertTrue(dialog.isOKActionEnabled)
        assertEquals("the radios", listOf(true, false), listOf(first.isSelected, second.isSelected))
    }

    fun testChoosingTheFirstRadioAgainKeepsTheRowPickedInTheSeveral() {
        val several = severalOnTheInstall()
        val dialog = offerDialog()
        val first = dialog.panel.waitFor(ELIXIR_ONLY_OFFER).also { it.doClick() }
        dialog.panel.pickRow(several[1].name)

        first.doClick()

        assertSame(several[1], dialog.panel.state.selected?.sdk)
        assertTrue(dialog.isOKActionEnabled)
    }

    fun testAReloadLeavesTheRadioAlone() {
        elixir(EARLIER)
        toolManager.pinsTheOffer()
        val panel = offerPanel {}
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
            val panel = offerPanel {}

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
        pickWhileTheCheckRuns(answered = { dialog -> assertTrue(dialog.panel.waitFor(ANOTHER).isSelected) })
    }

    /** With the check held, picks [EARLIER] and does [whileHeld]; once the offer shows, it is still picked, and [answered]. */
    private fun pickWhileTheCheckRuns(whileHeld: (SdkChoiceDialog) -> Unit = {}, answered: (SdkChoiceDialog) -> Unit = {}) {
        elixir(EARLIER)
        val release = toolManager.holdsTheOffer()
        try {
            val dialog = offerDialog()
            dialog.panel.pickRow(EARLIER)
            whileHeld(dialog)

            release.countDown()

            dialog.panel.waitFor(OFFER)
            assertEquals(EARLIER, dialog.panel.state.selected?.sdk?.name)
            answered(dialog)
        } finally {
            release.countDown()
        }
    }

    fun testTakingTheOfferSelectsTheModelPairedAsMisePins() {
        val elixirHome = elixirInstall("1.18.4", "27")
        val erlangHome = install("27", "27.3.4")
        toolManager.pins(elixirHome, erlangHome)
        val dialog = offerDialog()

        dialog.panel.waitFor(OFFER).doClick()

        val made = dialog.selectedModel()
        assertEquals(listOf(made), elixirSdksAt(elixirHome))
        assertPairedWithErlangAt(made, erlangHome)
        assertTrue(dialog.isOKActionEnabled)

        val again = offerDialog()
        again.panel.waitFor(OFFER).doClick()

        assertSame(ProjectJdkTable.getInstance().findJdk(made.name), again.panel.state.selected?.sdk)
        assertEquals(listOf(made), elixirSdksAt(elixirHome))
    }

    fun testTakingTheOfferRepairsNoModel() {
        val elixirHome = elixirInstall("1.18.4", "27")
        val otherErlang = registeredErlang("Choice other Erlang", install("26", "26.2.5"))
        val other = pairedElixir("Choice other pairing", elixirHome, otherErlang)
        val erlangHome = install("27", "27.3.4")
        toolManager.pins(elixirHome, erlangHome)
        val dialog = offerDialog()

        dialog.panel.waitFor(OFFER).doClick()

        assertEquals(otherErlang.name, storedErlangName(other))
        val selected = dialog.selectedModel()
        assertNotSame(other, selected)
        assertPairedWithErlangAt(selected, erlangHome)
    }

    fun testAnElixirPinAloneWithNoModelAsksTheErlangStep() {
        val elixirHome = elixirInstall("1.18.4", "27")
        val erlang = registeredErlang("Choice step Erlang", install("27", "27.3.4"))
        ScriptedErlangStep(asked, ScriptedErlangStep.pick(erlang.name)).install(testRootDisposable)
        toolManager.pins(elixirHome, null)
        val dialog = offerDialog()

        dialog.panel.waitFor(ELIXIR_ONLY_OFFER).doClick()

        assertEquals(listOf("erlang"), asked.events)
        val selected = dialog.selectedModel()
        assertEquals(listOf(selected), elixirSdksAt(elixirHome))
        assertEquals(erlang.name, storedErlangName(selected))
    }

    fun testAnElixirPinAloneWithOneModelIsSelected() {
        val elixirHome = elixirInstall("1.18.4", "27")
        val existing = pairedElixir("Choice one on the install", elixirHome, registeredErlang("Choice one's Erlang", install("26", "26.2.5")))
        toolManager.pins(elixirHome, null)
        val dialog = offerDialog()

        dialog.panel.waitFor(ELIXIR_ONLY_OFFER).doClick()

        assertEmpty(asked.events)
        assertSame(existing, dialog.panel.state.selected?.sdk)
    }

    fun testAnElixirPinAloneWithSeveralModelsSelectsNothing() {
        severalOnTheInstall()
        val dialog = offerDialog()

        dialog.panel.waitFor(ELIXIR_ONLY_OFFER).doClick()

        assertEmpty(asked.events)
        assertNull(dialog.panel.state.selected)
        assertFalse(dialog.isOKActionEnabled)
    }

    fun testTheEnvironmentBoxFollowsTheToolManagerBox() {
        toolManager.pinsTheOffer()
        val dialog = offerDialog()
        dialog.panel.waitFor(OFFER).doClick()
        val inStep = dialog.panel.waitFor(IN_STEP)
        val environment = dialog.panel.waitFor(ENVIRONMENT)

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
        val dialog = offerDialog()

        dialog.panel.waitFor(OFFER).doClick()

        for (box in listOf(dialog.panel.waitFor(IN_STEP), dialog.panel.waitFor(ENVIRONMENT))) {
            assertEquals(box.text, listOf(false, true), listOf(box.isEnabled, box.isSelected))
        }
    }

    fun testAnUntrustedConfigShowsItsMessageAndNoOffer() {
        toolManager.answer = { ToolManagerResult.Error(toolManager.name, MiseToolManager.untrustedConfigDescription("/project/mise.toml")) }

        val dialog = offerDialog()

        dialog.panel.waitFor("is not trusted") { "is not trusted" in it }
        dialog.panel.waitFor(CHECK_AGAIN)
        assertNull(dialog.panel.shownOffer())
    }

    fun testCheckAgainRunsTheCheckAgain() {
        toolManager.answer = { ToolManagerResult.Error(toolManager.name, MiseToolManager.untrustedConfigDescription("/project/mise.toml")) }
        val dialog = offerDialog()
        dialog.panel.waitFor("is not trusted") { "is not trusted" in it }
        toolManager.pinsTheOffer()

        dialog.panel.waitFor(CHECK_AGAIN).doClick()

        dialog.panel.waitFor(OFFER)
        assertNull(dialog.panel.shown { "is not trusted" in it })
    }

    fun testTheListWorksWhileTheCheckRuns() {
        pickWhileTheCheckRuns(whileHeld = { dialog ->
            assertTrue(dialog.isOKActionEnabled)
            assertNull(dialog.panel.shownOffer())
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
            val panel = offerDialog().panel
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
        val panel = offerDialog().panel
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

        val written = askWithTheOffer { panel ->
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
        val picks = mutableListOf<SdkChoicePick?>()
        val panel = offerPanel { picks += it }
        panel.waitFor(OFFER).doClick()
        assertNull("nothing ticked", picks.last()?.optIns)

        panel.waitFor(IN_STEP).doClick()
        assertEquals(ToolManagerOptIns(toolManager.name, environment = false), picks.last()?.optIns)

        panel.waitFor(ENVIRONMENT).doClick()
        assertEquals(ToolManagerOptIns(toolManager.name, environment = true), picks.last()?.optIns)

        panel.waitFor(IN_STEP).doClick()
        assertNull("the tool manager unticked", picks.last()?.optIns)
        assertSame(panel.state.selected, picks.last()?.entry)
    }

    fun testAListedPickHandsOverTheOptInsAndAnUnlistedOneDisablesTheBoxes() {
        val several = severalOnTheInstall()
        elixir("Choice A")
        val picks = mutableListOf<SdkChoicePick?>()
        val panel = offerPanel { picks += it }
        panel.waitFor(ELIXIR_ONLY_OFFER).doClick()
        val inStep = panel.waitFor(IN_STEP)
        val environment = panel.waitFor(ENVIRONMENT)
        assertTrue("the boxes follow the radio, with nothing picked yet", inStep.isEnabled)

        panel.pickRow(several[1].name)
        assertTrue(inStep.isEnabled)
        inStep.doClick()
        environment.doClick()

        assertEquals(ToolManagerOptIns(toolManager.name, environment = true), picks.last()?.optIns)

        panel.pickRow("Choice A")

        assertEquals(listOf(false, false), listOf(inStep.isEnabled, environment.isEnabled))
        assertEquals("Choice A", picks.last()?.entry?.sdk?.name)
        assertNull(picks.last()?.optIns)
    }

    fun testATickedBoxShowsUntickedWhileItHandsOverNothing() {
        elixir("Choice A")
        toolManager.pinsTheOffer()
        val picks = mutableListOf<SdkChoicePick?>()
        val panel = offerPanel { picks += it }
        val first = panel.waitFor(OFFER).also { it.doClick() }
        val inStep = panel.waitFor(IN_STEP)
        val environment = panel.waitFor(ENVIRONMENT)
        inStep.doClick()
        environment.doClick()

        panel.pickRow("Choice A")

        assertNull(picks.last()?.optIns)
        assertEquals("shown ticked", listOf(false, false), listOf(inStep.isSelected, environment.isSelected))

        first.doClick()

        assertEquals(ToolManagerOptIns(toolManager.name, environment = true), picks.last()?.optIns)
        assertEquals("shown ticked", listOf(true, true), listOf(inStep.isSelected, environment.isSelected))
    }

    fun testUntickingTheToolManagerShowsTheEnvironmentUnticked() {
        toolManager.pinsTheOffer()
        val picks = mutableListOf<SdkChoicePick?>()
        val panel = offerPanel { picks += it }
        panel.waitFor(OFFER).doClick()
        val inStep = panel.waitFor(IN_STEP)
        val environment = panel.waitFor(ENVIRONMENT)
        inStep.doClick()
        environment.doClick()

        inStep.doClick()

        assertNull(picks.last()?.optIns)
        assertFalse("the environment shown ticked", environment.isSelected)
    }

    fun testAStoredEnvironmentFlagFollowsTheUntickedBox() {
        ToolManagerSettings.getInstance(project).setEnvironmentEnabled(toolManager, true)
        toolManager.pinsTheOffer()

        askWithTheOffer { panel ->
            panel.waitFor(OFFER).doClick()
            panel.waitFor(IN_STEP).doClick()
        }

        assertEquals(listOf(true, false), toolManagerSettings())
    }

    fun testCancelWritesNoOptIn() {
        val elixirHome = elixirInstall("1.18.4", "27")
        toolManager.pins(elixirHome, install("27", "27.3.4"))
        val changes = settingsChanges(project, testRootDisposable) {}

        val written = askWithTheOffer(ok = false) { panel ->
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

        val written = askWithTheOffer { panel -> panel.waitFor(OFFER).doClick() }

        assertSame(written, ModuleRootManager.getInstance(module).sdk)
        assertEquals(listOf(false, false), toolManagerSettings())
        assertEquals("settings changes", 0, changes.size)
    }

    /** The tool manager's settings in this project: whether it is on, and whether its environment is. */
    private fun toolManagerSettings(): List<Boolean> =
        ToolManagerSettings.getInstance(project).run { listOf(isEnabled(toolManager), isEnvironmentEnabled(toolManager)) }

    /**
     * Asks the chooser for [module], with the offer folder as its content root so the tool managers are asked about
     * that, doing [answer] in its dialog's panel, then pressing OK, or Cancel unless [ok]; answers what is written.
     */
    private fun askWithTheOffer(ok: Boolean = true, answer: (SdkChoicePanel) -> Unit): Sdk? {
        PsiTestUtil.addContentRoot(module, LocalFileSystem.getInstance().refreshAndFindFileByNioFile(offerFolder)!!)
        return SdkChoice { _, modules, state ->
            val dialog = SdkChoiceDialog(null, modules, state).also { Disposer.register(testRootDisposable, it.disposable) }
            answer(dialog.panel)
            state.pick.takeIf { ok }
        }.ask(project, listOf(module))
    }

    /** The chooser's panel, with the tool managers asked about the offer folder, telling [onPick] each pick. */
    private fun offerPanel(onPick: (SdkChoicePick?) -> Unit): SdkChoicePanel =
        SdkChoicePanel(state(folders = listOf(offerFolder)), testRootDisposable, onPick)

    private val offerFolder: Path by lazy { newFolder() }

    /** The chooser's dialog for [module], with the tool managers asked about a folder of its own. */
    private fun offerDialog(): SdkChoiceDialog = dialog(listOf(offerFolder))

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
    private fun SdkChoiceDialog.assertPutBack(offer: String, earlier: String?) {
        val first = panel.waitFor(offer)
        val second = panel.waitFor(ANOTHER)
        assertEquals(earlier, panel.state.selected?.sdk?.name)
        assertEquals("OK", earlier != null, isOKActionEnabled)
        assertEquals("the radios", listOf(false, earlier != null), listOf(first.isSelected, second.isSelected))
        assertTrue("the first radio can be chosen", first.isEnabled)
    }

    private fun SdkChoiceDialog.assertTheFirstRadioTakesAgain(offer: String) {
        val first = panel.waitFor(offer)
        first.doClick()
        assertTrue(first.isSelected)
        assertNotNull(panel.state.selected)
    }

    /** The Model selected in the dialog's list. */
    private fun SdkChoiceDialog.selectedModel(): Sdk = panel.state.selected?.sdk ?: throw AssertionError("a Model is selected")

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
        SdkChoiceState(project, target, null, folders, hostCommits = true).also { Disposer.register(testRootDisposable, it) }

    /** An environment no Eel resolver knows, so every SDK is visible for it. */
    private object Elsewhere : EelDescriptor {
        override val name = "elsewhere"
        override val osFamily = EelOsFamily.Posix
    }

    private fun dialog(folders: List<Path> = emptyList()): SdkChoiceDialog =
        SdkChoiceDialog(null, listOf(module), state(folders = folders)).also { Disposer.register(testRootDisposable, it.disposable) }
}

private const val CHECK_AGAIN = "Check again"
private const val ANOTHER = "Use another SDK:"
private const val CHECKING = "Checking mise..."
private const val SEVERAL = "Several SDKs use this Elixir. Pick one in the list."
private const val EARLIER = "Choice A"
