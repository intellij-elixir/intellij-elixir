package org.elixir_lang.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.registerOrReplaceServiceInstance
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.junit.onPooledThread
import org.elixir_lang.mix.sync.MixSyncTestHelpers.runSuspendOnPooledThread
import org.elixir_lang.sdk.SdkFixtures.elixirHome
import org.elixir_lang.sdk.SdkFixtures.erlangHome
import org.elixir_lang.sdk.elixir.ElixirBuildInfo
import org.elixir_lang.sdk.elixir.ElixirVersions
import org.elixir_lang.sdk.elixir.OtpMajor
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import org.elixir_lang.sdk.wsl.wslCompat
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import com.intellij.util.concurrency.annotations.RequiresEdt

class SdkRegistrarVersionsTest : PlatformTestCase() {
    private val registered = mutableListOf<Sdk>()
    private val store get() = SdkVersionsStore.getInstance()

    override fun tearDown() {
        try {
            WriteAction.run<Throwable> {
                val table = ProjectJdkTable.getInstance()
                registered.filter { sdk -> table.allJdks.any { it === sdk } }.forEach(table::removeJdk)
            }
            registered.clear()
            store.clearForTests()
        } finally {
            super.tearDown()
        }
    }

    fun testRegisteringAnErlangSdkRecordsItsOtpVersion() {
        val erlangSdk = registerErlang(erlangHome("27", "27.0-rc1"))

        assertEquals("27.0-rc1", store.otpVersion(erlangSdk.homePath))
    }

    fun testRegisteringAnElixirSdkRecordsItsVersionVerbatim() {
        val erlangSdk = registerErlang(erlangHome("27", "27.3.4"))

        val elixirSdk = registerElixir(elixirHome("1.20.0-rc.0"), erlangSdk)

        assertEquals("1.20.0-rc.0", store.elixirVersions(elixirSdk.homePath)?.elixirVersion)
        assertNotNull(
            "the pairing stays in the SDK, which is what JPS reads",
            SdkFixtures.storedErlangHome(elixirSdk),
        )
    }

    fun testRegisteringTheResolvedElixirSdkRecordsItsOtpMajor() {
        val elixirHome = System.getenv("ELIXIR_LANG_ELIXIR_PATH")
        assertNotNull("ELIXIR_LANG_ELIXIR_PATH not set for the test JVM", elixirHome)
        val erlangSdk = registerErlang(erlangHome("27", "27.3.4"))
        val expectedOtpMajor = onPooledThread {
            ElixirBuildInfo.elixirOtpRelease(wslCompat.canonicalizePath(elixirHome!!))
        }
        assertNotNull("the resolved Elixir SDK must report its OTP major", expectedOtpMajor)

        val elixirSdk = registerElixir(elixirHome!!, erlangSdk)

        assertEquals(OtpMajor.Known(expectedOtpMajor!!), store.elixirVersions(elixirSdk.homePath)?.elixirOtpMajor)
    }

    fun testRegisteringAnErlangSdkWhoseOtpVersionCarriesAPackagingSuffix() {
        val home = erlangHome("25", "25.3.2.7-1")

        val erlangSdk = runSuspendOnPooledThread(60_000) { SdkRegistrar.registerOrUpdateErlangSdk(home) }

        assertNotNull("an OTP_VERSION a distribution wrote must still register", erlangSdk)
        erlangSdk!!.also(registered::add)
        SdkFixtures.waitForRegistrationFills()
        assertEquals("25.3.2.7-1", store.otpVersion(erlangSdk.homePath))
    }

    fun testRegisteringAnErlangSdkReadsItsHomeOnce() {
        val home = erlangHome("27", "27.3.4")
        val resolutions = CopyOnWriteArrayList<String>()
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            object : WslCompatService by MockWslCompatService() {
                // Both routes are overridden: Kotlin delegation resolves a default method against the delegate,
                // so overriding canonicalizePath alone would miss every call arriving through the nullable form.
                override fun canonicalizePath(path: String): String {
                    resolutions.add(FileUtil.toSystemIndependentName(path))
                    return FileUtil.toSystemIndependentName(home)
                }

                override fun canonicalizePathNullable(path: String?): String? =
                    path?.let { canonicalizePath(it) }
            },
            testRootDisposable,
        )

        registerErlang(home)

        // Only this home is counted: the mock replaces an application-level service, so a fill an earlier test left
        // running on a coroutine can land on it.
        assertEquals(
            "registration resolved the home more than once: $resolutions",
            1,
            resolutions.count { it == FileUtil.toSystemIndependentName(home) },
        )
    }

    fun testRegisteringAnElixirSdkReadsItsHomeOnce() {
        val erlangSdk = registerErlang(erlangHome("27", "27.3.4"))
        val home = elixirHome("1.19.5")
        val resolutions = CopyOnWriteArrayList<String>()
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            object : WslCompatService by MockWslCompatService() {
                override fun canonicalizePath(path: String): String =
                    FileUtil.toSystemIndependentName(path).also(resolutions::add)

                override fun canonicalizePathNullable(path: String?): String? = path?.let { canonicalizePath(it) }
            },
            testRootDisposable,
        )

        registerElixir(home, erlangSdk)

        assertEquals(
            "registration resolved the Elixir home more than once: $resolutions",
            1,
            resolutions.count { it == FileUtil.toSystemIndependentName(home) },
        )
    }

    fun testRegisteringAgainKeepsTheRecordedOtpVersionWhenDetectionFails() {
        val home = erlangHome("27", "27.3.4")
        val erlangSdk = registerErlang(home)
        assertEquals("precondition: the version is recorded", "27.3.4", store.otpVersion(erlangSdk.homePath))
        // A transient failure to read the home, as a WSL distro that is not responding gives.
        assertTrue(File(home, "releases/27/OTP_VERSION").delete())

        val again = runSuspendOnPooledThread(60_000) {
            SdkRegistrar.registerOrUpdateErlangSdk(home, resolvedVersion = "27.3.4")
        }

        assertSame(erlangSdk, again)
        assertEquals(
            "a failed detection must not wipe what was read before",
            "27.3.4",
            store.otpVersion(erlangSdk.homePath),
        )
    }

    @RequiresEdt
    fun testRegisteringAgainFillsAnSdkSavedWithoutVersions() {
        val erlangSdk = registerErlang(erlangHome("26", "26.2.5.21"))
        val elixirHome = elixirHome("1.19.5")
        val elixirSdk = registerElixir(elixirHome, erlangSdk)
        // As saved before the versions were recorded: the pairing alone, and nothing in the store.
        SdkFixtures.commitPairing(elixirSdk, erlangSdk)
        store.clearForTests()
        assertNotNull(
            "precondition: a confirmed pairing takes the fast path",
            SdkFixtures.storedErlangHome(elixirSdk),
        )

        val again = registerElixir(elixirHome, erlangSdk)

        assertSame(elixirSdk, again)
        assertEquals(ElixirVersions("1.19.5", OtpMajor.None), store.elixirVersions(elixirSdk.homePath))
    }

    private fun registerErlang(homePath: String): Sdk =
        runSuspendOnPooledThread(60_000) { SdkRegistrar.registerOrUpdateErlangSdk(homePath) }!!
            .also { registered.add(it); SdkFixtures.waitForRegistrationFills() }

    private fun registerElixir(homePath: String, erlangSdk: Sdk): Sdk =
        runSuspendOnPooledThread(60_000) { SdkRegistrar.registerOrUpdateElixirSdk(homePath, erlangSdk) }!!
            .also { registered.add(it); SdkFixtures.waitForRegistrationFills() }
}
