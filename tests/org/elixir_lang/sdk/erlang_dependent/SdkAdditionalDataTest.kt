package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType
import org.jdom.Element

/**
 * Unit tests for SdkAdditionalData.
 *
 * These tests verify the core persistence and caching logic.
 * Integration tests with actual SDK creation/deletion should be done manually
 * or in a full IDE test environment.
 */
class SdkAdditionalDataTest: PlatformTestCase() {

    /**
     * Verify that creating SdkAdditionalData with an Erlang SDK sets both name and home path.
     */
    fun testNewSdkCreation_SetsNameAndHomePath() {
        val elixirSdk = createMockSdk("Elixir 1.15.0", "/fake/elixir/1.15")
        val erlangSdk = createMockSdk("Erlang 26.0", "/fake/erlang/26.0")

        val additionalData = SdkAdditionalData(erlangSdk, elixirSdk)

        assertEquals("Erlang 26.0", additionalData.getErlangSdkName())
        assertEquals("/fake/erlang/26.0", additionalData.getErlangSdkHomePath())
    }

    /**
     * Verify that setErlangSdk() updates both the name, home path, and cache atomically.
     */
    fun testSetErlangSdk_UpdatesNameHomePathAndCache() {
        val elixirSdk = createMockSdk("Elixir 1.15.0", "/fake/elixir/1.15")
        val erlangSdk1 = createMockSdk("Erlang 25.0", "/fake/erlang/25.0")
        val erlangSdk2 = createMockSdk("Erlang 26.0", "/fake/erlang/26.0")

        val additionalData = SdkAdditionalData(erlangSdk1, elixirSdk)
        assertEquals("Erlang 25.0", additionalData.getErlangSdkName())
        assertEquals("/fake/erlang/25.0", additionalData.getErlangSdkHomePath())

        // Update to a different Erlang SDK
        additionalData.setErlangSdk(erlangSdk2)

        assertEquals("Erlang 26.0", additionalData.getErlangSdkName())
        assertEquals("/fake/erlang/26.0", additionalData.getErlangSdkHomePath())
    }

    /**
     * Verify that setting null clears name, home path, and cache.
     */
    fun testSetErlangSdk_WithNull_ClearsAll() {
        val elixirSdk = createMockSdk("Elixir 1.15.0", "/fake/elixir/1.15")
        val erlangSdk = createMockSdk("Erlang 26.0", "/fake/erlang/26.0")

        val additionalData = SdkAdditionalData(erlangSdk, elixirSdk)
        assertEquals("Erlang 26.0", additionalData.getErlangSdkName())
        assertEquals("/fake/erlang/26.0", additionalData.getErlangSdkHomePath())

        // Clear the Erlang SDK
        additionalData.setErlangSdk(null)

        assertNull(additionalData.getErlangSdkName())
        assertNull(additionalData.getErlangSdkHomePath())
    }

    /**
     * Verify that readExternal() loads both name and home path, and clears the cache.
     */
    fun testReadExternal_LoadsNameAndHomePathAndClearsCache() {
        val elixirSdk = createMockSdk("Elixir 1.15.0", "/fake/elixir/1.15")
        val additionalData = SdkAdditionalData(elixirSdk)

        val element = Element("additional")
        element.setAttribute("erlang-sdk-name", "Erlang 26.0")
        element.setAttribute("erlang-sdk-home-path", "/fake/erlang/26.0")

        additionalData.readExternal(element)

        assertEquals("Erlang 26.0", additionalData.getErlangSdkName())
        assertEquals("/fake/erlang/26.0", additionalData.getErlangSdkHomePath())
    }

    /**
     * Verify readExternal with legacy data (name only, no home path).
     */
    fun testReadExternal_LegacyNameOnly() {
        val elixirSdk = createMockSdk("Elixir 1.15.0", "/fake/elixir/1.15")
        val additionalData = SdkAdditionalData(elixirSdk)

        val element = Element("additional")
        element.setAttribute("erlang-sdk-name", "Erlang 26.0")
        // No erlang-sdk-home-path attribute - simulates legacy config

        additionalData.readExternal(element)

        assertEquals("Erlang 26.0", additionalData.getErlangSdkName())
        assertNull(additionalData.getErlangSdkHomePath())
    }

    /**
     * Verify that writeExternal() persists both name and home path.
     */
    fun testWriteExternal_PersistsNameAndHomePath() {
        val elixirSdk = createMockSdk("Elixir 1.15.0", "/fake/elixir/1.15")
        val erlangSdk = createMockSdk("Erlang 26.0", "/fake/erlang/26.0")

        val additionalData = SdkAdditionalData(erlangSdk, elixirSdk)

        val element = Element("additional")
        additionalData.writeExternal(element)

        assertEquals("Erlang 26.0", element.getAttributeValue("erlang-sdk-name"))
        assertEquals("/fake/erlang/26.0", element.getAttributeValue("erlang-sdk-home-path"))
    }

