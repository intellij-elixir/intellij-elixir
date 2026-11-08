package org.elixir_lang.sdk.choice

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.mix.project._import.Builder
import org.elixir_lang.sdk.SdkDetectionContext
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.storedErlangName
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import java.nio.file.Path

/** The Mix import's SDK step, built by `Provider` as the import wizard builds it. */
class SdkChoiceStepTest : PlatformTestCase() {
    private lateinit var table: SdkFixtures.TableSnapshot
    private val toolManager = FakeToolManager()
    /** The import's `Builder`, which `Provider` hands its steps. */
    private val builder = Builder()
    private var defaultProjectSdk: Sdk? = null

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        // The step asks the tool managers about its directory, and a real mise there answers from this machine's config.
        toolManager.install(testRootDisposable)
        defaultProjectSdk = defaultProjectRoots().projectSdk
    }

    override fun tearDown() = runAll(
        { WriteAction.run<Throwable> { defaultProjectRoots().projectSdk = defaultProjectSdk } },
        { SdkDetectionContext.clear() },
        { table.restore() },
        { super.tearDown() },
    )

    fun testNothingIsPickedWhileTheDefaultProjectsSdkIsAnElixirModel() {
        val elixir = pairedForNext(ELIXIR, "Step Erlang", testRootDisposable)
        WriteAction.run<Throwable> { defaultProjectRoots().projectSdk = elixir }
        val step = sdkStep(newProject())

        step.updateStep()
        step.updateDataModel()

        assertNull(builder.elixirSdk)
    }

    fun testAnUnpairedModelIsListedAndNextRefusesIt() {
        SdkFixtures.register(SdkFixtures.elixirSdk(UNPAIRED, elixirInstall("1.18.4", "27")), testRootDisposable)
        pairedForNext(ELIXIR, "Step Erlang", testRootDisposable)
        val step = sdkStep(newProject())
        step.updateStep()

        assertEquals(listOf(ELIXIR, UNPAIRED), step.panel.state.entries.map { it.sdk.name })
        step.pick(UNPAIRED)
        assertThrows(ConfigurationException::class.java, "Internal Erlang SDK is not configured. Set it before picking this Elixir SDK.") {
            step.validate()
        }
    }

    fun testAPairingCommittedWhileTheStepIsOpenSurvivesNext() {
        val elixir = pairedForNext(ELIXIR, "Step Erlang 1", testRootDisposable)
        val repaired = erlangForNext("Step Erlang 2", testRootDisposable)
        val step = sdkStep(newProject())
        step.updateStep()
        step.pick(ELIXIR)

        SdkFixtures.commitPairing(elixir, repaired)
        assertTrue(step.validate())
        step.updateDataModel()

        assertEquals("Step Erlang 2", storedErlangName(elixir))
        assertSame(elixir, builder.elixirSdk)
    }

    fun testInANewProjectTheOfferAndAnEnabledFirstBoxShow() {
        toolManager.pinsTheOffer()
        val step = sdkStep(newProject())
        step.updateStep()

        step.component.waitFor(OFFER).doClick()

        assertTrue(step.component.waitFor(IN_STEP).isEnabled)
    }

    fun testAddingToAnOpenProjectShowsTheBoxesDisabled() {
        toolManager.pinsTheOffer()
        val step = sdkStep(WizardContext(project, Disposer.newDisposable(testRootDisposable)))
        step.updateStep()

        step.component.waitFor(OFFER).doClick()

        assertEquals(listOf(false, false), listOf(step.component.waitFor(IN_STEP), step.component.waitFor(ENVIRONMENT)).map { it.isEnabled })
        step.component.waitFor(SETTINGS_PAGE) { SETTINGS_PAGE in it }
    }

    fun testOnImportModuleTheOfferIsForTheFolderChosen() {
        val chosen = FileUtil.createTempDirectory("chosen", null).path
        toolManager.pinsAnotherIn(Path.of(chosen))
        // Import Module's wizard works in the open project's folder, and hands the folder chosen to the builder once the steps are built.
        val context = WizardContext(project, Disposer.newDisposable(testRootDisposable))
        val step = sdkStep(context)
        builder.fileToImport = chosen

        step.updateStep()

        step.component.waitFor(FOLDER_OFFER)
    }

    fun testTheDirectoryIsPublishedForDetectionThenCleared() {
        val context = newProject()
        val step = sdkStep(context)

        step.updateStep()
        assertEquals(Path.of(context.projectFileDirectory), SdkDetectionContext.resolve())

        step.disposeUIResources()
        assertNull(SdkDetectionContext.resolve())
    }

    private fun defaultProjectRoots() = ProjectRootManager.getInstance(ProjectManager.getInstance().defaultProject)

    private fun newProject(): WizardContext = WizardContext(null, Disposer.newDisposable(testRootDisposable))

    /** The import's first step, over a new directory. */
    private fun sdkStep(context: WizardContext): SdkChoiceStep {
        context.setProjectFileDirectory(FileUtil.createTempDirectory("mixImport", null).path)
        context.projectBuilder = builder
        return mixImportStep(context, builder)
    }

    private companion object {
        const val ELIXIR = "Step A"
        const val UNPAIRED = "Step unpaired"
        const val SETTINGS_PAGE = "Settings > Languages & Frameworks > Elixir > Tool Managers"
    }
}
