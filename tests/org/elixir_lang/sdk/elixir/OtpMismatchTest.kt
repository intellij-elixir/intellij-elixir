package org.elixir_lang.sdk.elixir

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.registerOrReplaceServiceInstance
import org.elixir_lang.junit.onPooledThread
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import java.util.concurrent.atomic.AtomicInteger
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import java.io.File

/**
 * Tests for [ElixirSdkValidation.detectOtpMismatch].
 *
 * Neither overload reads a file: a home the store has not recorded declines.
 */
class OtpMismatchTest : PlatformTestCase() {
    private val tempDirs = mutableListOf<File>()

    override fun tearDown() = runAll(
        { tempDirs.forEach { FileUtil.delete(it) } },
        { tempDirs.clear() },
        { SdkVersionsStore.getInstance().clearForTests() },
        { super.tearDown() },
    )

    // -------------------------------------------------------------------------
    // detectOtpMismatch(sdk)
    // -------------------------------------------------------------------------

    fun testDetectOtpMismatch_readsTheStoreWithNoFilesOnDisk() {
        val erlangSdk = registerErlangSdk(fakeHome("erlang/25.3"), otpVersion = "25.3")
        val elixirSdk = registerElixirSdkPairedWith(erlangSdk, elixirOtpMajor = "27")

        assertEquals("27" to "25", detectOnBackgroundThread(elixirSdk))
    }

    fun testDetectOtpMismatch_comparesMajorsNotFullVersions() {
        val erlangSdk = registerErlangSdk(fakeHome("erlang/26.2.5.21"), otpVersion = "26.2.5.21")
        val elixirSdk = registerElixirSdkPairedWith(erlangSdk, elixirOtpMajor = "26")

        assertNull(detectOnBackgroundThread(elixirSdk))
    }

    fun testDetectOtpMismatch_returnsNullWhenWarningSuppressed() {
        val erlangSdk = registerErlangSdk(fakeHome("erlang/25.3"), otpVersion = "25.3")
        val elixirSdk = registerElixirSdkPairedWith(erlangSdk, elixirOtpMajor = "27", suppressWarning = true)

        assertNull(
            "A suppressed OTP-mismatch warning must short-circuit to null even when majors differ",
            detectOnBackgroundThread(elixirSdk),
        )
    }

    fun testDetectOtpMismatch_declinesForAnErlangHomeNotReadYet() {
        // The file would say 25; it is not consulted, because this runs under the status bar's refresh.
        val erlangSdk = registerErlangSdk(createErlangHome(major = "25", otpVersion = "25.3"), otpVersion = null)
        // Registering read the real home; undone, since the case is a home nothing has read.
        SdkVersionsStore.getInstance().clearForTests()
        val elixirSdk = registerElixirSdkPairedWith(erlangSdk, elixirOtpMajor = "27")

        assertNull(detectOnBackgroundThread(elixirSdk))
    }

    fun testDetectOtpMismatch_declinesWhenTheElixirOtpMajorIsNotRecorded() {
        val erlangSdk = registerErlangSdk(fakeHome("erlang/25.3"), otpVersion = "25.3")
        val elixirSdk = registerElixirSdkPairedWith(erlangSdk, elixirOtpMajor = null)

        assertNull(detectOnBackgroundThread(elixirSdk))
    }

    fun testDetectOtpMismatch_danglingPairingHasNoOtpVersion() {
        val unregisteredErlangSdk = SdkFixtures.erlangSdk("OTP mismatch dangling Erlang", fakeHome("erlang/dangling"))
        // Recorded, so the null result is the dangling pairing and not a missing version.
        SdkVersionsStore.getInstance().setOtpVersion(unregisteredErlangSdk.homePath!!, "25.3")
        val elixirSdk = registerElixirSdkPairedWith(unregisteredErlangSdk, elixirOtpMajor = "27")

        assertNull(detectOnBackgroundThread(elixirSdk))
    }

    // -------------------------------------------------------------------------
    // detectOtpMismatch(elixirHome, erlangHome)
    // -------------------------------------------------------------------------

