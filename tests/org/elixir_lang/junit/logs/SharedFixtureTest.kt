package org.elixir_lang.junit.logs

import org.elixir_lang.junit.UnitTestCase
import org.elixir_lang.junit.logs.samples.SAMPLE_ERROR
import org.elixir_lang.junit.logs.samples.SAMPLE_FAILURE
import org.elixir_lang.junit.logs.samples.SAMPLE_WARNING
import org.elixir_lang.junit.logs.samples.SharedFixtureSample
import org.elixir_lang.junit.logs.samples.SharedFixtureSample.Companion.SET_UP_FAILURE
import org.elixir_lang.junit.logs.samples.SharedFixtureSample.Companion.SLEEP_MILLIS
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.FilterResult
import org.junit.platform.engine.TestExecutionResult.Status
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod
import org.junit.platform.launcher.PostDiscoveryFilter

/** Runs [SharedFixtureSample], whose cases share one fixture, in a nested launcher. */
class SharedFixtureTest : UnitTestCase() {
    fun testACaseCarriesOnlyItsOwnLogsFailureAndTime() {
        val finished = runSample()

        assertEquals(SharedFixtureSample.CASES.toSet(), finished.keys)
        val logs = finished.getValue("logs").result
        assertEquals(Status.FAILED, logs.status)
        val warned = logs.throwable.orElseThrow().stackTraceToString()
        assertTrue(warned, warned.contains("WARN #${SharedFixtureSample::class.java.name} - $SAMPLE_WARNING"))
        val fails = finished.getValue("fails").result
        assertEquals(Status.FAILED, fails.status)
        assertEquals(SAMPLE_FAILURE, fails.throwable.orElseThrow().message)
        // Not the last case, which closes the fixture.
        val errs = finished.getValue("errs").result
        assertEquals(Status.FAILED, errs.status)
        val shown = errs.throwable.orElseThrow().stackTraceToString()
        assertTrue(shown, shown.contains("ERROR #${SharedFixtureSample::class.java.name} - $SAMPLE_ERROR"))
        for (case in listOf("a", "posts", "sleeps", "c", "d")) {
            assertEquals(case, Status.SUCCESSFUL, finished.getValue(case).result.status)
        }
        assertTrue("sleeps took ${finished.getValue("sleeps").millis} ms", finished.getValue("sleeps").millis >= SLEEP_MILLIS)
        // `c` is neither first nor last, so it carries neither set-up nor tear-down.
        assertTrue("c took ${finished.getValue("c").millis} ms", finished.getValue("c").millis < SLEEP_MILLIS)
        assertOneFixture()
    }

    fun testASelectedCaseIsCheckedAlone() {
        val finished = runSample(selectMethod(SharedFixtureSample::class.java, "sleeps"))

        assertEquals(setOf("sleeps"), finished.keys)
        assertEquals(listOf("sleeps"), SharedFixtureSample.checked)
        assertOneFixture()
    }

    fun testACaseAFilterKeepsIsCheckedAloneAndTheFixtureStillTornDown() {
        val onlyA = PostDiscoveryFilter { descriptor ->
            FilterResult.includedIf(!descriptor.isTest || descriptor.displayName == "a")
        }
        val finished = runSample(filter = onlyA)

        assertEquals(setOf("a"), finished.keys)
        assertEquals(listOf("a"), SharedFixtureSample.checked)
        assertOneFixture()
    }

    fun testAFailedSetUpFailsTheFirstCaseAndLeavesTheRestUnchecked() {
        SharedFixtureSample.failSetUp = true
        val finished = try {
            runSample()
        } finally {
            SharedFixtureSample.failSetUp = false
        }

        assertEquals(SET_UP_FAILURE, finished.getValue("a").result.throwable.orElseThrow().message)
        for (case in SharedFixtureSample.CASES.drop(1)) {
            val result = finished.getValue(case).result
            assertEquals(case, Status.FAILED, result.status)
            assertEquals(case, "Not checked: the shared fixture ended in a", result.throwable.orElseThrow().message)
        }
        assertEquals(emptyList<String>(), SharedFixtureSample.checked)
        assertEquals(1, SharedFixtureSample.tearDowns.get())
    }

    private fun runSample(
        selector: DiscoverySelector = selectClass(SharedFixtureSample::class.java),
        filter: PostDiscoveryFilter? = null,
    ): Map<String, Finished> =
        try {
            runNested(selector, filter = filter)
        } finally {
            // Removed so the build's own report does not fail on the sample.
            UnexpectedLogs.drainUnreported { it.test.orEmpty().contains(SharedFixtureSample::class.java.name) }
        }

    private fun assertOneFixture() {
        assertEquals("set-ups", 1, SharedFixtureSample.setUps.get())
        assertEquals("tear-downs", 1, SharedFixtureSample.tearDowns.get())
    }
}
