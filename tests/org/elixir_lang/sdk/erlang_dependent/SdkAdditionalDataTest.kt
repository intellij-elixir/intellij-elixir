package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
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

    /** The check asks the resolver, which reads the pairing off the SDK. */
    private fun elixirHolding(data: (Sdk) -> SdkAdditionalData): Sdk =
        SdkFixtures.elixirSdk("Elixir", "/fake/elixir").also { SdkFixtures.commit(it, data(it)) }

    private fun refusal(elixir: Sdk): String? =
        try {
            elixir.elixirAdditionalData!!.checkValid(ProjectSdksModel())
            null
        } catch (e: ConfigurationException) {
            e.message
        }

    // Helper method to create a lightweight SDK instance
    private fun createMockSdk(name: String, homePath: String? = null): Sdk {
        return ProjectJdkImpl(name, ErlangSdkType.instance, homePath ?: "", "")
    }
}
