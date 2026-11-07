package org.elixir_lang.new_project_wizard

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
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
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkDetectionContext
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.choice.ENVIRONMENT
import org.elixir_lang.sdk.choice.FakeToolManager
import org.elixir_lang.sdk.choice.IN_STEP
import org.elixir_lang.sdk.choice.OFFER
import org.elixir_lang.sdk.choice.waitFor
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.tool_manager.ToolManagerSettings
import org.elixir_lang.tool_manager.elixirSdksAt
import java.nio.file.Files
import java.nio.file.Path

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
            step.moduleBuilder(sdk).commit(project)
            return checkNotNull(ModuleManager.getInstance(project).findModuleByName("extra")) { "no module 'extra'" }
        }
    }
}

private const val SETTINGS_PAGE = "Settings > Languages & Frameworks > Elixir > Tool Managers"