    fun testDetectOtpMismatchHomes_comparesWhatTheStoreHolds() {
        SdkVersionsStore.getInstance().setElixirVersions(
            fakeHome("elixir/homes"),
            ElixirVersions("1.16.3", OtpMajor.Known("27")),
        )
        SdkVersionsStore.getInstance().setOtpVersion(fakeHome("erlang/homes"), "26.2.5")

        assertEquals("27" to "26", ElixirSdkValidation.detectOtpMismatch(fakeHome("elixir/homes"), fakeHome("erlang/homes")))
    }

    fun testDetectOtpMismatchHomes_declinesForAHomeNotReadYet() {
        SdkVersionsStore.getInstance().setElixirVersions(
            fakeHome("elixir/homes"),
            ElixirVersions("1.16.3", OtpMajor.Known("27")),
        )
        val erlangHome = createErlangHome(major = "26", otpVersion = "26.2.5")

        assertNull(ElixirSdkValidation.detectOtpMismatch(fakeHome("elixir/homes"), erlangHome))
    }

    fun testDetectOtpMismatch_readsNoFilesForABuildRecordedAsHavingNoOtpMajor() {
        val erlangSdk = registerErlangSdk(fakeHome("erlang/25.3"), otpVersion = "25.3")
        // Elixir below 1.6 reports no OTP major: an answer, not a gap to re-read on every status bar refresh.
        val elixirSdk = registerElixirSdkPairedWith(erlangSdk, elixirOtpMajor = null)
        SdkVersionsStore.getInstance().setElixirVersions(elixirSdk.homePath!!, ElixirVersions("1.5.3", OtpMajor.None))
        val resolutions = AtomicInteger()
        // Only the detection's own resolutions are counted: the mock replaces an application-level service, which the
        // rewatch the store writes start resolves homes through too.
        val detecting = ThreadLocal.withInitial { false }
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            object : WslCompatService by MockWslCompatService() {
                override fun canonicalizePath(path: String): String =
                    path.also { if (detecting.get()) resolutions.incrementAndGet() }

                override fun canonicalizePathNullable(path: String?): String? = path?.let(::canonicalizePath)
            },
            testRootDisposable,
        )

        assertNull(
            onPooledThread {
                detecting.set(true)
                ElixirSdkValidation.detectOtpMismatch(elixirSdk).also { detecting.remove() }
            }
        )

        assertEquals("resolving a home is uncached I/O that can boot a WSL distro", 0, resolutions.get())
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun detectOnBackgroundThread(elixirSdk: Sdk): Pair<String, String>? =
        onPooledThread { ElixirSdkValidation.detectOtpMismatch(elixirSdk) }

    private fun registerErlangSdk(homePath: String, otpVersion: String?): Sdk {
        val sdk = SdkFixtures.register(
            SdkFixtures.erlangSdk("OTP mismatch Erlang $homePath", homePath),
            testRootDisposable,
        )
        // A null version leaves the store empty: a home nothing has read yet.
        if (otpVersion != null) SdkVersionsStore.getInstance().setOtpVersion(homePath, otpVersion)
        return sdk
    }

    private fun registerElixirSdkPairedWith(
        erlangSdk: Sdk,
        elixirOtpMajor: String?,
        suppressWarning: Boolean = false,
    ): Sdk {
        val homePath = fakeHome("elixir/1.16")
        val sdk = SdkFixtures.register(
            SdkFixtures.elixirSdk("OTP mismatch Elixir ${erlangSdk.name}", homePath),
            testRootDisposable,
        )
        // The pairing and the suppress flag stay with the SDK; the versions are the installation's.
        SdkFixtures.commit(
            sdk,
            SdkAdditionalData(erlangSdk, sdk).apply { setSuppressOtpMismatchWarning(suppressWarning) },
        )
        if (elixirOtpMajor != null) {
            SdkVersionsStore.getInstance().setElixirVersions(
                homePath,
                ElixirVersions("1.16.3", OtpMajor.Known(elixirOtpMajor)),
            )
        }
        return sdk
    }

    /** Creates a real Erlang home directory containing `releases/<major>/OTP_VERSION`. */
    private fun createErlangHome(major: String, otpVersion: String): String {
        val home = FileUtil.createTempDirectory("erlang_home", null).also { tempDirs.add(it) }
        val releaseDir = File(home, "releases/$major")
        assertTrue("Failed to create $releaseDir", releaseDir.mkdirs())
        File(releaseDir, "OTP_VERSION").writeText("$otpVersion\n")
        return home.path
    }
}
