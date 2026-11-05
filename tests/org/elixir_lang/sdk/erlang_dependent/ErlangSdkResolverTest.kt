package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
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
        val erlangSdk = SdkFixtures.erlangSdk("Erlang 26 at the home", erlangHomePath)
        // The stored name names an SDK at another home: the home comes first.
        val namedElsewhere = SdkFixtures.erlangSdk("Erlang 26", "/fake/erlang/27.0")

        val elixirSdk = createElixirSdk { sdk ->
            SdkFixtures.storedPairing(sdk, "Erlang 26", erlangHomePath)
        }

        assertEquals(
            "Should resolve successfully by home path",
            erlangSdk,
            resolver.resolveErlangSdk(elixirSdk, ListedSdkModel(namedElsewhere, erlangSdk)),
        )
    }

    fun testResolveByHomePath_survivesRename() {
        val erlangHomePath = "/fake/erlang/26.0"
        // SDK was renamed from "Erlang 26" to "My Erlang"
        val erlangSdk = SdkFixtures.erlangSdk("My Erlang", erlangHomePath)

        val elixirSdk = createElixirSdk { sdk ->
            // Persisted with old name but matching home path
            SdkFixtures.storedPairing(sdk, "Erlang 26", erlangHomePath)
        }

        assertEquals(
            "Should resolve by path even after rename",
            erlangSdk,
            resolver.resolveErlangSdk(elixirSdk, ListedSdkModel(erlangSdk)),
        )
    }

    fun testResolveByHomePath_matchesAcrossWslPrefixSpellings() {
        // Registered Erlang SDK home uses the legacy \\wsl$\ spelling; the persisted Elixir SDK
        // configuration uses the modern \\wsl.localhost\ spelling for the same install. Path-first
        // resolution must match across both spellings of the same distro.
        val legacyHomePath = "\\\\wsl$\\IntellijElixirWSLDistribution\\home\\testuser\\.asdf\\installs\\erlang\\26.0"
        val modernHomePath = "\\\\wsl.localhost\\IntellijElixirWSLDistribution\\home\\testuser\\.asdf\\installs\\erlang\\26.0"
        val erlangSdk = SdkFixtures.erlangSdk("Erlang 26", legacyHomePath)

        // A stored name that names nothing, so only the home can find it.
        val elixirSdk = createElixirSdk { sdk ->
            SdkFixtures.storedPairing(sdk, "Erlang 26 before rename", modernHomePath)
        }

        assertEquals(
            "Should resolve across WSL prefix spellings",
            erlangSdk,
            resolver.resolveErlangSdk(elixirSdk, ListedSdkModel(erlangSdk)),
        )
    }

    fun testTheStoredNameBreaksASameHomeTieInTheModel() {
        val home = "/fake/erlang/26.0"
        val first = SdkFixtures.erlangSdk("Erlang 26", home)
        val named = SdkFixtures.erlangSdk("Erlang 26 (2)", home)
        val elixirSdk = createElixirSdk { sdk -> SdkFixtures.storedPairing(sdk, named.name, home) }

        assertEquals(named, resolver.resolveErlangSdk(elixirSdk, ListedSdkModel(first, named)))
    }

    fun testTheStoredNameBreaksASameHomeTieInTheTable() {
        val home = "/fake/erlang/26.0"
        val first = SdkFixtures.register(SdkFixtures.erlangSdk("Erlang 26 tie", home), testRootDisposable)
        val named = SdkFixtures.register(SdkFixtures.erlangSdk("Erlang 26 tie (2)", home), testRootDisposable)
        assertEquals(
            "the table lists the SDKs in the order they were added",
            listOf(first, named),
            ProjectJdkTable.getInstance().allJdks.filter { it.homePath == home },
        )
        val elixirSdk = createElixirSdk { sdk -> SdkFixtures.storedPairing(sdk, named.name, home) }

        assertEquals(named, resolver.resolveErlangSdk(elixirSdk, null))
    }

    /** The SDK the stored name names, added at the home after a lookup answered another, wins without a restart. */
    fun testTheNamedSdkAddedAfterALookupWins() {
        val home = "/fake/erlang/26.0"
        val first = SdkFixtures.register(SdkFixtures.erlangSdk("Erlang 26 looked up", home), testRootDisposable)
        val elixirSdk = createElixirSdk { sdk -> SdkFixtures.storedPairing(sdk, "Erlang 26 re-added", home) }
        assertEquals(
            "precondition: with the named SDK absent, the one at the home answers",
            first,
            resolver.resolveErlangSdk(elixirSdk, null),
        )

        val named = SdkFixtures.register(SdkFixtures.erlangSdk("Erlang 26 re-added", home), testRootDisposable)

        assertEquals(named, resolver.resolveErlangSdk(elixirSdk, null))
    }

    /** An SDK at the home renamed to the stored name, after a lookup answered another, wins without a restart. */
    fun testAnSdkRenamedToTheStoredNameAfterALookupWins() {
        val home = "/fake/erlang/26.0"
        val first = SdkFixtures.register(SdkFixtures.erlangSdk("Erlang 26 first", home), testRootDisposable)
        SdkFixtures.register(SdkFixtures.erlangSdk("Erlang 26 second", home), testRootDisposable)
        val elixirSdk = createElixirSdk { sdk -> SdkFixtures.storedPairing(sdk, "Erlang 26 named", home) }
        assertEquals(
            "precondition: with no SDK of the stored name, the first at the home answers",
            first,
            resolver.resolveErlangSdk(elixirSdk, null),
        )

        val renamed = SdkFixtures.renameInTable("Erlang 26 second", "Erlang 26 named")

        assertEquals(renamed, resolver.resolveErlangSdk(elixirSdk, null))
    }

    fun testWithNoNamedSdkAtTheHomeTheFirstWins() {
        val home = "/fake/erlang/26.0"
        val first = SdkFixtures.erlangSdk("Erlang 26", home)
        val second = SdkFixtures.erlangSdk("Erlang 26 (2)", home)
        // The stored name names an SDK at another home, which the home outranks.
        val namedElsewhere = SdkFixtures.erlangSdk("Erlang 26 elsewhere", "/fake/erlang/27.0")
        val elixirSdk = createElixirSdk { sdk -> SdkFixtures.storedPairing(sdk, namedElsewhere.name, home) }

        assertEquals(first, resolver.resolveErlangSdk(elixirSdk, ListedSdkModel(namedElsewhere, first, second)))
    }

    fun testResolveByName_legacyFallback() {
        val configuredName = "Erlang 26"
        val erlangSdk = SdkFixtures.erlangSdk(configuredName, "/fake/erlang/26.0")

        // Legacy config: name only, no home path
        val elixirSdk = createElixirSdk { sdk ->
            SdkFixtures.storedPairing(sdk, configuredName)
        }

        assertEquals(
            "Should resolve by name as legacy fallback",
            erlangSdk,
            resolver.resolveErlangSdk(elixirSdk, ListedSdkModel(erlangSdk)),
        )
    }

    /** An Elixir SDK with the additional data [additionalDataProvider] gives it committed, if any. */
    private fun createElixirSdk(additionalDataProvider: (Sdk) -> SdkAdditionalData?): Sdk =
        SdkFixtures.elixirSdk("Elixir SDK", "/fake/elixir/1.15").also { sdk ->
            additionalDataProvider(sdk)?.let { SdkFixtures.commit(sdk, it) }
        }
}
