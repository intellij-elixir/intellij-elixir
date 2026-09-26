package org.elixir_lang.mix

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.progress.coroutineToIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.elixir_lang.junit.LightTestCase
import org.elixir_lang.run.ElixirCapturingProcessHandler
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

class WaitForOutputTest : LightTestCase() {
    /** A project closed, or the IDE shutting down, while `mix deps` runs must not leave the check waiting on it. */
    fun testACancelledWaitDestroysTheProcess() {
        val handler = ElixirCapturingProcessHandler(sleeper())

        runBlocking {
            // As `DepsCheckerService` calls it.
            val waiting = launch(Dispatchers.IO) { coroutineToIndicator { waitForOutput(handler, TIMEOUT_MS) } }
            delay(STARTED_MS.milliseconds)

            val stopped = measureTime { waiting.cancelAndJoin() }

            assertTrue("the wait ended $stopped after it was cancelled", stopped < 5_000.milliseconds)
        }
        assertTrue("the process was destroyed", handler.waitFor(5_000))
    }

    /** A BEAM can outlive both interrupts, and the check must not wait on it for as long as it runs. */
    fun testACancelledWaitKillsAProcessThatSurvivesItsInterrupts() {
        val handler = object : ElixirCapturingProcessHandler(sleeper()) {
            override fun interrupt(fallback: () -> Unit) {}
        }

        runBlocking {
            val waiting = launch(Dispatchers.IO) { coroutineToIndicator { waitForOutput(handler, TIMEOUT_MS) } }
            delay(STARTED_MS.milliseconds)

            val stopped = measureTime { waiting.cancelAndJoin() }

            assertTrue("the wait ended $stopped after it was cancelled", stopped < 10_000.milliseconds)
        }
        assertTrue("the process was killed", handler.waitFor(5_000))
    }

    fun testATimedOutWaitDestroysTheProcess() {
        val handler = ElixirCapturingProcessHandler(sleeper())

        val output = waitForOutput(handler, 1_000)

        assertTrue("the wait reported the timeout", output.isTimeout)
        assertTrue("the process was destroyed", handler.waitFor(5_000))
    }

    /** A JVM running a single-file program that sleeps, so it behaves the same on every OS. */
    private fun sleeper(): GeneralCommandLine {
        val source = File(FileUtil.createTempDirectory("sleeper", null, true), "Sleeper.java")
        source.writeText("class Sleeper { public static void main(String[] a) throws Exception { Thread.sleep($SLEEP_MS); } }")
        val java = ProcessHandle.current().info().command().orElseThrow()

        return GeneralCommandLine(java, source.path)
    }

    private companion object {
        const val SLEEP_MS = 30_000
        const val TIMEOUT_MS = 60_000
        const val STARTED_MS = 2_000L
    }
}
