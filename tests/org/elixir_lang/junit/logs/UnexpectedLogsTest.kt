package org.elixir_lang.junit.logs

import org.elixir_lang.junit.UnitTestCase
import org.elixir_lang.junit.logs.samples.BACKGROUND_ERROR
import org.elixir_lang.junit.logs.samples.BACKGROUND_WARNING
import org.elixir_lang.junit.logs.samples.DETAIL
import org.elixir_lang.junit.logs.samples.FIRST_ATTACHMENT_NAME
import org.elixir_lang.junit.logs.samples.FIRST_ATTACHMENT_TEXT
import org.elixir_lang.junit.logs.samples.JupiterSample
import org.elixir_lang.junit.logs.samples.MULTI_LINE_ATTACHMENT_NAME
import org.elixir_lang.junit.logs.samples.MULTI_LINE_ATTACHMENT_TEXT
import org.elixir_lang.junit.logs.samples.PLATFORM_CATEGORY
import org.elixir_lang.junit.logs.samples.PLATFORM_ERROR
import org.elixir_lang.junit.logs.samples.PLATFORM_WARNING
import org.elixir_lang.junit.logs.samples.SAMPLE_ERROR
import org.elixir_lang.junit.logs.samples.SAMPLE_WARNING
import org.elixir_lang.junit.logs.samples.SECOND_ATTACHMENT_NAME
import org.elixir_lang.junit.logs.samples.SECOND_ATTACHMENT_TEXT
import org.elixir_lang.junit.logs.samples.UNCAUGHT_EXCEPTION
import org.elixir_lang.junit.logs.samples.UncheckedSample
import org.elixir_lang.junit.logs.samples.VintageSample
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import java.io.PrintWriter
import java.io.StringWriter

/** Runs each sample class in a nested launcher. */
class UnexpectedLogsTest : UnitTestCase() {
    private companion object {
        const val FILE_WATCHER_CATEGORY = "#com.intellij.openapi.vfs.impl.local.FileWatcher"
    }

