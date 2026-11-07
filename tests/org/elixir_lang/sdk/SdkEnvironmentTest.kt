package org.elixir_lang.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.registerOrReplaceServiceInstance
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.wsl.LEGACY_WSL_PREFIX
import org.elixir_lang.sdk.wsl.MODERN_WSL_PREFIX
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import org.mockito.Mockito
import java.nio.file.Path

/**
 * Which environment a path or an SDK's home belongs to, and which SDKs a target environment lists.
 *
 * A real WSL environment cannot be required in platform tests. On a machine without WSL, a `\\wsl$\<distro>` path and
 * a `\\wsl.localhost\<distro>` path both resolve to the local descriptor; with WSL, both resolve to the distro's. So an
 * assertion that the two are equal holds either way. [MockWslCompatService] pins the prefix conversion, legacy to
 * modern unless a row says otherwise, so the rows don't depend on the host's Windows version.
 */
@Suppress("UnstableApiUsage")
class SdkEnvironmentTest : PlatformTestCase() {
    private lateinit var table: SdkFixtures.TableSnapshot

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        installWslCompatService(MockWslCompatService())
    }

    override fun tearDown() = runAll(
        { table.restore() },
        { super.tearDown() },
    )

    fun testALocalDirectoryIsInTheLocalEnvironment() {
        assertEquals(Path.of(FileUtil.getTempDirectory()).getEelDescriptor(), SdkEnvironment.eelDescriptor(localDirectory()))
    }

    fun testAnUnparseablePathHasNoEnvironment() {
        // Path.of() rejects a NUL on every platform.
        assertNull(SdkEnvironment.eelDescriptor("\u0000invalid"))
    }

    /** WSL descriptor equality includes the UNC root, so without the normalisation the two prefixes would differ. */
    fun testBothWslPrefixesIntoOneDistroAreOneEnvironment() {
        assertEquals(
            SdkEnvironment.eelDescriptor("${LEGACY_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\project"),
            SdkEnvironment.eelDescriptor("${MODERN_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\project"),
        )
    }

    fun testBothWslPrefixesIntoOneDistroAreOneEnvironmentWhenModernConvertsToLegacy() {
        installWslCompatService(MockWslCompatService(prefixConversionOverride = MODERN_WSL_PREFIX to LEGACY_WSL_PREFIX))

        assertEquals(
            SdkEnvironment.eelDescriptor("${LEGACY_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\project"),
            SdkEnvironment.eelDescriptor("${MODERN_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\project"),
        )
    }

    fun testAnSdkWithNoHomeHasNoEnvironment() {
        assertNull(SdkEnvironment.sdkDescriptor(registerElixirSdk("Homeless Elixir")))
    }

    fun testAnSdkWithAnUnparseableHomeHasNoEnvironment() {
        // Mocked: ProjectJdkTable.addJdk() parses the home itself and rejects the NUL first.
        val sdk = Mockito.mock(Sdk::class.java)
        Mockito.`when`(sdk.homePath).thenReturn("\u0000invalid")

        assertNull(SdkEnvironment.sdkDescriptor(sdk))
    }

    fun testAnSdkWithALocalHomeIsInALocalDirectorysEnvironment() {
        val sdk = registerElixirSdk("Local Elixir", homePath = localDirectory())

        assertEquals(SdkEnvironment.eelDescriptor(localDirectory()), SdkEnvironment.sdkDescriptor(sdk))
    }

    fun testAnSdkWhoseEnvironmentIsUnknownIsVisible() {
        val sdk = registerElixirSdk("Homeless Elixir")

        assertTrue(SdkEnvironment.visibleFor(SdkEnvironment.eelDescriptor(localDirectory()))(sdk))
    }

    fun testALocalSdkIsVisibleForALocalTarget() {
        val sdk = registerElixirSdk("Local Elixir", homePath = localDirectory())

        assertTrue(SdkEnvironment.visibleFor(SdkEnvironment.eelDescriptor(localDirectory()))(sdk))
    }

    fun testAWslSdkIsVisibleForATargetInItsDistroThroughTheOtherPrefix() {
        val sdk = registerElixirSdk("WSL Elixir", homePath = "${LEGACY_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\elixir")
        val target = SdkEnvironment.eelDescriptor("${MODERN_WSL_PREFIX}IntellijElixirWSLDistribution\\home\\user\\project")

        assertTrue(SdkEnvironment.visibleFor(target)(sdk))
    }

    private fun installWslCompatService(service: WslCompatService) {
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(WslCompatService::class.java, service, testRootDisposable)
    }

    private fun localDirectory(): String = FileUtil.createTempDirectory("sdkEnvironment", null).path

    private fun registerElixirSdk(name: String, homePath: String = ""): Sdk =
        SdkFixtures.register(SdkFixtures.elixirSdk(name, homePath), testRootDisposable)
}
