package org.elixir_lang.sdk

import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.ScriptedFileChooserFactory
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType

class SdkHomeChooserTest : HeavyTestCase() {
    fun testTheErlangSdkTypeCreatesThroughItsOwnDialog() {
        assertTrue(
            "the platform's fallback checks the environment against user.home and rejects every WSL home",
            ErlangSdkType.instance.supportsCustomCreateUI(),
        )
    }

    fun testTheStartFolderWaitsForASlowScan() {
        val home = createTempDirectory().toPath()

        val folder = SdkHomeChooser.startFolder("Erlang SDK", createTempDirectory().toPath(), { Thread.sleep(500); listOf(home.toString()) }, emptyList())

        assertEquals("a scan through a WSL distro takes longer than the old 200 ms", path(home.toString()), folder?.path)
    }

    /** A scan through an unresponsive WSL distro may never return; Cancel must still end the wait. */
    fun testWaitingForTheScanEndsWhenCancelled() {
        val neverDone = java.util.concurrent.CompletableFuture<String?>()
        val indicator = com.intellij.openapi.progress.EmptyProgressIndicator()

        // Cancelled once running: runProcess starts the indicator, which clears an earlier cancel.
        assertThrows(com.intellij.openapi.progress.ProcessCanceledException::class.java) {
            com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(
                {
                    indicator.cancel()
                    SdkHomeChooser.awaitCancellably(neverDone)
                },
                indicator,
            )
        }
    }

    fun testTheStartFolderFallsBackToTheBasePath() {
        val base = createTempDirectory().toPath()

        val folder = SdkHomeChooser.startFolder("Erlang SDK", base, { emptyList() }, emptyList())

        assertEquals(path(base.toString()), folder?.path)
    }

    fun testTheNewestHomeNotAlreadyAnSdkIsChosen() {
        val homes = listOf(
            "\\\\wsl.localhost\\IntellijElixirWSLDistribution\\home\\u\\.local\\share\\mise\\installs\\erlang\\29.0",
            "\\\\wsl.localhost\\IntellijElixirWSLDistribution\\home\\u\\.local\\share\\mise\\installs\\erlang\\28.1.1",
            "\\\\wsl.localhost\\IntellijElixirWSLDistribution\\home\\u\\.local\\share\\mise\\installs\\erlang\\28.1",
        )

        val registered = listOf("//wsl.localhost/IntellijElixirWSLDistribution/home/u/.local/share/mise/installs/erlang/29.0")

        val chosen = SdkHomeChooser.firstUnregistered(homes, registered)

        assertEquals(
            "29.0 is registered, spelled as the SDK table stores it",
            "\\\\wsl.localhost\\IntellijElixirWSLDistribution\\home\\u\\.local\\share\\mise\\installs\\erlang\\28.1.1",
            chosen,
        )
    }

    fun testTheNewestHomeIsChosenWhenEveryHomeIsAnSdk() {
        val homes = listOf("/erlang/29.0", "/erlang/28.1")

        val chosen = SdkHomeChooser.firstUnregistered(homes, homes)

        assertEquals("/erlang/29.0", chosen)
    }

    fun testElixirBuildsForTheChosenErlangsOtpComeFirst() {
        val homes = listOf(
            "/elixir/1.20.4-otp-29",
            "/elixir/1.20.4-otp-28",
            "/elixir/1.20.4-otp-27",
            "/elixir/1.20.4",
            "/elixir/1.19.5-otp-28",
        )

        val preferred = SdkHomeChooser.preferringOtp(homes, 28)

        assertEquals(
            "that OTP's builds, newest first, then older OTPs a newer one runs, then unknown, then newer OTPs",
            listOf(
                "/elixir/1.20.4-otp-28",
                "/elixir/1.19.5-otp-28",
                "/elixir/1.20.4-otp-27",
                "/elixir/1.20.4",
                "/elixir/1.20.4-otp-29",
            ),
            preferred,
        )
    }

    fun testWithNoErlangChosenTheOrderIsNewestFirst() {
        val homes = listOf("/elixir/1.20.4-otp-29", "/elixir/1.20.4-otp-28")

        assertEquals(homes, SdkHomeChooser.preferringOtp(homes, null))
    }

    fun testTheSdkHomeChooserIsAlwaysIntelliJs() {
        assertTrue(
            "the native Windows and macOS dialogs may ignore the start folder",
            SdkHomeChooser.chooserDescriptor(ErlangSdkType.instance).isForcedToUseIdeaFileChooser,
        )
        assertFalse(ErlangSdkType.instance.homeChooserDescriptor.isForcedToUseIdeaFileChooser)
    }

    fun testCancellingTheHomeCheckChoosesNothing() {
        val home = createTempDirectory().path
        ScriptedFileChooserFactory(Asked(), home).install(testRootDisposable)
        val chosen = mutableListOf<String>()

        SdkHomeChooser.chooseSdkHome(CancellingHomeCheck(), null) { chosen += it }

        assertEmpty(chosen)
    }

    fun testTheBasePathIsTheOpenProject() {
        val root = tempDir.createVirtualDir()
        PsiTestUtil.addContentRoot(module, root)

        assertEquals(project.guessProjectDir()!!.toNioPath(), SdkHomeChooser.defaultBasePath(project))
    }

    private fun path(path: String): String = FileUtil.toSystemIndependentName(path)

    /** Cancels the "Checking SDK home" progress from inside its check, as its Cancel button does. */
    private class CancellingHomeCheck : StubSdkType("Cancelling home check") {
        override fun isValidSdkHome(path: String): Boolean {
            ProgressManager.getInstance().progressIndicator.cancel()
            ProgressManager.checkCanceled()
            return true
        }
    }
}