    /**
     * Verify that writeExternal() doesn't write anything if both name and home path are null.
     */
    fun testWriteExternal_WithNullNameAndPath_WritesNothing() {
        val elixirSdk = createMockSdk("Elixir 1.15.0", "/fake/elixir/1.15")
        val additionalData = SdkAdditionalData(elixirSdk)

        val element = Element("additional")
        additionalData.writeExternal(element)

        assertNull(element.getAttributeValue("erlang-sdk-name"))
        assertNull(element.getAttributeValue("erlang-sdk-home-path"))
    }

    /**
     * Verify that clone() creates a new instance with the same name and home path but no shared cache.
     */
    fun testClone_CreatesIndependentCopy() {
        val elixirSdk = createMockSdk("Elixir 1.15.0", "/fake/elixir/1.15")
        val erlangSdk = createMockSdk("Erlang 26.0", "/fake/erlang/26.0")

        val original = SdkAdditionalData(erlangSdk, elixirSdk)
        val cloned = original.clone()

        // Name and home path should be copied
        assertEquals(original.getErlangSdkName(), cloned.getErlangSdkName())
        assertEquals(original.getErlangSdkHomePath(), cloned.getErlangSdkHomePath())

        // Modifying the clone should not affect the original
        cloned.setErlangSdk(null)
        assertNull(cloned.getErlangSdkName())
        assertNull(cloned.getErlangSdkHomePath())
        assertEquals("Erlang 26.0", original.getErlangSdkName())
        assertEquals("/fake/erlang/26.0", original.getErlangSdkHomePath())
    }

    /**
     * Verify that getErlangSdkName() returns the stored name without triggering SDK lookup.
     */
    fun testGetErlangSdkName_ReturnsNameWithoutLookup() {
        val elixirSdk = createMockSdk("Elixir 1.15.0", "/fake/elixir/1.15")
        val additionalData = SdkAdditionalData(elixirSdk)

        // Set name directly via readExternal
        val element = Element("additional")
        element.setAttribute("erlang-sdk-name", "Erlang 26.0")
        element.setAttribute("erlang-sdk-home-path", "/fake/erlang/26.0")
        additionalData.readExternal(element)

        // getErlangSdkName should return the name immediately
        // (no lookup happens, no ProjectJdkTable access)
        assertEquals("Erlang 26.0", additionalData.getErlangSdkName())
        assertEquals("/fake/erlang/26.0", additionalData.getErlangSdkHomePath())
    }

    fun testSuppressOtpMismatchRoundTripsAndClones() {
        val elixirSdk = createMockSdk("Elixir", "/fake/elixir")
        val element = Element("additional")
        val original = SdkAdditionalData(elixirSdk).apply { setSuppressOtpMismatchWarning(true) }

        original.writeExternal(element)

        assertTrue(SdkAdditionalData(elixirSdk).apply { readExternal(element) }.isSuppressOtpMismatchWarning())
        assertTrue(original.clone().isSuppressOtpMismatchWarning())
    }

    fun testCheckValidWithNoErlangChosenSaysSo() {
        assertEquals(NO_ERLANG, refusal(elixirHolding { SdkAdditionalData(it) }))
    }

    fun testCheckValidSaysSoWithOtherErlangSdksPresent() {
        SdkFixtures.register(createMockSdk("Erlang present", "/fake/erlang/present"), testRootDisposable)

        assertEquals(NO_ERLANG, refusal(elixirHolding { SdkAdditionalData(it) }))
    }

    fun testCheckValidKeepsItsTextWhenTheChosenErlangIsGone() {
        val gone = createMockSdk("Erlang gone", "/fake/erlang/gone")

        assertEquals("No Erlang SDK found. Please configure an Erlang SDK first.", refusal(elixirHolding { SdkAdditionalData(gone, it) }))
    }

    fun testCheckValidRefusesAnErlangOnAnotherMachineNamingBoth() {
        val local = SdkFixtures.register(createMockSdk("Erlang local", "/fake/erlang/local"), testRootDisposable)
        val elixir = elixirHolding { SdkAdditionalData(local, it) }

        assertEquals(
            "Erlang SDK 'Erlang local' is on Local: Windows 11, not WSL: Ubuntu, where this Elixir SDK is. " +
                "An Erlang SDK on WSL: Ubuntu must be chosen.",
            refusal(elixir) {
                checkValid(
                    ProjectSdksModel(),
                    inEnvironment = { false },
                    machineOf = { if (it == elixir.homePath) "WSL: Ubuntu" else "Local: Windows 11" },
                )
            },
        )
    }

