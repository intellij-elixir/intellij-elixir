package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.registerOrReplaceServiceInstance
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.junit.onPooledThread
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import org.junit.Assume

class ErlangSdkResolverTest : PlatformTestCase() {
    private val resolver = DefaultErlangSdkResolver()

    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            MockWslCompatService(),
            testRootDisposable,
        )
    }

    fun testNotConfigured() {
        val elixirSdk = createElixirSdk { sdk -> SdkAdditionalData(sdk) }

        val result = resolver.resolveErlangSdkResult(elixirSdk, ListedSdkModel())

        assertTrue(result is ErlangSdkResult.Missing)
        val missing = result as ErlangSdkResult.Missing
        assertEquals(MissingErlangSdkReason.NOT_CONFIGURED, missing.reason)
    }

    fun testNotFound() {
        val configuredName = "Erlang SDK"
        val elixirSdk = createElixirSdk { sdk ->
            SdkFixtures.storedPairing(sdk, configuredName)
        }

        val result = resolver.resolveErlangSdkResult(elixirSdk, ListedSdkModel())

        assertTrue(result is ErlangSdkResult.Missing)
        val missing = result as ErlangSdkResult.Missing
        assertEquals(MissingErlangSdkReason.NOT_FOUND, missing.reason)
        assertEquals(configuredName, missing.erlangSdkName)
    }

    fun testInvalidType() {
        val configuredName = "Not Erlang"
        // An Elixir SDK is not a valid Erlang dependency.
        val invalidSdk = SdkFixtures.elixirSdk(configuredName, "/tmp/invalid")
        val elixirSdk = createElixirSdk { sdk ->
            SdkFixtures.storedPairing(sdk, configuredName)
        }

        val result = resolver.resolveErlangSdkResult(elixirSdk, ListedSdkModel(invalidSdk))

        assertTrue(result is ErlangSdkResult.Missing)
        val missing = result as ErlangSdkResult.Missing
        assertEquals(MissingErlangSdkReason.INVALID_TYPE, missing.reason)
        assertEquals(configuredName, missing.erlangSdkName)
    }

    fun testMissingHomePath() {
        val configuredName = "Erlang SDK"
        val erlangSdk = SdkFixtures.erlangSdk(configuredName, "")

        Assume.assumeTrue(Type.staticIsValidDependency(erlangSdk))

        val elixirSdk = createElixirSdk { sdk ->
            SdkFixtures.storedPairing(sdk, configuredName)
        }

        val result = resolver.resolveErlangSdkResult(elixirSdk, ListedSdkModel(erlangSdk))

        assertTrue(result is ErlangSdkResult.Missing)
        val missing = result as ErlangSdkResult.Missing
        assertEquals(MissingErlangSdkReason.MISSING_HOME_PATH, missing.reason)
        assertEquals(configuredName, missing.erlangSdkName)
    }

    fun testResolvingWithoutReadAccessIsRejected() {
        val elixirSdk = createElixirSdk { sdk -> SdkAdditionalData(sdk) }

        val failure = onPooledThread {
            runCatching { resolver.resolveErlangSdkResult(elixirSdk, ListedSdkModel()) }.exceptionOrNull()
        }

        assertTrue(
            "the resolver reads the SDK table, so it must refuse to run without read access; failed with $failure",
            failure?.message?.contains("Read access is allowed from inside read-action") == true,
        )
    }

    fun testResolveByHomePath_success() {
        val erlangHomePath = "/fake/erlang/26.0"
        val erlangSdk = SdkFixtures.erlangSdk("Erlang 26", erlangHomePath)

        val elixirSdk = createElixirSdk { sdk ->
            SdkFixtures.storedPairing(sdk, "Erlang 26", erlangHomePath)
        }

        val result = resolver.resolveErlangSdkResult(elixirSdk, ListedSdkModel(erlangSdk))

        assertTrue("Should resolve successfully by home path", result is ErlangSdkResult.Success)
        assertEquals(erlangSdk, (result as ErlangSdkResult.Success).sdk)
    }

    fun testResolveByHomePath_survivesRename() {
        val erlangHomePath = "/fake/erlang/26.0"
        // SDK was renamed from "Erlang 26" to "My Erlang"
        val erlangSdk = SdkFixtures.erlangSdk("My Erlang", erlangHomePath)

        val elixirSdk = createElixirSdk { sdk ->
            // Persisted with old name but matching home path
            SdkFixtures.storedPairing(sdk, "Erlang 26", erlangHomePath)
        }

        val result = resolver.resolveErlangSdkResult(elixirSdk, ListedSdkModel(erlangSdk))

        assertTrue("Should resolve by path even after rename", result is ErlangSdkResult.Success)
        assertEquals(erlangSdk, (result as ErlangSdkResult.Success).sdk)
    }

    fun testResolveByHomePath_matchesAcrossWslPrefixSpellings() {
        // Registered Erlang SDK home uses the legacy \\wsl$\ spelling; the persisted Elixir SDK
        // configuration uses the modern \\wsl.localhost\ spelling for the same install. Path-first
        // resolution must match across both spellings of the same distro.
        val legacyHomePath = "\\\\wsl$\\IntellijElixirWSLDistribution\\home\\testuser\\.asdf\\installs\\erlang\\26.0"
        val modernHomePath = "\\\\wsl.localhost\\IntellijElixirWSLDistribution\\home\\testuser\\.asdf\\installs\\erlang\\26.0"
        val erlangSdk = SdkFixtures.erlangSdk("Erlang 26", legacyHomePath)

        val elixirSdk = createElixirSdk { sdk ->
            SdkFixtures.storedPairing(sdk, "Erlang 26", modernHomePath)
        }

        val result = resolver.resolveErlangSdkResult(elixirSdk, ListedSdkModel(erlangSdk))

        assertTrue("Should resolve across WSL prefix spellings", result is ErlangSdkResult.Success)
        assertEquals(erlangSdk, (result as ErlangSdkResult.Success).sdk)
    }

    fun testResolveByName_legacyFallback() {
        val configuredName = "Erlang 26"
        val erlangSdk = SdkFixtures.erlangSdk(configuredName, "/fake/erlang/26.0")

        // Legacy config: name only, no home path
        val elixirSdk = createElixirSdk { sdk ->
            SdkFixtures.storedPairing(sdk, configuredName)
        }

        val result = resolver.resolveErlangSdkResult(elixirSdk, ListedSdkModel(erlangSdk))

        assertTrue("Should resolve by name as legacy fallback", result is ErlangSdkResult.Success)
        assertEquals(erlangSdk, (result as ErlangSdkResult.Success).sdk)
    }

    /** An Elixir SDK with the additional data [additionalDataProvider] gives it committed, if any. */
    private fun createElixirSdk(additionalDataProvider: (Sdk) -> SdkAdditionalData?): Sdk =
        SdkFixtures.elixirSdk("Elixir SDK", "/fake/elixir/1.15").also { sdk ->
            additionalDataProvider(sdk)?.let { SdkFixtures.commit(sdk, it) }
        }
}