    fun testVintage() {
        val results = run(VintageSample::class.java)

        assertFailedWith(results, "testWarns", "WARN #${VintageSample::class.java.name} - $SAMPLE_WARNING")
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, results.getValue("testExpectsItsWarning").status)
        assertFailedWith(results, "testExpectsAWarningThatNeverComes", "none was logged")
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, results.getValue("testIgnoredWarning").status)
        // Reported rather than failed: only the plugin's own warnings fail a test.
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, results.getValue("testPlatformWarns").status)
        assertFailedWith(results, "testPlatformLogsAnError", "ERROR $PLATFORM_CATEGORY - $PLATFORM_ERROR")
        assertFailedWith(results, "testWarnsOnAnotherThread", BACKGROUND_WARNING)
        assertFailedWith(results, "testLogsAnError", "ERROR #${VintageSample::class.java.name} - $SAMPLE_ERROR")
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, results.getValue("testExpectsItsError").status)
        assertFailedWith(results, "testExpectsAnErrorThatNeverComes", "none was logged")
        assertFailedWith(results, "testLogsAnErrorOnAnotherThread", "ERROR #${VintageSample::class.java.name} - $BACKGROUND_ERROR")
        assertTrue("`Logger.error` returns to the code that logged it", VintageSample.loggedErrorReturned)
        assertFailedWith(results, "testLeavesAnExceptionUncaughtOnAnotherThread", "UNCAUGHT java.lang.IllegalStateException - $UNCAUGHT_EXCEPTION")
        assertFailedWith(results, "testFailsAndWarns", "WARN #${VintageSample::class.java.name} - $SAMPLE_WARNING")
        assertSummaryShownOnce(results, "testWarns")
        assertSummaryShownOnce(results, "testLogsAnError")
        assertConsoleShows(results, "testWarns", "WARN #${VintageSample::class.java.name} - $SAMPLE_WARNING")
        assertConsoleShows(results, "testLogsAnError", "ERROR #${VintageSample::class.java.name} - $SAMPLE_ERROR")
        assertConsoleShows(results, "testLeavesAnExceptionUncaughtOnAnotherThread", UNCAUGHT_EXCEPTION)
        assertSummaryShownOnce(results, "testFailsAndWarns")
        assertEquals(listOf(PLATFORM_WARNING), reported(VintageSample::class.java))
    }

    // The platform logs its diagnosis (a coroutine dump, a thread dump) as the attachments of an error.
    fun testAnErrorsAttachmentsAreShown() {
        val results = run(VintageSample::class.java)
        reported(VintageSample::class.java)

        for (test in listOf("testLogsAnErrorWithAttachments", "testLogsTheSameErrorWithDifferentAttachments")) {
            for (attachment in listOf(FIRST_ATTACHMENT_NAME to FIRST_ATTACHMENT_TEXT, SECOND_ATTACHMENT_NAME to SECOND_ATTACHMENT_TEXT)) {
                val (name, text) = attachment
                assertFailedWith(results, test, "$name\n")
                assertFailedWith(results, test, text)
                assertConsoleShows(results, test, text)
                assertShownOnce(results, test, text)
            }
        }
        assertShownOnce(results, "testLogsAnErrorWithAttachments", FIRST_ATTACHMENT_NAME)
        assertFailedWith(results, "testLogsAnErrorWithADetail", DETAIL)
        assertConsoleShows(results, "testLogsAnErrorWithADetail", DETAIL)
    }

    // Two errors alike but for their attachments are two logs, not one logged twice.
    fun testErrorsThatDifferOnlyInTheirAttachmentsAreNotGrouped() {
        val results = run(VintageSample::class.java)
        reported(VintageSample::class.java)

        val shown = shown(results.getValue("testLogsTheSameErrorWithDifferentAttachments"))
        assertFalse("`$shown` groups them", shown.contains("2 times"))
    }

    // A real dump has many lines, some of which may start with `|`.
    fun testEveryLineOfAnAttachmentIsShownIndentedAndIntact() {
        val results = run(VintageSample::class.java)
        reported(VintageSample::class.java)

        val indented = indented("    ")
        assertFailedWith(results, "testLogsAnErrorWithAMultiLineAttachment", indented)
        assertConsoleShows(results, "testLogsAnErrorWithAMultiLineAttachment", indented)
    }

    fun testReportModeListsAnErrorsAttachments() {
        run(VintageSample::class.java, mode = "report")

        val reported = UnexpectedLogs.drainUnreported { it.test.orEmpty().contains(VintageSample::class.java.name) }
            .joinToString("\n") { it.toString() }
        assertTrue("`$FIRST_ATTACHMENT_TEXT` reported in $reported", reported.contains(FIRST_ATTACHMENT_TEXT))
        assertTrue("`$DETAIL` reported in $reported", reported.contains(DETAIL))
        assertTrue("the multi-line attachment reported in $reported", reported.contains(indented("  ")))
    }

    private fun indented(indent: String): String =
        "\n$indent---\n" + "$MULTI_LINE_ATTACHMENT_NAME\n$MULTI_LINE_ATTACHMENT_TEXT".lines().joinToString("\n") { "$indent$it" }

    fun testJupiter() {
        val results = run(JupiterSample::class.java)

        assertFailedWith(results, "warns()", "WARN #${JupiterSample::class.java.name} - $SAMPLE_WARNING")
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, results.getValue("expectsItsWarning()").status)
        assertFailedWith(results, "expectsAWarningThatNeverComes()", "none was logged")
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, results.getValue("ignoredWarning()").status)
        assertFailedWith(results, "warnsOnAnotherThread()", BACKGROUND_WARNING)
        assertFailedWith(results, "failsAndWarns()", "WARN #${JupiterSample::class.java.name} - $SAMPLE_WARNING")
        assertSummaryShownOnce(results, "warns()")
        assertConsoleShows(results, "warns()", "WARN #${JupiterSample::class.java.name} - $SAMPLE_WARNING")
        assertSummaryShownOnce(results, "failsAndWarns()")
        assertEquals(emptyList<String>(), reported(JupiterSample::class.java))
    }

    fun testReportModeListsTheLogsOfACheckedTest() {
        val results = run(VintageSample::class.java, mode = "report")

        assertEquals(TestExecutionResult.Status.SUCCESSFUL, results.getValue("testWarns").status)
        assertEquals(TestExecutionResult.Status.SUCCESSFUL, results.getValue("testLogsAnError").status)
        val reported = reported(VintageSample::class.java)
        for (message in listOf(SAMPLE_WARNING, BACKGROUND_WARNING, SAMPLE_ERROR, BACKGROUND_ERROR, UNCAUGHT_EXCEPTION)) {
            assertTrue("`$message` reported in $reported", message in reported)
        }
    }

    fun testATestWithoutTheCheckIsReported() {
        val results = run(UncheckedSample::class.java)

        assertEquals(TestExecutionResult.Status.SUCCESSFUL, results.getValue("testWarns").status)
        assertEquals(listOf(SAMPLE_WARNING), reported(UncheckedSample::class.java))
    }

    // `processWarn` returning false is what keeps a warning out of the console and `idea.log`.
    fun testASilentIgnoredWarningIsNotLoggedAndAnotherIgnoredOneIs() {
        val processor = GuardedLoggedErrorProcessor()

        assertFalse(
            processor.processWarn(
                "#com.intellij.openapi.vfs.impl.local.LocalFileSystemBase",
                """\\wsl$\IntellijElixirWSLDistribution\home: The specified network name is no longer available""",
                null,
            ),
        )
        assertTrue(processor.processWarn("#com.intellij.util.ui.StyleSheetUtil", "Missing global CSS sheet", null))
    }

    // A platform bug is ignored only where it is thrown, so the same exception from anywhere else still fails a test.
    fun testAnEntryWithAFrameIgnoresOnlyWhatIsThrownThroughIt() {
        val flushing = thrownThrough("com.intellij.history.core.ChangeListImpl")
        val elsewhere = thrownThrough("org.elixir_lang.Elsewhere")

        assertTrue(IgnoredLogs.matches(flushing.javaClass.name, flushing.message!!, flushing))
        assertTrue(
            IgnoredLogs.matches(
                "#com.intellij.openapi.application.impl.ExceptionsKt",
                "Unhandled exception in [CoroutineName(com.intellij.history.integration.LocalHistoryImpl)]",
                IllegalStateException("unhandled", flushing),
            ),
        )
        assertFalse(IgnoredLogs.matches(elsewhere.javaClass.name, elsewhere.message!!, elsewhere))
    }

    fun testHeadlessAndSplitModePlatformWarningsAreIgnored() {
        assertTrue(
            IgnoredLogs.matches(
                "#com.intellij.ui.jcef.JBCefApp",
                "JCEF is manually disabled in headless env via 'ide.browser.jcef.headless.enabled=false'",
                null,
            ),
        )
        assertTrue(
            IgnoredLogs.matches(
                "#com.intellij.ide.plugins.PluginManager",
                "Environment-configured module is not found: intellij.platform.split",
                null,
            ),
        )
    }

    fun testTheFileWatcherMissingItsExtensionPointInAMockApplicationIsIgnored() {
        assertTrue(
            IgnoredLogs.matches(
                FILE_WATCHER_CATEGORY,
                "Missing extension point: com.intellij.vfs.local.pluggableFileWatcher in container {}",
                null,
            ),
        )
    }

    // Only the empty mock-application container is ignored: a real application missing the extension point, or
    // another extension point missing from the mock, still fails a test.
    fun testTheFileWatcherMissingItsExtensionPointInARealApplicationIsNotIgnored() {
        assertFalse(
            IgnoredLogs.matches(
                FILE_WATCHER_CATEGORY,
                "Missing extension point: com.intellij.vfs.local.pluggableFileWatcher in container ApplicationImpl@1d3a7f2",
                null,
            ),
        )
        assertFalse(
            IgnoredLogs.matches(
                FILE_WATCHER_CATEGORY,
                "Missing extension point: com.intellij.otherExtensionPoint in container {}",
                null,
            ),
        )
        assertFalse(
            IgnoredLogs.matches(
                "#com.intellij.openapi.vfs.impl.local.OtherWatcher",
                "Missing extension point: com.intellij.vfs.local.pluggableFileWatcher in container {}",
                null,
            ),
        )
    }

    private fun thrownThrough(className: String): NullPointerException =
        NullPointerException("getService(...) must not be null").apply {
            stackTrace = arrayOf(StackTraceElement(className, "flush", null, -1))
        }

    private fun run(sample: Class<*>, mode: String = "fail"): Map<String, TestExecutionResult> =
        runNested(selectClass(sample), mode).mapValues { it.value.result }

    private fun assertFailedWith(results: Map<String, TestExecutionResult>, test: String, message: String) {
        val result = results.getValue(test)
        assertEquals(TestExecutionResult.Status.FAILED, result.status)
        val shown = shown(result)
        assertTrue("`$test` failed with:\n$shown", shown.contains(message))
    }

    private fun assertSummaryShownOnce(results: Map<String, TestExecutionResult>, test: String) {
        val shown = shown(results.getValue(test))

        assertEquals("`$test` showed:\n$shown", 1, Regex("unexpected log\\(s\\) in").findAll(shown).count())
    }

    private fun assertShownOnce(results: Map<String, TestExecutionResult>, test: String, text: String) {
        val shown = shown(results.getValue(test))

        assertEquals("`$test` showed:\n$shown", 1, Regex(Regex.escape(text)).findAll(shown).count())
    }

    private fun assertConsoleShows(results: Map<String, TestExecutionResult>, test: String, message: String) {
        val shown = console(results.getValue(test).throwable.orElseThrow())

        assertTrue("`$test` showed on the console:\n$shown", shown.contains(message))
    }

    // What Gradle's exception formatter and the test-logger plugin print: each throwable's `toString()` and frames,
    // down the causes. Neither prints suppressed exceptions.
    private fun console(failure: Throwable): String =
        generateSequence(failure) { it.cause }.joinToString("\nCaused by: ") { throwable ->
            throwable.toString() + throwable.stackTrace.joinToString("") { "\n    at $it" }
        }

    // What an IDE shows: the failure's message, then its stack trace, with the logs as suppressed exceptions.
    private fun shown(result: TestExecutionResult): String {
        val failure = result.throwable.orElseThrow()

        return failure.message.orEmpty() + "\n" + StringWriter().also { failure.printStackTrace(PrintWriter(it)) }
    }

    // Removed so the build's own report does not fail on the samples.
    private fun reported(sample: Class<*>): List<String> =
        UnexpectedLogs.drainUnreported { it.test.orEmpty().contains(sample.name) }.map { it.message }
}
