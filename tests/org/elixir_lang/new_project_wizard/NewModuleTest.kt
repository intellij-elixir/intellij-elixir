package org.elixir_lang.new_project_wizard

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModifiableModuleModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ui.configuration.DefaultModulesProvider
import com.intellij.openapi.roots.ui.configuration.ModulesProvider
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.runAll
import com.intellij.ui.UIBundle
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.system.OS
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironment
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkDetectionContext
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.choice.ENVIRONMENT
import org.elixir_lang.sdk.choice.FakeToolManager
import org.elixir_lang.sdk.choice.IN_STEP
import org.elixir_lang.sdk.choice.OFFER
import org.elixir_lang.sdk.choice.TOOL_MANAGER_PROBE
import org.elixir_lang.sdk.choice.TOOL_MANAGER_PROBE_LINE
import org.elixir_lang.sdk.choice.replaceToolManagerEnvironments
import org.elixir_lang.sdk.choice.waitFor
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.tool_manager.ToolManagerSettings
import org.elixir_lang.tool_manager.elixirSdksAt
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** File > New > Module with the Elixir generator, in this test's project, as the wizard builds its steps. */
class NewModuleTest : HeavyTestCase() {
    private lateinit var table: SdkFixtures.TableSnapshot
    private val toolManager = FakeToolManager()

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        // The row asks the tool managers about its location, and a real mise there answers from this machine's config.
        toolManager.install(testRootDisposable)
    }

    override fun tearDown() = runAll(
        { SdkDetectionContext.clear() },
        { table.restore() },
        { super.tearDown() },
    )

    fun testANameAModuleAlreadyHasRefusesCreate() {
        val wizard = Wizard()

        wizard.base.name = module.name

        assertContainsElements(
            wizard.panel.validateAll().map { it.message },
            UIBundle.message("label.project.wizard.new.module.name.exists.error", module.name),
        )
    }

    fun testTheNewModulesFileIsUnderItsNameAtItsLocation() {
        val sdk = SdkFixtures.register(SdkFixtures.elixirSdk("New module Elixir", fakeHome("elixir/new-module")), testRootDisposable)
        val wizard = Wizard()

        wizard.commit(sdk)

        PlatformTestUtil.saveProject(project, true)
        assertTrue("no module file at <location>/extra/extra.iml", Files.isRegularFile(Path.of(wizard.location, "extra", "extra.iml")))
    }

    fun testTheOffersModelWithTheToolManagerTickedTurnsItOnInTheProject() = assertTakingTheOfferTickedTurnsTheToolManagerOn { Wizard() }

    fun testOnNewProjectTheOffersModelWithTheToolManagerTickedTurnsItOnInTheProjectMade() =
        assertTakingTheOfferTickedTurnsTheToolManagerOn { Wizard(WizardContext(null, Disposer.newDisposable(testRootDisposable))) }

    fun testMixNewGetsTheTickedEnvironmentOfTheToolManagerAskedAboutTheLocation() =
        assertMixNewGetsTheTickedEnvironment { Wizard() }

    fun testOnNewProjectMixNewGetsTheTickedEnvironmentOfTheToolManagerAskedAboutTheLocation() =
        assertMixNewGetsTheTickedEnvironment { Wizard(WizardContext(null, Disposer.newDisposable(testRootDisposable))) }

    fun testProjectStructuresNewModuleShowsTheBoxesDisabledAndLeavesTheToolManagerAlone() {
        val elixirHome = elixirInstall("1.18.4", "27")
        toolManager.pins(elixirHome, install("27", "27.3.4"))
        // Project Structure's wizard has its ModulesConfigurator, which is no DefaultModulesProvider.
        val wizard = Wizard(WizardContext(project, Disposer.newDisposable(testRootDisposable)).apply { modulesProvider = ModulesProvider.EMPTY_MODULES_PROVIDER })

        wizard.panel.waitFor(OFFER).doClick()

        assertEquals(listOf(false, false), listOf(wizard.panel.waitFor(IN_STEP), wizard.panel.waitFor(ENVIRONMENT)).map { it.isEnabled })
        wizard.panel.waitFor(SETTINGS_PAGE) { SETTINGS_PAGE in it }
        val made = wizard.commit()

        assertEquals(elixirSdksAt(elixirHome), listOfNotNull(ModuleRootManager.getInstance(made).sdk))
        assertFalse(ToolManagerSettings.getInstance(project).isEnabled(toolManager))
    }

    fun testInProjectStructuresModelCancelLeavesNoModule() {
        val sdk = SdkFixtures.register(SdkFixtures.elixirSdk("New module Elixir", fakeHome("elixir/new-module")), testRootDisposable)
        val model = ModuleManager.getInstance(project).getModifiableModel()
        val wizard = Wizard(projectStructure(model))

        wizard.commit(sdk)
        WriteAction.run<Throwable> { model.dispose() }

        assertNull(ModuleManager.getInstance(project).findModuleByName("extra"))
    }

    fun testInProjectStructuresModelApplyLeavesTheModuleWithThePick() {
        val sdk = SdkFixtures.register(SdkFixtures.elixirSdk("New module Elixir", fakeHome("elixir/new-module")), testRootDisposable)
        val model = ModuleManager.getInstance(project).getModifiableModel()
        val wizard = Wizard(projectStructure(model))

        wizard.commit(sdk)
        WriteAction.run<Throwable> { model.commit() }

        assertSame(sdk, ModuleManager.getInstance(project).findModuleByName("extra")?.let { ModuleRootManager.getInstance(it).sdk })
    }

    /** Project Structure's New Module, which hands the wizard its uncommitted [model]. */
    @Suppress("DEPRECATION") // The key Project Structure's wizard hands its model under; its replacement is @Internal.
    private fun projectStructure(model: ModifiableModuleModel) = WizardContext(project, Disposer.newDisposable(testRootDisposable)).apply {
        modulesProvider = ModulesProvider.EMPTY_MODULES_PROVIDER
        putUserData(NewProjectWizardStep.MODIFIABLE_MODULE_MODEL_KEY, model)
    }

    /**
     * In the wizard [opened] once mise pins an installed Elixir and Erlang, the offer taken with the tool manager's box
     * ticked gives the module made the offer's Model and turns the tool manager on.
     */
    private fun assertTakingTheOfferTickedTurnsTheToolManagerOn(opened: () -> Wizard) {
        val elixirHome = elixirInstall("1.18.4", "27")
        toolManager.pins(elixirHome, install("27", "27.3.4"))
        val wizard = opened()

        wizard.panel.waitFor(OFFER).doClick()
        wizard.panel.waitFor(IN_STEP).doClick()
        val made = wizard.commit()

        assertEquals(elixirSdksAt(elixirHome), listOfNotNull(ModuleRootManager.getInstance(made).sdk))
        assertTrue(ToolManagerSettings.getInstance(project).isEnabled(toolManager))
    }

    /**
     * In the wizard [opened] once mise pins an installed Elixir and Erlang, the offer taken with mise's environment
     * ticked gives `mix new` that environment, which mise reads for the location, as the offer did, before the project
     * adopts the boxes.
     */
    private fun assertMixNewGetsTheTickedEnvironment(opened: () -> Wizard) {
        val askedAbout = mutableListOf<Path>()
        replaceToolManagerEnvironments(
            testRootDisposable,
            mapOf(toolManager.name to ToolManagerEnvironmentResolver { dir, _ ->
                askedAbout.add(dir)
                ToolManagerEnvironment.Variables(TOOL_MANAGER_PROBE)
            }),
        )
        toolManager.pinsTheOffer()
        val wizard = opened()
        wizard.panel.waitFor(OFFER).doClick()
        wizard.panel.waitFor(IN_STEP).doClick()
        wizard.panel.waitFor(ENVIRONMENT).doClick()
        wizard.base.name = "extra"
        wizard.panel.apply()

        val lines = AppExecutorUtil.getAppExecutorService().submit(Callable {
            val commandLine = wizard.step.mixNewCommandLine(project, wizard.step.sdk!!).apply {
                parametersList.clearAll()
                if (OS.CURRENT == OS.Windows) {
                    exePath = "cmd.exe"
                    addParameters("/c", "set")
                } else {
                    exePath = "env"
                }
            }
            val process = commandLine.createProcess()
            process.inputStream.bufferedReader().readText().also { process.waitFor(30, TimeUnit.SECONDS) }
        }).get(60, TimeUnit.SECONDS).lines().map { it.trim() }

        assertTrue(lines.joinToString("\n"), TOOL_MANAGER_PROBE_LINE in lines)
        assertEquals(listOf(Path.of(wizard.location)), askedAbout)
        assertFalse("adopted before the module is made", ToolManagerSettings.getInstance(project).isEnabled(toolManager))
    }

    /** The wizard's name and location step, then this plugin's, on one panel, for File > New > Module unless [context] is given. */
    private inner class Wizard(
        private val context: WizardContext = WizardContext(project, Disposer.newDisposable(testRootDisposable))
            .apply { modulesProvider = DefaultModulesProvider.createForProject(project) },
    ) {
        val base = NewProjectWizardBaseStep(RootNewProjectWizardStep(context))
        val step = Step(base)
        val panel: DialogPanel = panel {
            base.setupUI(this)
            step.setupUI(this)
        }.apply { registerValidators(context.disposable) }

        /** A new folder, set as the location before any pick: a changed location takes the offer away. */
        val location: String = FileUtil.createTempDirectory("newModule", null).path.also { base.path = it }

        /**
         * Takes the step's answers for a module "extra" at [location] with [sdk], and makes it there as `mix new` and
         * the wizard do.
         */
        fun commit(sdk: Sdk = step.sdk!!): Module {
            base.name = "extra"
            panel.apply()
            Files.createDirectories(Path.of(location, "extra"))
            return step.commitModule(project, sdk).single()
        }
    }
}

private const val SETTINGS_PAGE = "Settings > Languages & Frameworks > Elixir > Tool Managers"
