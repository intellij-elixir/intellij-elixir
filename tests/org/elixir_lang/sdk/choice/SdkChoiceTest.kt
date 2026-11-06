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
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.registerOrReplaceServiceInstance
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
import javax.swing.JPanel

/** The chooser on IntelliJ IDEA, asked for this test's modules, with its dialog answered by the test. */
class SdkChoiceTest : HeavyTestCase() {
    private val shown = mutableListOf<SdkChoiceState>()
    private val asked = Asked()
    private lateinit var table: SdkFixtures.TableSnapshot

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        asked.recordMessages(testRootDisposable)
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

    /** Asks the chooser for [modules], its dialog answered by [answer] from what it shows. */
    private fun ask(answer: (SdkChoiceState) -> SdkChoiceEntry?, modules: List<Module> = listOf(module)): Sdk? =
        SdkChoice { _, _, state ->
            shown += state
            answer(state)
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
            dialog.state.entries.map(dialog::rowText),
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

        dialog.pick(dialog.state.entries.single())

        assertTrue(dialog.isOKActionEnabled)
    }

    fun testAnUnpairedSdkCanBePicked() {
        elixir("Choice unpaired")
        val dialog = dialog()

        dialog.pick(dialog.state.entries.single())

        assertEquals("Choice unpaired", dialog.state.selected?.sdk?.name)
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

        dialog.add()

        val created = Facet.sdks().single()
        assertEquals(listOf(created.name), dialog.state.names())
        assertEquals(created.name, dialog.state.selected?.sdk?.name)
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
        dialog.pick(dialog.state.entries.single())

        dialog.add()

        assertEquals(listOf("Choice A"), Facet.sdks().map { it.name })
        assertEquals("Choice A", dialog.state.selected?.sdk?.name)
        assertTrue(dialog.isOKActionEnabled)
    }

    fun testManageSdksOpensTheSdksPage() {
        val recorded = RecordedChooser(testRootDisposable)

        dialog().manageSdksLink.doClick()

        assertEquals(listOf(project to SettingsPage.SDKS), recorded.settingsOpened)
    }

    fun testAnSdkAddedToTheTableIsListedAndThePickKept() {
        elixir("Choice A")
        val dialog = dialog()
        dialog.pick(dialog.state.entries.single())

        elixir("Choice B")
        SdkFixtures.settle()

        assertEquals(listOf("Choice A", "Choice B"), dialog.state.names())
        assertEquals("Choice A", dialog.state.selected?.sdk?.name)
        assertTrue(dialog.isOKActionEnabled)
    }

    fun testRemovingThePickedSdkFromTheTableClearsThePick() {
        val picked = elixir("Choice A")
        elixir("Choice B")
        val dialog = dialog()
        dialog.pick(dialog.state.entries.first())

        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(picked) }
        SdkFixtures.settle()

        assertEquals(listOf("Choice B"), dialog.state.names())
        assertNull(dialog.state.selected)
        assertFalse(dialog.isOKActionEnabled)
    }

    fun testAPairingCommittedWhileTheChooserIsOpenIsShown() {
        val unpaired = elixir("Choice unpaired")
        val erlang = erlang()
        val dialog = dialog()

        SdkFixtures.commitPairing(unpaired, erlang)
        SdkFixtures.settle()

        assertEquals(listOf("Choice unpaired  Erlang SDK 'Choice Erlang', OTP 27.3.4"), dialog.state.entries.map(dialog::rowText))
    }

    fun testAddKeepsAPairingCommittedWhileTheChooserIsOpen() {
        scriptAdd(pickInstalledErlang)
        val unpaired = elixir("Choice unpaired")
        val erlang = erlang()
        val dialog = dialog()

        SdkFixtures.commitPairing(unpaired, erlang)
        dialog.add()

        assertEquals("Choice Erlang", storedErlangName(unpaired))
    }

    fun testRenamingThePickedSdkKeepsThePick() {
        elixir("Choice A")
        val dialog = dialog()
        dialog.pick(dialog.state.entries.single())

        SdkFixtures.renameInTable("Choice A", "Choice A renamed")
        SdkFixtures.settle()

        assertEquals("Choice A renamed", dialog.state.selected?.sdk?.name)
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

    fun testAddsErlangStepIsGivenTheTargetsErlangSdks() {
        SdkFixtures.register(
            SdkFixtures.erlangSdk("Choice WSL Erlang", "${LEGACY_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\erlang"),
            testRootDisposable,
        )
        val step = scriptAdd(ScriptedErlangStep.cancel)

        wslTargetState().add(JPanel())

        assertContainsElements(step.offered.single().mapNotNull { it.sdk?.name }, "Choice WSL Erlang", "Choice installed Erlang")
    }

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

    private fun state(project: Project = this.project, target: EelDescriptor? = project.getEelDescriptor()): SdkChoiceState =
        SdkChoiceState(project, target, null).also { Disposer.register(testRootDisposable, it) }

    private fun dialog(): SdkChoiceDialog =
        SdkChoiceDialog(null, listOf(module), state()).also { Disposer.register(testRootDisposable, it.disposable) }
}
