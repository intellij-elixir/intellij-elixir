package org.elixir_lang.new_project_wizard

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.module.ModuleManager
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
import java.nio.file.Files
import java.nio.file.Path

/** File > New > Module with the Elixir generator, in this test's project, as the wizard builds its steps. */
class NewModuleTest : HeavyTestCase() {
    private lateinit var table: SdkFixtures.TableSnapshot

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        // The row asks the tool managers about its location, and a real mise there answers from this machine's config.
        FakeToolManager().install(testRootDisposable)
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
        val location = FileUtil.createTempDirectory("newModule", null).path
        val wizard = Wizard()
        wizard.base.path = location
        wizard.base.name = "extra"
        wizard.panel.apply()
        // What `mix new` makes.
        Files.createDirectories(Path.of(location, "extra"))

        wizard.step.moduleBuilder(sdk).commit(project)

        assertNotNull("no module 'extra'", ModuleManager.getInstance(project).findModuleByName("extra"))
        PlatformTestUtil.saveProject(project, true)
        assertTrue("no module file at <location>/extra/extra.iml", Files.isRegularFile(Path.of(location, "extra", "extra.iml")))
    }

    /** The New Module wizard's name and location step, then this plugin's, on one panel. */
    private inner class Wizard {
        private val context = WizardContext(project, Disposer.newDisposable(testRootDisposable))
        val base = NewProjectWizardBaseStep(RootNewProjectWizardStep(context))
        val step = Step(base)
        val panel: DialogPanel = panel {
            base.setupUI(this)
            step.setupUI(this)
        }.apply { registerValidators(context.disposable) }
    }
}
