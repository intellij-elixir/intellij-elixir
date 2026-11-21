package org.elixir_lang.sdk

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.junit.onPooledThread
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.TimeUnit

class SdkVersionFileWatcherTest : PlatformTestCase() {
    private val homesByWatchedPath = mapOf(
        "/opt/erlang/27/releases/27/OTP_VERSION" to setOf("/opt/erlang/27"),
        "/opt/elixir/1.20.5/lib/elixir/ebin/elixir.app" to setOf("/opt/elixir/1.20.5"),
        "/opt/elixir/1.20.5/lib/elixir/ebin/Elixir.System.beam" to setOf("/opt/elixir/1.20.5"),
    )

    fun testAChangedVersionFileNamesItsHome() {
        val events = listOf(contentChange("/opt/erlang/27/releases/27/OTP_VERSION"))

        assertEquals(
            setOf("/opt/erlang/27"),
            SdkVersionFileWatcher.homesToRevalidate(events, homesByWatchedPath),
        )
    }

    fun testAnUnwatchedPathNamesNothing() {
        val events = listOf(contentChange("/opt/erlang/27/releases/27/OTP_VERSION.bak"))

        assertEmpty(SdkVersionFileWatcher.homesToRevalidate(events, homesByWatchedPath))
    }

    fun testADeletedVersionFileNamesItsHome() {
        val events = listOf(deleteElixirApp())

        assertEquals(
            setOf("/opt/elixir/1.20.5"),
            SdkVersionFileWatcher.homesToRevalidate(events, homesByWatchedPath),
        )
    }

    fun testARecreatedVersionFileNamesItsHome() {
        // A package that replaces an install writes the file again rather than editing it in place.
        val events = listOf(create("/opt/erlang/27/releases/27/OTP_VERSION"))

        assertEquals(
            setOf("/opt/erlang/27"),
            SdkVersionFileWatcher.homesToRevalidate(events, homesByWatchedPath),
        )
    }

    fun testAReleaseDirectoryAddedByAnUpgradeNamesItsHome() {
        // An in-place OTP upgrade adds releases/28 beside releases/27 rather than rewriting the watched file.
        val events = listOf(create("/opt/erlang/27/releases/28"))

        assertEquals(
            setOf("/opt/erlang/27"),
            SdkVersionFileWatcher.homesToRevalidate(
                events,
                homesByWatchedPath + ("/opt/erlang/27/releases" to setOf("/opt/erlang/27")),
            ),
        )
    }

    fun testAFileOtpWritesBesideTheReleaseDirectoriesNamesNothing() {
        // `release_handler` rewrites RELEASES and start_erl.data in the directory holding the releases. Treating
        // those as a new release would re-read the installation, and that read clears what it cannot re-read.
        val events = listOf(
            create("/opt/erlang/27/releases/RELEASES"),
            contentChange("/opt/erlang/27/releases/start_erl.data"),
        )

        assertEmpty(
            SdkVersionFileWatcher.homesToRevalidate(
                events,
                homesByWatchedPath + ("/opt/erlang/27/releases" to setOf("/opt/erlang/27")),
            ),
        )
    }

    fun testPathsAreComparedSystemIndependently() {
        val events = listOf(contentChange("C:\\opt\\erlang\\27\\releases\\27\\OTP_VERSION"))

        assertEquals(
            setOf("C:/opt/erlang/27"),
            SdkVersionFileWatcher.homesToRevalidate(
                events,
                mapOf("C:/opt/erlang/27/releases/27/OTP_VERSION" to setOf("C:/opt/erlang/27")),
            ),
        )
    }

    fun testSeveralChangesUnderOneHomeNameItOnce() {
        val events = listOf(
            contentChange("/opt/elixir/1.20.5/lib/elixir/ebin/elixir.app"),
            contentChange("/opt/elixir/1.20.5/lib/elixir/ebin/Elixir.System.beam"),
        )

        assertEquals(
            setOf("/opt/elixir/1.20.5"),
            SdkVersionFileWatcher.homesToRevalidate(events, homesByWatchedPath),
        )
    }

    fun testAPathWatchedForTwoHomesNamesBoth() {
        val events = listOf(contentChange("/usr/lib/erlang/releases/27/OTP_VERSION"))

        assertEquals(
            setOf("/usr", "/usr/lib/erlang"),
            SdkVersionFileWatcher.homesToRevalidate(
                events,
                mapOf("/usr/lib/erlang/releases/27/OTP_VERSION" to setOf("/usr", "/usr/lib/erlang")),
            ),
        )
    }

    fun testAnEventWithNoPathNamesNothing() {
        assertEmpty(SdkVersionFileWatcher.homesToRevalidate(listOf(mock(VFileEvent::class.java)), homesByWatchedPath))
    }

    fun testTheFilesWatchedForAnElixirHome() {
        assertEquals(
            listOf(
                "/opt/elixir/1.20.5/lib/elixir/ebin/elixir.app",
                "/opt/elixir/1.20.5/lib/elixir/ebin/Elixir.System.beam",
            ),
            SdkVersionFileWatcher.elixirVersionFiles("/opt/elixir/1.20.5"),
        )
    }

    fun testTheVersionFilesOfAMiseStyleHome() {
        assertOtpVersionFile(SdkFixtures.erlangHome("27", "27.3.4"), "releases")
    }

    fun testTheVersionFilesOfAnInstallPrefixHome() {
        assertOtpVersionFile(SdkFixtures.erlangPrefixHome("27", "27.3.4"), "lib/erlang/releases")
    }

    private fun assertOtpVersionFile(home: String, releasesDir: String) {
        assertEquals(
            listOf("${installationKey(home)}/$releasesDir/27/OTP_VERSION"),
            SdkVersionFileWatcher.otpVersionFiles(home),
        )
    }

    /** A later rewatch or installation can dispose the parent while a watch is still being set up off the lock. */
    fun testWatchingUnderADisposedParentWatchesNothing() {
        assertEmpty(watchUnderADisposedParent(SdkFixtures.elixirHome("1.20.5")))
    }

    private fun watchUnderADisposedParent(home: String): Set<String> {
        val parent = Disposer.newDisposable().also(Disposer::dispose)

        return onPooledThread<Set<String>>(TimeUnit.SECONDS.toMillis(30)) { SdkVersionFileWatcher.watch(listOf(home), parent) {} }
    }

    private fun contentChange(path: String): VFileContentChangeEvent =
        mock(VFileContentChangeEvent::class.java).also { `when`(it.path).thenReturn(path) }

    private fun deleteElixirApp(): VFileDeleteEvent =
        mock(VFileDeleteEvent::class.java).also { `when`(it.path).thenReturn("/opt/elixir/1.20.5/lib/elixir/ebin/elixir.app") }

    private fun create(path: String): VFileCreateEvent =
        mock(VFileCreateEvent::class.java).also { `when`(it.path).thenReturn(path) }
}
