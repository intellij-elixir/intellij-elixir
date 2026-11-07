package org.elixir_lang.module

import com.intellij.ide.util.projectWizard.SettingsStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModifiableModuleModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ui.configuration.DefaultModulesProvider
import com.intellij.openapi.roots.ui.configuration.ModulesProvider
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.common.runAll
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.choice.ENVIRONMENT
import org.elixir_lang.sdk.choice.FakeToolManager
import org.elixir_lang.sdk.choice.IN_STEP
import org.elixir_lang.sdk.choice.OFFER
import org.elixir_lang.sdk.choice.SdkChoiceStep
import org.elixir_lang.sdk.choice.pairedForNext
import org.elixir_lang.sdk.choice.pickAndNext
import org.elixir_lang.sdk.choice.takeTheOfferInStep
import org.elixir_lang.sdk.choice.waitFor
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.tool_manager.ToolManagerSettings
import org.elixir_lang.tool_manager.elixirSdksAt
import javax.swing.JComponent

/** The Elixir module type's wizard step and settings step, as File > New and Project Structure's New Module build them. */
class ElixirModuleTypeStepTest : HeavyTestCase() {
    private lateinit var table: SdkFixtures.TableSnapshot
    private val toolManager = FakeToolManager()
    private val builder = ElixirModuleBuilder()

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        toolManager.install(testRootDisposable)
    }

    override fun tearDown() = runAll(
        { table.restore() },
        { super.tearDown() },
    )

    fun testTheStepSetsTheBuildersElixirSdk() {
        val elixir = pairedForNext("Module Elixir", "Module Erlang", testRootDisposable)
        val step = step(project, DefaultModulesProvider.createForProject(project))

        step.pickAndNext(elixir.name)

        assertEquals(elixir.name, builder.elixirSdk?.name)
    }

    fun testUnderProjectStructuresModulesTheBoxesAreDisabledAndNoOptInsReachTheBuilder() {
        toolManager.pins(elixirInstall("1.18.4", "27"), install("27", "27.3.4"))
        val step = step(project, ModulesProvider.EMPTY_MODULES_PROVIDER)
        step.updateStep()

        step.component.waitFor(OFFER).doClick()
        assertEquals(listOf(false, false), listOf(step.component.waitFor(IN_STEP), step.component.waitFor(ENVIRONMENT)).map { it.isEnabled })
        step.component.waitFor(IN_STEP).doClick()
        step.validate()
        step.updateDataModel()

        assertNotNull(builder.elixirSdk)
        assertNull(builder.toolManagerOptIns)
    }

    fun testUnderTheProjectsOwnModulesTheTickedOptInsReachTheBuilder() {
        toolManager.pins(elixirInstall("1.18.4", "27"), install("27", "27.3.4"))
        val step = step(project, DefaultModulesProvider.createForProject(project))

        step.takeTheOfferInStep()

        assertEquals(toolManager.name, builder.toolManagerOptIns?.manager)
    }

    fun testInANewProjectTheTickedOptInsAreWrittenAtCommit() {
        val elixirHome = elixirInstall("1.18.4", "27")
        toolManager.pins(elixirHome, install("27", "27.3.4"))
        step(null, ModulesProvider.EMPTY_MODULES_PROVIDER).takeTheOfferInStep()

        val module = commit()

        assertEquals(elixirSdksAt(elixirHome), listOfNotNull(ModuleRootManager.getInstance(module).sdk))
        assertTrue(ToolManagerSettings.getInstance(project).isEnabled(toolManager))
    }

    fun testInProjectStructuresModelThePickIsTheModuleSdkOnceThatModelCommits() {
        val elixir = pairedForNext("Module Elixir", "Module Erlang", testRootDisposable)
        builder.elixirSdk = elixir
        val model = ModuleManager.getInstance(project).getModifiableModel()

        val module = commit(model)
        WriteAction.run<Throwable> { model.commit() }

        assertSame(elixir, ModuleRootManager.getInstance(module).sdk)
    }

    fun testTheSettingsStepAsksForNoSdkAndAddsNoSourceFolder() {
        val added = mutableListOf<JComponent>()
        val settingsStep = object : SettingsStep {
            override fun getContext() = WizardContext(project, Disposer.newDisposable(testRootDisposable))
            override fun addSettingsField(label: String, field: JComponent) { added += field }
            override fun addSettingsComponent(component: JComponent) { added += component }
            override fun addExpertPanel(panel: JComponent) { added += panel }
            override fun addExpertField(label: String, field: JComponent) { added += field }
        }

        builder.modifySettingsStep(settingsStep).updateDataModel()

        assertEquals(emptyList<JComponent>(), added)
        assertEquals(emptyList<Any>(), builder.sourcePaths)
    }

    /** The module type's one wizard step, in a wizard for [project] under [modulesProvider], over a new directory. */
    private fun step(project: Project?, modulesProvider: ModulesProvider): SdkChoiceStep {
        val context = WizardContext(project, Disposer.newDisposable(testRootDisposable))
        context.setProjectFileDirectory(FileUtil.createTempDirectory("moduleType", null).path)
        context.modulesProvider = modulesProvider
        return ElixirModuleType.getInstance().createWizardSteps(context, builder, modulesProvider).single() as SdkChoiceStep
    }

    /** Commits the builder's module into [model], or into the project's own when null. */
    private fun commit(model: ModifiableModuleModel? = null): Module {
        val directory = FileUtil.createTempDirectory("built", null).path
        builder.name = "built"
        builder.moduleFilePath = "$directory/built.iml"
        builder.contentEntryPath = directory
        return builder.commit(project, model, DefaultModulesProvider.createForProject(project))!!.single()
    }
}
