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
import com.intellij.platform.eel.provider.getEelDescriptor
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
import org.mockito.Mockito
import java.nio.file.Path

/**
 * Tests for the EEL-aware SDK filtering in [ElixirSdkForModuleStep].
 *
 * A real WSL/remote environment cannot be required in platform tests, so coverage is built from
 * three kinds of assertions:
 * - locally observable behaviour: a local import target resolves to the local descriptor,
 *   visibility is permissive when an environment cannot be determined, and the post-reset sync
 *   loop ([ElixirSdkForModuleStep.addImportTargetSdks] via updateStep()) never adds spurious SDKs
 *   or marks the model modified for a local-only import;
 * - plumbing: paths are routed through WslCompatService's prefix normalization before descriptor
 *   resolution (via a recording service);
 * - environment-independent invariants: after normalization, `\\wsl$\<distro>` and
 *   `\\wsl.localhost\<distro>` paths resolve to EQUAL descriptors. On machines without WSL both
 *   resolve to the local descriptor; on machines with WSL both resolve to the same distro
 *   descriptor -- the assertions hold either way, while exercising the real re-add path when WSL
 *   is present.
 *
 * [MockWslCompatService] pins the prefix-conversion policy (legacy -> modern by default) so tests
 * are deterministic across the CI matrix (Windows 11, older Windows, Linux) instead of depending
 * on the host OS version.
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

    // eelDescriptor(path) -- descriptor resolution and normalization plumbing

    fun testImportTargetDescriptorForLocalDirectoryIsLocalDescriptor() {
        val targetDescriptor = step(localImportDirectory()).importTargetDescriptor()

        assertNotNull("A local import directory should resolve to a descriptor", targetDescriptor)
        assertEquals(
            "A local import directory should resolve to the same descriptor as any other local path",
            Path.of(FileUtil.getTempDirectory()).getEelDescriptor(),
            targetDescriptor
        )
    }

    fun testEelDescriptorIsNullForUnparseablePath() {
        // A NUL character is rejected by Path.of() on every platform
        assertNull(
            "An unparseable path has no determinable descriptor",
            step(localImportDirectory()).eelDescriptor("\u0000invalid")
        )
    }

    fun testEelDescriptorNormalizesPathsThroughWslCompatService() {
        val recordedPaths = mutableListOf<String>()
        installWslCompatService(object : WslCompatService by MockWslCompatService() {
            override fun String.canonicalizeWslPrefix(): String {
                recordedPaths += this
                return this
            }
        })

        val importDirectory = localImportDirectory()
        val sdkHome = localImportDirectory()
        val sdk = registerElixirSdk("Local Elixir", homePath = sdkHome)
        val moduleStep = step(importDirectory)

        moduleStep.importTargetDescriptor()
        moduleStep.sdkDescriptor(sdk)

        // Slash direction varies by source (SdkModificator stores system-independent paths,
        // FileUtil.createTempDirectory returns system-dependent ones), so compare normalized
        assertContainsElements(
            recordedPaths.map(FileUtil::toSystemIndependentName),
            FileUtil.toSystemIndependentName(importDirectory),
            FileUtil.toSystemIndependentName(sdkHome),
        )
    }

    /**
     * The regression test for the mixed-prefix hole: WSL descriptor equality includes the UNC
     * root, so without normalization a `\\wsl$\<distro>` SDK home and a `\\wsl.localhost\<distro>`
     * import directory would resolve to unequal descriptors on a machine with WSL. After
     * normalization both resolve to the same descriptor -- on machines without WSL both are the
     * local descriptor, so the assertion is environment-independent.
     */
    fun testMixedWslPrefixesResolveToEqualDescriptors() {
        val moduleStep = step(localImportDirectory())

        assertEquals(
            "Legacy- and modern-prefix paths into the same distro must resolve to equal descriptors",
            moduleStep.eelDescriptor("${LEGACY_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\project"),
            moduleStep.eelDescriptor("${MODERN_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\project")
        )
    }

    fun testMixedWslPrefixesResolveToEqualDescriptorsUnderLegacyConversionPolicy() {
        // Older Windows normalizes modern -> legacy instead; the equality invariant must still hold
        installWslCompatService(MockWslCompatService(prefixConversionOverride = MODERN_WSL_PREFIX to LEGACY_WSL_PREFIX))

        val moduleStep = step(localImportDirectory())

        assertEquals(
            "Descriptor equality must be prefix-insensitive under the modern -> legacy policy too",
            moduleStep.eelDescriptor("${LEGACY_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\project"),
            moduleStep.eelDescriptor("${MODERN_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\project")
        )
    }

    // sdkDescriptor(sdk)

    fun testSdkDescriptorIsNullWithoutHomePath() {
        val sdk = registerElixirSdk("Homeless Elixir")

        assertNull(
            "An SDK without a home path has no determinable descriptor",
            step(localImportDirectory()).sdkDescriptor(sdk)
        )
    }

    fun testSdkDescriptorIsNullForUnparseableHomePath() {
        // A NUL character is rejected by Path.of() on every platform. The SDK is mocked rather
        // than registered because ProjectJdkTable.addJdk() itself parses the home path and
        // rejects the NUL before sdkDescriptor() could ever see it.
        val sdk = Mockito.mock(Sdk::class.java)
        Mockito.`when`(sdk.homePath).thenReturn("\u0000invalid")

        assertNull(
            "An SDK with an unparseable home path has no determinable descriptor",
            step(localImportDirectory()).sdkDescriptor(sdk)
        )
    }

    fun testSdkDescriptorForLocalHomeMatchesLocalImportTarget() {
        val sdk = registerElixirSdk("Local Elixir", homePath = localImportDirectory())
        val moduleStep = step(localImportDirectory())

        assertEquals(
            "An SDK with a local home should be in the same environment as a local import target",
            moduleStep.importTargetDescriptor(),
            moduleStep.sdkDescriptor(sdk)
        )
    }

    // sdkVisibleForImportTarget(sdk)

    fun testSdkWithUnknownDescriptorIsVisible() {
        val sdk = registerElixirSdk("Homeless Elixir")

        assertTrue(
            "Visibility should be permissive when the SDK's environment cannot be determined",
            step(localImportDirectory()).sdkVisibleForImportTarget(sdk)
        )
    }

    fun testLocalSdkIsVisibleForLocalImportTarget() {
        val sdk = registerElixirSdk("Local Elixir", homePath = localImportDirectory())

        assertTrue(
            "A local SDK should be visible for a local import target",
            step(localImportDirectory()).sdkVisibleForImportTarget(sdk)
        )
    }

    fun testWslSdkIsVisibleForImportTargetInSameDistroWithMixedPrefixes() {
        val sdk = registerElixirSdk("WSL Elixir", homePath = "${LEGACY_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\elixir")

        // Environment-independent: without WSL both sides are the local descriptor; with WSL both
        // normalize to the same distro descriptor. Either way the SDK must be visible.
        assertTrue(
            "A WSL SDK should be visible when importing from the same distro via the other prefix",
            step("${MODERN_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\project").sdkVisibleForImportTarget(sdk)
        )
    }

    // updateStep() -- reset + addImportTargetSdks() end to end

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

        // Environment-independent end state: on machines without WSL reset() already kept the SDK
        // (its home is owned by the local environment there); on machines with WSL reset() filters
        // it out and addImportTargetSdks() re-adds it because the normalized descriptors match.
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