    fun testCheckValidTakesAnErlangOnTheSameMachine() {
        val local = SdkFixtures.register(createMockSdk("Erlang same machine", "/fake/erlang/same"), testRootDisposable)

        assertNull(
            refusal(elixirHolding { SdkAdditionalData(local, it) }) {
                checkValid(ProjectSdksModel(), inEnvironment = { true }, machineOf = { error("named only on a refusal") })
            },
        )
    }

    fun testCheckValidRefusesAPairedErlangMovedToAnotherMachineInTheDialog() {
        val local = SdkFixtures.register(createMockSdk("Erlang moved", "/fake/erlang/moved"), testRootDisposable)
        val elixir = elixirHolding { SdkAdditionalData(local, it) }

        assertEquals(movedAway("Erlang moved"), refusalAfterAMove(elixir, local))
    }

    fun testCheckValidRefusesAMoveToAnotherMachineWithNothingCached() {
        val local = SdkFixtures.register(createMockSdk("Erlang moved uncached", "/fake/erlang/moved-uncached"), testRootDisposable)
        val elixir = elixirHolding { SdkAdditionalData(local, it).apply { setCachedErlangSdk(null) } }

        assertEquals(movedAway("Erlang moved uncached"), refusalAfterAMove(elixir, local))
    }

    fun testCheckValidRefusesARenameAndMoveToAnotherMachineOfANameOnlyPairing() {
        val local = SdkFixtures.register(createMockSdk("Erlang name only", "/fake/erlang/name-only"), testRootDisposable)
        val elixir = elixirHolding { SdkFixtures.storedPairing(it, "Erlang name only") }

        assertEquals(movedAway("Erlang name only renamed"), refusalAfterAMove(elixir, local, renamed = "Erlang name only renamed"))
    }

    fun testCheckValidRefusesAMoveToAnotherMachineLeavingASecondErlangAtTheOldHome() {
        val local = SdkFixtures.register(createMockSdk("Erlang moved from shared", "/fake/erlang/shared"), testRootDisposable)
        SdkFixtures.register(createMockSdk("Erlang left at shared", "/fake/erlang/shared"), testRootDisposable)
        val elixir = elixirHolding { SdkAdditionalData(local, it).apply { setCachedErlangSdk(null) } }

        assertEquals(movedAway("Erlang moved from shared"), refusalAfterAMove(elixir, local))
    }

    /** The message [elixir] is refused with once [erlang]'s copy in an open model moves to [ELSEWHERE], renamed [renamed]. */
    private fun refusalAfterAMove(elixir: Sdk, erlang: Sdk, renamed: String = erlang.name): String? {
        val model = ProjectSdksModel().apply { reset(project) }
        try {
            SdkFixtures.commit(model.projectSdks[erlang]!!) {
                name = renamed
                homePath = ELSEWHERE
            }
            return refusal(elixir) { checkAcrossMachines(model, ELSEWHERE) }
        } finally {
            model.disposeUIResources()
        }
    }

    /** The check asks the resolver, which reads the pairing off the SDK. */
    private fun elixirHolding(data: (Sdk) -> SdkAdditionalData): Sdk =
        SdkFixtures.elixirSdk("Elixir", "/fake/elixir").also { SdkFixtures.commit(it, data(it)) }

    /** The message [check] refuses [elixir] with; null when it takes it. */
    private fun refusal(elixir: Sdk, check: SdkAdditionalData.() -> Unit = { checkValid(ProjectSdksModel()) }): String? =
        try {
            elixir.elixirAdditionalData!!.check()
            null
        } catch (e: ConfigurationException) {
            e.message
        }

    // Helper method to create a lightweight SDK instance
    private fun createMockSdk(name: String, homePath: String? = null): Sdk {
        return ProjectJdkImpl(name, ErlangSdkType.instance, homePath ?: "", "")
    }

    private companion object {
        const val ELSEWHERE = "/fake/erlang/elsewhere"
    }
}

/** [SdkAdditionalData.checkValid] of [model], where homes at [elsewhere] are on another machine than the rest. */
internal fun SdkAdditionalData.checkAcrossMachines(model: ProjectSdksModel, elsewhere: String) =
    checkValid(model, inEnvironment = { it != elsewhere }, machineOf = { if (it == elsewhere) "WSL: Ubuntu" else "Local: Windows 11" })

/** What [checkAcrossMachines] refuses an Elixir SDK with when its Erlang SDK [erlangName] is the one elsewhere. */
internal fun movedAway(erlangName: String): String =
    "Erlang SDK '$erlangName' is on WSL: Ubuntu, not Local: Windows 11, where this Elixir SDK is. " +
        "An Erlang SDK on Local: Windows 11 must be chosen."
