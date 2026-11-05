package org.elixir_lang.new_project_wizard

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.roots.ui.configuration.JdkComboBox
import com.intellij.openapi.roots.ui.configuration.SdkComboBoxBase
import com.intellij.openapi.roots.ui.configuration.SdkListItem
import com.intellij.openapi.roots.ui.configuration.SdkListModelBuilder
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.common.runAll
import com.intellij.ui.dsl.builder.panel
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkDetectionContext
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.ScriptedErlangStep
import org.elixir_lang.sdk.erlang_dependent.StepAnswer
import org.elixir_lang.sdk.erlang_dependent.elixirAdditionalData
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import java.nio.file.Path
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType

/**
 * Tests the SDK-environment wiring of [elixirSdkComboBox]: the New Project wizard has no real
 * Project, so the combo must publish the Location field as the SDK detection context and keep it
 * current as the user edits it (WSL-specific behaviour itself is covered by SdkEnvironment usage
 * in ElixirSdkForModuleStepTest). A detected Elixir home the user picks asks for its Erlang SDK.
 */
class ElixirSdkComboBoxTest : PlatformTestCase() {
    private val asked = Asked()
    private lateinit var table: SdkFixtures.TableSnapshot
    private val registered = mutableListOf<Sdk>()

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        asked.recordMessages(testRootDisposable)
    }

    override fun tearDown() = runAll(
        { SdkDetectionContext.clear() },
        { table.restore() },
        { super.tearDown() },
    )

    fun testLocationDrivesSdkDetectionContextThroughWizardLifecycle() {
        val wizardDisposable = Disposer.newDisposable(testRootDisposable)
        val wizardContext = WizardContext(null, wizardDisposable)
        val propertyGraph = PropertyGraph()
        val locationProperty = propertyGraph.property("")
        val sdkProperty = propertyGraph.property<Sdk?>(null)

        panel {
            row {
                elixirSdkComboBox(wizardContext, sdkProperty, locationProperty)
            }
        }

        assertNull(
            "A blank location should publish no detection context",
            SdkDetectionContext.resolve()
        )

        val firstDirectory = FileUtil.createTempDirectory("newProject", null).path
        locationProperty.set(firstDirectory)

        assertEquals(
            "Setting the location should publish it for SDK detection",
            Path.of(firstDirectory),
            SdkDetectionContext.resolve()
        )

        val secondDirectory = FileUtil.createTempDirectory("newProject", null).path
        locationProperty.set(secondDirectory)

        assertEquals(
            "Editing the location should republish it",
            Path.of(secondDirectory),
            SdkDetectionContext.resolve()
        )

        Disposer.dispose(wizardDisposable)

        assertNull(
            "Disposing the wizard should clear the published location",
            SdkDetectionContext.resolve()
        )
    }

    fun testADetectedPickAsksTheErlangStep() {
        SdkFixtures.register(SdkFixtures.erlangSdk("Combo registered Erlang", install("27", "27.3.4")))
        script(ScriptedErlangStep.cancel)

        wizardCombo().pickDetected(elixirInstall("1.18.4", "27"))

        assertEquals("the registered Erlang SDK is offered, not paired silently", listOf("erlang"), asked.events)
    }

    fun testTheAskListsAnErlangPendingInTheModel() {
        val combo = wizardCombo()
        val pending = SdkFixtures.erlangSdk("Combo pending Erlang", install("27", "27.3.4"))
        combo.sdksModel.addSdk(pending)
        val step = script(ScriptedErlangStep.cancel)

        combo.pickDetected(elixirInstall("1.18.4", "27"))

        assertContainsElements(step.offered.single().mapNotNull { it.sdk?.name }, pending.name)
    }

    fun testAPickPairsIt() {
        val combo = wizardCombo()
        val pending = SdkFixtures.erlangSdk("Combo picked Erlang", install("27", "27.3.4"))
        combo.sdksModel.addSdk(pending)
        script(ScriptedErlangStep.pick(pending.name))

        combo.pickDetected(elixirInstall("1.18.4", "27"))

        val selected = combo.selectedJdk ?: throw AssertionError("the picked SDK is selected")
        assertEquals(pending.name, selected.elixirAdditionalData?.getErlangSdkName())
        assertEquals("the combo hands the wizard the pick for setupProject", listOf(pending.name), registered.map { it.name })
    }

    fun testCancelLeavesNoSdk() {
        val combo = wizardCombo()
        script(ScriptedErlangStep.cancel)

        combo.pickDetected(elixirInstall("1.18.4", "27"))

        assertNull(combo.selectedJdk)
        assertTrue(combo.sdksModel.sdks.none { it.sdkType is ElixirSdkType })
    }

    private fun script(vararg answers: StepAnswer): ScriptedErlangStep =
        ScriptedErlangStep(asked, *answers).install(testRootDisposable)

    /** The New Project wizard's Elixir SDK combo, its Erlang SDKs for setupProject recorded in [registered]. */
    private fun wizardCombo(): JdkComboBox {
        val wizardContext = WizardContext(null, Disposer.newDisposable(testRootDisposable))
        val sdkProperty = PropertyGraph().property<Sdk?>(null)
        lateinit var combo: JdkComboBox
        panel { row { combo = elixirSdkComboBox(wizardContext, sdkProperty) { registered += it }.component } }
        return combo
    }
}

/** A home the combo detected, picked as the user picks it: the platform sets it up, then calls the combo's callback. */
internal fun JdkComboBox.pickDetected(home: String) {
    // SuggestedItem's constructor is package-private; detection itself needs a dialog parent.
    val item = SdkListItem.SuggestedItem::class.java
        .getDeclaredConstructor(SdkType::class.java, String::class.java, String::class.java)
        .apply { isAccessible = true }
        .newInstance(ElixirSdkType.instance, "1.18.4", home)
    setSelectedItem(item)
}

/** The model the combo was built on, which it does not expose. */
internal val JdkComboBox.sdksModel: ProjectSdksModel
    get() {
        val builder = SdkComboBoxBase::class.java.getDeclaredField("myModel").apply { isAccessible = true }.get(this)
        return SdkListModelBuilder::class.java.getDeclaredField("mySdkModel").apply { isAccessible = true }.get(builder) as ProjectSdksModel
    }
