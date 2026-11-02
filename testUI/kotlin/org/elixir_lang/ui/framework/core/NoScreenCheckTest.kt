package org.elixir_lang.ui.framework.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Path
import kotlin.io.path.writeText

/** Every way `NoScreen.check` refuses an environment, with no IDE. */
class NoScreenCheckTest {
    @TempDir
    lateinit var temp: Path

    private val stripped: Map<String, String>
        get() = mapOf(
            "BROWSER" to NoScreen.sink.toString(),
            "PATH" to listOf(NoScreen.sink.parent.toString(), "/usr/bin", "/bin").joinToString(File.pathSeparator),
            NoScreen.SINK_LOG to temp.resolve("sink.log").toString(),
            "HOME" to "/home/someone",
        )

    private val display = mapOf("DISPLAY" to ":0", "WAYLAND_DISPLAY" to "wayland-0", "XDG_RUNTIME_DIR" to "/run/user/1000")

    private fun anotherXdgOpen(): Path = temp.resolve("bin/xdg-open").also {
        it.parent.toFile().mkdirs()
        it.writeText("#!/bin/sh\nexit 0\n")
        it.toFile().setExecutable(true)
    }

    private fun refuses(env: Map<String, String>, onScreen: Boolean = false, linux: Boolean = true, naming: String) {
        val error = assertThrows<IllegalStateException> { NoScreen.check(env, onScreen, linux) }
        assertTrue(naming in error.message.orEmpty()) { "expected the refusal to name '$naming': ${error.message}" }
    }

    @Test
    fun `the stripped environment passes`() = assertDoesNotThrow { NoScreen.check(stripped, onScreen = false, linux = true) }

    @Test
    fun `the testUI task's own environment passes`() = assertDoesNotThrow { NoScreen.check() }

    @ParameterizedTest
    @ValueSource(strings = ["DISPLAY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS", "PULSE_SERVER"])
    fun `a removed variable that is set is refused`(name: String) = refuses(stripped + (name to "x"), naming = "$name is set")

    @Test
    fun `headless off Linux is refused, naming the on-screen switch`() =
        refuses(stripped, linux = false, naming = "-PtestUiHeadless=false")

    @Test
    fun `on screen keeps the display variables`() =
        assertDoesNotThrow { NoScreen.check(stripped + display, onScreen = true, linux = true) }

    @Test
    fun `on screen off Linux needs no DISPLAY`() =
        assertDoesNotThrow { NoScreen.check(stripped, onScreen = true, linux = false) }

    @ParameterizedTest
    @ValueSource(strings = ["DBUS_SESSION_BUS_ADDRESS", "PULSE_SERVER"])
    fun `on screen still refuses the session variables`(name: String) =
        refuses(stripped + display + (name to "x"), onScreen = true, naming = "$name is set")

    @Test
    fun `on screen on Linux with no DISPLAY is refused`() =
        refuses(stripped + (display - "DISPLAY"), onScreen = true, naming = "DISPLAY is not set")

    @Test
    fun `no sink log is refused`() = refuses(stripped - NoScreen.SINK_LOG, naming = "${NoScreen.SINK_LOG} is not set")

    @Test
    fun `no BROWSER is refused`() = refuses(stripped - "BROWSER", naming = "BROWSER is not the sink")

    @Test
    fun `a BROWSER other than the sink is refused`() =
        refuses(stripped + ("BROWSER" to anotherXdgOpen().toString()), naming = "BROWSER is not the sink")

    @Test
    fun `another xdg-open first on PATH is refused`() {
        val path = listOf(anotherXdgOpen().parent.toString(), stripped.getValue("PATH")).joinToString(File.pathSeparator)
        refuses(stripped + ("PATH" to path), naming = "the first xdg-open on PATH is not the sink")
    }

    @Test
    fun `no xdg-open on PATH is refused`() =
        refuses(stripped + ("PATH" to temp.toString()), naming = "the first xdg-open on PATH is not the sink")
}
