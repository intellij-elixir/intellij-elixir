package org.elixir_lang.junit.logs.samples

import com.intellij.openapi.diagnostic.Attachment
import com.intellij.openapi.diagnostic.Logger
import org.elixir_lang.junit.UnitTestCase
import org.elixir_lang.junit.logs.expectErrors
import org.elixir_lang.junit.logs.expectWarnings
import kotlin.concurrent.thread

/** Run only by `UnexpectedLogsTest`, in a nested launcher; the `test` task excludes this package. */
@Suppress("LoggingSimilarMessage") // The same warning and error, expected and not.
class VintageSample : UnitTestCase() {
    fun testWarns() {
        logger().warn(SAMPLE_WARNING)
    }

    fun testExpectsItsWarning() {
        expectSampleWarning(warn = true)
    }

    fun testExpectsAWarningThatNeverComes() {
        expectSampleWarning(warn = false)
    }

    fun testIgnoredWarning() {
        Logger.getInstance("#com.intellij.util.ui.StyleSheetUtil").warn("Missing global CSS sheet")
    }

    fun testPlatformWarns() {
        Logger.getInstance(PLATFORM_CATEGORY).warn(PLATFORM_WARNING)
    }

    fun testPlatformLogsAnError() {
        Logger.getInstance(PLATFORM_CATEGORY).error(PLATFORM_ERROR)
    }

    fun testWarnsOnAnotherThread() {
        warnOnAnotherThread()
    }

    fun testLogsAnError() {
        logger().error(SAMPLE_ERROR)
    }

    fun testLogsAnErrorWithAttachments() {
        logger().error(SAMPLE_ERROR, Attachment(FIRST_ATTACHMENT_NAME, FIRST_ATTACHMENT_TEXT), Attachment(SECOND_ATTACHMENT_NAME, SECOND_ATTACHMENT_TEXT))
    }

    fun testLogsAnErrorWithADetail() {
        logger().error(SAMPLE_ERROR, DETAIL)
    }

    fun testLogsTheSameErrorWithDifferentAttachments() {
        // One call site, so the two logs have the same frames and only their attachments tell them apart.
        for ((name, text) in listOf(FIRST_ATTACHMENT_NAME to FIRST_ATTACHMENT_TEXT, SECOND_ATTACHMENT_NAME to SECOND_ATTACHMENT_TEXT)) {
            logger().error(SAMPLE_ERROR, Attachment(name, text))
        }
    }

    fun testLogsAnErrorWithAMultiLineAttachment() {
        logger().error(SAMPLE_ERROR, Attachment(MULTI_LINE_ATTACHMENT_NAME, MULTI_LINE_ATTACHMENT_TEXT))
    }

    fun testExpectsItsError() {
        expectSampleError(log = true)
    }

    fun testExpectsAnErrorThatNeverComes() {
        expectSampleError(log = false)
    }

    fun testLogsAnErrorOnAnotherThread() {
        logAnErrorOnAnotherThread()
    }

    fun testFailsAndWarns() {
        logger().warn(SAMPLE_WARNING)
        fail(SAMPLE_FAILURE)
    }

    fun testLeavesAnExceptionUncaughtOnAnotherThread() {
        throwOnAnotherThread()
    }

    // Kept out of the test methods: JUnit 3 would run a Kotlin lambda's `test...$lambda$0` method as a test of its own.
    private fun expectSampleWarning(warn: Boolean) {
        expectWarnings(VintageSample::class.java, Regex("^$SAMPLE_WARNING$")) { if (warn) logger().warn(SAMPLE_WARNING) }
    }

    private fun expectSampleError(log: Boolean) {
        expectErrors(VintageSample::class.java, Regex("^$SAMPLE_ERROR$")) { if (log) logger().error(SAMPLE_ERROR) }
    }

    private fun logAnErrorOnAnotherThread() {
        loggedErrorReturned = false
        thread {
            logger().error(BACKGROUND_ERROR)
            loggedErrorReturned = true
        }.join()
    }

    private fun throwOnAnotherThread() {
        thread { throw IllegalStateException(UNCAUGHT_EXCEPTION) }.join()
    }

    private fun warnOnAnotherThread() {
        thread { logger().warn(BACKGROUND_WARNING) }.join()
    }

    private fun logger() = Logger.getInstance(VintageSample::class.java)

    companion object {
        @Volatile
        var loggedErrorReturned = false
    }
}

const val SAMPLE_WARNING = "sample warning"
const val BACKGROUND_WARNING = "background warning"
const val SAMPLE_ERROR = "sample error"
const val BACKGROUND_ERROR = "background error"
const val UNCAUGHT_EXCEPTION = "uncaught exception"
const val FIRST_ATTACHMENT_NAME = "coroutineDump.txt"
const val FIRST_ATTACHMENT_TEXT = "coroutine still running in the first scope"
const val SECOND_ATTACHMENT_NAME = "EDT.txt"
const val SECOND_ATTACHMENT_TEXT = "edt thread dump of the second scope"
const val MULTI_LINE_ATTACHMENT_NAME = "dump.txt"
const val MULTI_LINE_ATTACHMENT_TEXT = "first line\n| piped line\nthird line"
const val DETAIL ="plain detail text"
const val SAMPLE_FAILURE = "sample failure"
const val PLATFORM_CATEGORY = "#com.example.platform.Sample"
const val PLATFORM_WARNING = "platform warning"
const val PLATFORM_ERROR = "platform error"
