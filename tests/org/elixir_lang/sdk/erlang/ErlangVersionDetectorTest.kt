package org.elixir_lang.sdk.erlang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.registerOrReplaceServiceInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import java.io.File

/** File-based OTP version detection from `<N>/OTP_VERSION` in a home's releases dir. */
class ErlangVersionDetectorTest : PlatformTestCase() {

    private lateinit var tempSdkHome: File

    override fun setUp() {
        super.setUp()

        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            MockWslCompatService(),
            testRootDisposable,
        )

        tempSdkHome = FileUtil.createTempDirectory("erlang_sdk_home", null, true)
    }

    fun testDetectRelease_standardInstall() {
        writeOtpVersion("releases/26", "26.2.5.6")

        val release = detect()

        assertNotNull("Should detect release from OTP_VERSION", release)
        assertEquals("26", release!!.otpMajor)
        assertEquals("26.2.5.6", release.otpVersion)
    }

    fun testDetectRelease_multipleReleaseDirs_picksHighest() {
        // Simulate multiple release directories (e.g. OTP upgrade in place)
        writeOtpVersion("releases/25", "25.3.2.12")
        writeOtpVersion("releases/26", "26.2.5.6")

        val release = detect()

        assertNotNull(release)
        assertEquals("26", release!!.otpMajor)
        assertEquals("26.2.5.6", release.otpVersion)
    }

    fun testDetectRelease_missingReleasesDir() {
        // No releases/ directory at all
        val release = detect()
        assertNull("Should return null when releases/ is missing", release)
    }

    fun testDetectRelease_emptyReleasesDir() {
        File(tempSdkHome, "releases").mkdirs()
        // No numeric subdirectories

        val release = detect()
        assertNull("Should return null when releases/ has no numeric subdirectories", release)
    }

    fun testDetectRelease_missingOtpVersionFile() {
        // Directory exists but no OTP_VERSION file
        File(tempSdkHome, "releases/26").mkdirs()

        val release = detect()
        assertNull("Should return null when OTP_VERSION file is missing", release)
    }

    fun testDetectRelease_emptyOtpVersionFile() {
        writeOtpVersion("releases/26", "   ")

        val release = detect()

        assertNull("Should return null when OTP_VERSION is blank", release)
    }

    fun testDetectRelease_ignoresNonNumericDirs() {
        // Non-numeric directories should be ignored
        File(tempSdkHome, "releases/RELEASES").mkdirs()
        File(tempSdkHome, "releases/start_erl").mkdirs()
        writeOtpVersion("releases/26", "26.0")

        val release = detect()

        assertNotNull(release)
        assertEquals("26", release!!.otpMajor)
        assertEquals("26.0", release.otpVersion)
    }

    fun testDetectRelease_trimsWhitespace() {
        writeOtpVersion("releases/28", "  28.0.3  ")

        val release = detect()

        assertNotNull(release)
        assertEquals("28.0.3", release!!.otpVersion)
    }

    fun testDetectRelease_patchedInPlace_stripsAsterisks() {
        // otp_patch_apply appends "**" to OTP_VERSION when patching in place
        writeOtpVersion("releases/26", "26.2.5.1**")

        val release = detect()

        assertNotNull("Should detect release from patched OTP_VERSION", release)
        assertEquals("26", release!!.otpMajor)
        assertEquals("26.2.5.1", release.otpVersion)
    }

    fun testDetectRelease_releaseCandidate() {
        writeOtpVersion("releases/27", "27.0-rc1")

        val release = detect()

        assertNotNull("Should detect a release candidate", release)
        assertEquals("27", release!!.otpMajor)
        assertEquals("27.0-rc1", release.otpVersion)
    }

    fun testDetectRelease_packagedOtpVersion_usesTheReleasesDirectoryMajor() {
        // A distribution's packaging suffix, which is not an OTP version.
        writeOtpVersion("releases/25", "25.3.2.7-1")

        val release = detect()

        assertNotNull("an SDK must not become unregisterable over an unexpected OTP_VERSION", release)
        assertEquals("25", release!!.otpMajor)
        assertEquals("25.3.2.7-1", release.otpVersion)
        assertTrue(
            "the leading numbers agree with the directory, so they still order it above a plain 25.1; got $release",
            release > Release.parse("25.1")!!,
        )
    }

    fun testDetectRelease_otpVersionWithNoLeadingNumber_usesTheReleasesDirectoryMajor() {
        writeOtpVersion("releases/16", "R16B03-1")

        val release = detect()

        assertNotNull(release)
        assertEquals("16", release!!.otpMajor)
        assertEquals("R16B03-1", release.otpVersion)
    }

    fun testDetectRelease_leadingNumberThatIsNotTheMajor_usesTheReleasesDirectoryMajor() {
        // A packager's text whose leading number is not the major.
        writeOtpVersion("releases/27", "8.2 (OTP 27)")

        val release = detect()

        assertNotNull(release)
        assertEquals("27", release!!.otpMajor)
        assertEquals("8.2 (OTP 27)", release.otpVersion)
    }

    fun testDetectRelease_unparseableOtpVersion_usesTheReleasesDirectoryMajor() {
        writeOtpVersion("releases/26", "not a version")

        val release = detect()

        assertNotNull(release)
        assertEquals("26", release!!.otpMajor)
        assertEquals("not a version", release.otpVersion)
    }

    fun testDetectRelease_miseStyleHome() {
        writeOtpVersion("releases/29", "29.0.6")
        File(tempSdkHome, "releases/RELEASES").writeText("[].\n")
        File(tempSdkHome, "releases/start_erl.data").writeText("15.0 29\n")

        assertEquals("29.0.6", detect()?.otpVersion)
    }

    /** `make install PREFIX=/usr` (Debian), `/opt/homebrew` or a Homebrew Cellar prefix. */
    fun testDetectRelease_installPrefixHome() {
        writeOtpVersion("lib/erlang/releases/27", "27.3.4")

        assertEquals("27.3.4", detect()?.otpVersion)
    }

    /** A multilib `LIBDIR`, as Fedora's `/usr` has. */
    fun testDetectRelease_multilibInstallPrefixHome() {
        writeOtpVersion("lib64/erlang/releases/26", "26.2.5")

        assertEquals("26.2.5", detect()?.otpVersion)
    }

    fun testDetectRelease_homeReleasesReadBeforeAPrefixOnes() {
        writeOtpVersion("releases/28", "28.1")
        writeOtpVersion("lib/erlang/releases/27", "27.3.4")

        assertEquals("28.1", detect()?.otpVersion)
    }

    fun testDetectRelease_libReadBeforeLib64() {
        writeOtpVersion("lib/erlang/releases/27", "27.3.4")
        writeOtpVersion("lib64/erlang/releases/26", "26.2.5")

        assertEquals("27.3.4", detect()?.otpVersion)
    }

    fun testDetectRelease_installPrefixWithEmptyReleases() {
        assertTrue(File(tempSdkHome, "lib/erlang/releases").mkdirs())

        assertNull(detect())
    }

    fun testDetectRelease_firstExistingReleasesDirIsTheOneRead() {
        assertTrue(File(tempSdkHome, "lib/erlang/releases").mkdirs())
        writeOtpVersion("lib64/erlang/releases/26", "26.2.5")

        assertNull("lib/erlang/releases exists, so lib64's is not tried", detect())
    }

    fun testDetectRelease_nonExistentPath() {
        val release = detect("/nonexistent/path/erlang")
        assertNull("Should return null for non-existent path", release)
    }

    private fun writeOtpVersion(releaseDir: String, otpVersion: String) {
        val dir = File(tempSdkHome, releaseDir)
        assertTrue(dir.mkdirs())
        File(dir, "OTP_VERSION").writeText("$otpVersion\n")
    }

    private fun detect(home: String = tempSdkHome.absolutePath): Release? =
        runBlocking(Dispatchers.IO) { ErlangVersionDetector.detectReleaseAt(home) }
}
