package org.elixir_lang.mix.project._import.step

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ui.configuration.JdkComboBox
import com.intellij.openapi.roots.ui.configuration.SdkListItem
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.registerOrReplaceServiceInstance
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkDetectionContext
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.elixir.Type
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.ScriptedErlangStep
import org.elixir_lang.sdk.erlang_dependent.StepAnswer
import org.elixir_lang.sdk.erlang_dependent.elixirAdditionalData
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.sdk.wsl.wslCompat
import org.elixir_lang.sdk.wsl.LEGACY_WSL_PREFIX
import org.elixir_lang.sdk.wsl.MODERN_WSL_PREFIX
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import java.nio.file.Path

/**
 * The import's SDK step: its pick, and the SDKs of the import target's environment it adds to its model.
 * [MockWslCompatService] pins the prefix conversion, legacy to modern, so the rows don't depend on the host's Windows
 * version.
 */
@Suppress("UnstableApiUsage")
class ElixirSdkForModuleStepTest : PlatformTestCase() {
    private val asked = Asked()
    private lateinit var table: SdkFixtures.TableSnapshot

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        asked.recordMessages(testRootDisposable)
        installWslCompatService(MockWslCompatService())
    }

    override fun tearDown() = runAll(
        // updateStep() publishes the import directory application-wide; don't leak it
        { SdkDetectionContext.clear() },
        { table.restore() },
        { super.tearDown() },
    )

    fun testADetectedPickAsksAndAPickPairsAndSelectsIt() {
        val erlang = SdkFixtures.register(SdkFixtures.erlangSdk("Import picked Erlang", install("27", "27.3.4")))
        script(ScriptedErlangStep.pick(erlang.name))
        val step = step(localImportDirectory()).apply { updateStep() }
        val combo = step.preferredFocusedComponent as JdkComboBox

        combo.pickDetected(elixirInstall("1.18.4", "27"))

        assertEquals(listOf("erlang"), asked.events)
        val selected = combo.selectedJdk ?: throw AssertionError("the picked SDK is selected")
        assertEquals(erlang.name, selected.elixirAdditionalData?.getErlangSdkName())
        assertTrue("Next is enabled", step.validate())
    }

    fun testCancelClearsAPriorPick() {
        val erlang = SdkFixtures.register(SdkFixtures.erlangSdk("Import prior Erlang", install("27", "27.3.4")))
        script(ScriptedErlangStep.pick(erlang.name), ScriptedErlangStep.cancel)
        val step = step(localImportDirectory()).apply { updateStep() }
        val combo = step.preferredFocusedComponent as JdkComboBox
        combo.pickDetected(elixirInstall("1.18.4", "27"))
        val home = elixirInstall("1.17.3", "27")

        combo.pickDetected(home)

        assertNull("the combo is empty", combo.selectedJdk)
        assertFalse("Next is disabled", step.validate())
        assertTrue(
            "the cancelled SDK is out of the model, so no Apply commits it",
            step.projectSdksModel.sdks.none { wslCompat.pathsEqualWslAware(it.homePath, home) },
        )
    }

    private fun script(vararg answers: StepAnswer): ScriptedErlangStep =
        ScriptedErlangStep(asked, *answers).install(testRootDisposable)

    private fun installWslCompatService(service: WslCompatService) {
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            service,
            testRootDisposable,
        )
    }

    private fun step(importDirectory: String): ElixirSdkForModuleStep {
        val wizardContext = WizardContext(null, testRootDisposable)
        wizardContext.setProjectFileDirectory(importDirectory)

        return ElixirSdkForModuleStep(wizardContext)
    }

    private fun localImportDirectory(): String = FileUtil.createTempDirectory("mixImport", null).path

    private fun registerElixirSdk(name: String, homePath: String? = null): Sdk {
        val sdk = ProjectJdkImpl(name, Type.instance)

        WriteAction.run<Throwable> {
            if (homePath != null) {
                val modificator = sdk.sdkModificator
                modificator.homePath = homePath
                modificator.commitChanges()
            }
            ProjectJdkTable.getInstance().addJdk(sdk, testRootDisposable)
        }

        return sdk
    }

    fun testUpdateStepPopulatesLocalSdkWithoutModifyingModel() {
        val sdk = registerElixirSdk("Local Elixir", homePath = localImportDirectory())
        val moduleStep = step(localImportDirectory())

        moduleStep.updateStep()

        val projectSdksModel = moduleStep.projectSdksModel
        assertTrue(
            "reset() should pick up a local SDK for a local import target",
            projectSdksModel.projectSdks.containsKey(sdk)
        )
        assertFalse(
            "The import-target sync loop must not mark the model modified for a local-only import",
            projectSdksModel.isModified
        )

        val sdkCountAfterFirstUpdate = projectSdksModel.projectSdks.size
        moduleStep.updateStep()

        assertEquals(
            "Repeated updateStep() must not duplicate SDKs in the model",
            sdkCountAfterFirstUpdate,
            projectSdksModel.projectSdks.size
        )
    }

    fun testHomelessSdkDoesNotTriggerTheSyncLoop() {
        // A never-configured ProjectJdkImpl reports "" as its home path, which the platform's
        // reset() treats as local and includes in the model on its own. The sync loop must still
        // skip it (blank home -> unknown environment), observable as the model staying unmodified.
        registerElixirSdk("Homeless Elixir")
        val moduleStep = step(localImportDirectory())

        moduleStep.updateStep()

        assertFalse(
            "The sync loop must not re-add an SDK whose environment cannot be determined",
            moduleStep.projectSdksModel.isModified
        )
    }

    fun testUpdateStepMakesWslSdkAvailableWhenImportingFromSameDistroWithMixedPrefixes() {
        val sdk = registerElixirSdk("WSL Elixir", homePath = "${LEGACY_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\elixir")
        val moduleStep = step("${MODERN_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\project")

        moduleStep.updateStep()

        assertTrue(
            "A WSL SDK must be in the model when importing from the same distro via the other prefix",
            moduleStep.projectSdksModel.projectSdks.containsKey(sdk)
        )
        assertTrue(
            "A WSL SDK must be visible when importing from the same distro via the other prefix",
            moduleStep.sdkVisibleForImportTarget(sdk)
        )
    }

    fun testUpdateStepPublishesImportDirectoryAsSdkDetectionContext() {
        val importDirectory = localImportDirectory()
        val moduleStep = step(importDirectory)

        moduleStep.updateStep()

        assertEquals(
            "updateStep() should publish the import directory for SDK detection and home selection",
            Path.of(importDirectory),
            SdkDetectionContext.resolve()
        )

        moduleStep.disposeUIResources()

        assertNull(
            "disposeUIResources() should clear the published import directory",
            SdkDetectionContext.resolve()
        )
    }

    fun testUpdateStepDoesNotDuplicateWslSdkOnRepeatedCalls() {
        registerElixirSdk("WSL Elixir", homePath = "${LEGACY_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\elixir")
        val moduleStep = step("${MODERN_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\project")

        moduleStep.updateStep()
        val sdkCountAfterFirstUpdate = moduleStep.projectSdksModel.projectSdks.size

        moduleStep.updateStep()

        assertEquals(
            "Repeated updateStep() must not duplicate re-added SDKs in the model",
            sdkCountAfterFirstUpdate,
            moduleStep.projectSdksModel.projectSdks.size
        )
    }
}

/** A home the combo detected, picked as the user picks it: the platform sets it up, then calls the combo's callback. */
private fun JdkComboBox.pickDetected(home: String) {
    // SuggestedItem's constructor is package-private; detection itself needs a dialog parent.
    val item = SdkListItem.SuggestedItem::class.java
        .getDeclaredConstructor(SdkType::class.java, String::class.java, String::class.java)
        .apply { isAccessible = true }
        .newInstance(Type.instance, "1.18.4", home)
    setSelectedItem(item)
}
