package org.elixir_lang.ui.framework.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Path
import kotlin.io.path.Path

/** What the guard makes of what a test left, with no IDE. */
class NoScreenGuardTest {
    private val home = Path("/tests/IU-261/CodeIntelligence-walk")

    private fun left(sink: List<String> = emptyList(), displays: Set<String> = emptySet(), homes: List<Path> = listOf(home)) =
        NoScreenGuard.Left(sink, emptySet(), emptyList(), emptyList(), homes, displays, listOf(home.resolve("log/xvfb-log.txt")))

    @Test
    fun `nothing left passes`() {
        assertEquals(emptyList<String>(), NoScreenGuard.problems(left(homes = emptyList()), onScreen = false, linux = true))
    }

    @Test
    fun `an IDE seen on Xvfb passes`() {
        assertEquals(emptyList<String>(), NoScreenGuard.problems(left(displays = setOf(":88")), onScreen = false, linux = true))
    }

    @Test
    fun `on Linux, an IDE on no display fails`() {
        val problems = NoScreenGuard.problems(left(), onScreen = true, linux = true)
        assertTrue(problems.any { "no process carried a DISPLAY" in it }) { "$problems" }
    }

    @Test
    fun `off Linux, on screen, no process is looked for`() {
        assertEquals(emptyList<String>(), NoScreenGuard.problems(left(), onScreen = true, linux = false))
    }

    /** The guard finds leftovers only on Linux, so only there does it stop any. */
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `a leftover that ignores SIGTERM is stopped`() {
        val process = ProcessBuilder("sh", "-c", "trap '' TERM; sleep 60 & wait; sleep 60")
            // On screen it would carry the real home, which the guard refuses for a process with a display.
            .apply { environment()["HOME"] = NoScreen.privateHome.toString() }
            .start()
        try {
            Thread.sleep(200)
            NoScreenGuard.stop(listOf(process.toHandle()))
            assertFalse(process.isAlive) { "pid ${process.pid()} still runs" }
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `the browser route's URL opened in a test fails`() {
        val problems = NoScreenGuard.problems(left(sink = listOf("2026-11-02T11:00:00Z https://example.invalid/"), displays = setOf(":88")), onScreen = false, linux = true)
        assertTrue(problems.any { "something tried to open" in it }) { "$problems" }
    }
}
