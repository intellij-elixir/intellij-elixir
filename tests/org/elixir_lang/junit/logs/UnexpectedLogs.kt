package org.elixir_lang.junit.logs

import com.intellij.util.ThrowableRunnable
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Fails a test that logs an error, or a warning from the plugin's own loggers, or leaves an exception uncaught on any
 * thread, that it did not expect, whichever engine runs it. A platform warning is only reported: they change with each
 * platform version and runner, and none has pointed at a plugin bug.
 *
 * [GuardedLoggedErrorProcessor] is installed as the JVM-wide `LoggedErrorProcessor` for the whole run, so every
 * `Logger.warn` and `Logger.error`, on any thread, is recorded against the running test, as is an uncaught exception.
 * The platform already fails a test for an error logged on its own thread; one logged on another thread, or caught by
 * the code under test, fails nothing without this.
 *
 * Nothing is thrown where it is logged: platform code catches what its callees throw, and a throw in a tearDown leaves
 * its cleanup undone. The test is failed once it has finished, tearDown included: by [failOnUnexpectedLogs], which the
 * plugin's JUnit 3 base classes and [UnexpectedLogsRule] wrap each test in, and by [UnexpectedLogsExtension] for
 * Jupiter. What no test was failed for - logged after its test finished, outside any test, or in a test class without
 * the check - is written to a report the Gradle `test` task fails on; platform warnings to one it only lists.
 */
object UnexpectedLogs {
    /** `fail` (the default) or `report`, which records and reports without failing anything. */
    const val MODE_PROPERTY = "elixir.test.logs"

    /** The directory each JVM writes its report to; without it the report goes to stderr. */
    const val REPORT_PROPERTY = "elixir.test.logs.report"

    val failing: Boolean
        get() = System.getProperty(MODE_PROPERTY) != "report"

    enum class Level { WARN, ERROR, UNCAUGHT }

    class Logged(
        /** The running test's unique id, or null outside any test. */
        val test: String?,
        /** The running test as a person names it: `Class.method`. */
        val testName: String?,
        val thread: String,
        val level: Level,
        val category: String,
        val message: String,
        val cause: Throwable?,
        val frames: List<StackTraceElement>,
        /** What `Logger.error` was given beside the message: each attachment as its name, a newline and its text. */
        val details: List<String> = emptyList(),
    ) {
        @Volatile
        var reported = false

        /** Whether it fails the test it was logged in, rather than only being reported. */
        val fails: Boolean
            get() = level != Level.WARN || category.startsWith(PLUGIN_CATEGORY)

        val header: String
            get() = "$level $category - $message [${testName ?: "outside any test"}, thread $thread]"

        // `Logger.error(message)` passes `Throwable(message)`, and an uncaught exception is its own category and message.
        val informativeCause: Throwable?
            get() = cause?.takeUnless {
                level == Level.UNCAUGHT || (it.javaClass == Throwable::class.java && it.message == message)
            }

        /** Each detail indented under the header, with a rule before it. */
        fun detailsText(indent: String): String =
            details.joinToString("") { detail ->
                "\n$indent---\n" + detail.lines().joinToString("\n") { "$indent$it" }
            }

        /** For the report, which no IDE renders: the test's full id and the frames as text. */
        override fun toString(): String =
            "${test ?: "(outside any test)"}\n  $header" +
                (informativeCause?.let { "\n  caused by $it" } ?: "") +
                detailsText("  ") +
                frames.joinToString("") { "\n    at $it" }
    }

    private class Running(val id: String, val name: String, val thread: Thread) {
        val logged = ConcurrentLinkedQueue<Logged>()
    }

    // A stack, because a test can run a nested launcher, as the guard's own tests do.
    private val running = ArrayDeque<Running>()
    private val unreported = ConcurrentLinkedQueue<Logged>()

    fun record(level: Level, category: String, message: String, t: Throwable?, details: List<String> = emptyList()) {
        if (IgnoredLogs.matches(category, message, t)) return

        val current = synchronized(running) { running.lastOrNull() }
        val thread = when {
            // A JUnit 3 platform test runs its body there.
            EventQueue.isDispatchThread() -> LoggingThread.EDT
            Thread.currentThread() === current?.thread -> LoggingThread.TEST
            else -> LoggingThread.BACKGROUND
        }
        val frames = if (level == Level.UNCAUGHT) tidy(t?.stackTrace.orEmpty().asList(), thread) else callerFrames(thread)
        val logged = Logged(current?.id, current?.name, Thread.currentThread().name, level, category, message, t, frames, details)

        if (current == null) unreported += logged else current.logged += logged
    }

    /** Runs [test], then fails it for everything unexpected logged while it ran, keeping its own failure if it had one. */
    @JvmStatic
    fun failOnUnexpectedLogs(test: ThrowableRunnable<Throwable>) {
        val failure = try {
            test.run()
            null
        } catch (t: Throwable) {
            t
        }
        val logged = takeCurrent()

        if (logged.isNotEmpty()) {
            throw failure?.apply { addSuppressed(UnexpectedLogsError(logged)) } ?: UnexpectedLogsError(logged)
        }

        failure?.let { throw it }
    }

    internal fun testStarted(id: String, name: String) {
        synchronized(running) { running.addLast(Running(id, name, Thread.currentThread())) }
    }

    /** Moves what the test was not failed for to the report. */
    internal fun testFinished(id: String) {
        val finished = synchronized(running) {
            running.indexOfLast { it.id == id }.takeIf { it >= 0 }?.let { running.removeAt(it) }
        } ?: return

        finished.logged.filterNot { it.reported }.forEach { unreported += it }
    }

    /**
     * The current test's unexpected logs it has not been failed for, marked so the report does not repeat them. None
     * when not [failing], so the report keeps them all.
     */
    internal fun takeCurrent(): List<Logged> {
        if (!failing) return emptyList()
        val current = synchronized(running) { running.lastOrNull() } ?: return emptyList()

        return current.logged.filter { !it.reported && it.fails }.onEach { it.reported = true }
    }

    /** Removes and returns what is recorded for the report and matches [predicate]. */
    internal fun drainUnreported(predicate: (Logged) -> Boolean = { true }): List<Logged> =
        unreported.filter(predicate).also { unreported.removeAll(it.toSet()) }

    internal fun writeReport() {
        val (failing, platformWarnings) = drainUnreported().partition { it.fails }
        write(failing, "", "unexpected log(s) that did not fail a test")
        write(platformWarnings, PLATFORM_WARNINGS_PREFIX, "platform warning(s)")
    }

    private fun write(logged: List<Logged>, prefix: String, what: String) {
        if (logged.isEmpty()) return

        val text = logged.joinToString("\n\n")
        val directory = System.getProperty(REPORT_PROPERTY)

        if (directory == null) {
            System.err.println("${logged.size} $what:\n$text")
        } else {
            File(directory).apply { mkdirs() }
                .resolve("${prefix}jvm-${ProcessHandle.current().pid()}.txt")
                .appendText("$text\n\n")
        }
    }

    /** Starts the name of a report file that only lists: the build fails on every other one. */
    const val PLATFORM_WARNINGS_PREFIX = "platform-warnings-"

    private const val PLUGIN_CATEGORY = "#org.elixir_lang."

    // Everything up to the logger's last frame is logging and processors, so the first frame is the code that logged.
    private fun callerFrames(thread: LoggingThread): List<StackTraceElement> {
        val frames = Throwable().stackTrace
        val logger = frames.indexOfLast { frame -> LOGGING_PACKAGES.any { frame.className.startsWith(it) } }

        return tidy(frames.drop(logger + 1), thread)
    }

    private enum class LoggingThread { TEST, EDT, BACKGROUND }

    /**
     * Drops, from the bottom, only frames that say nothing about how the code that logged was reached: the runners',
     * and on the EDT or another thread its dispatcher's. Fixture and platform frames between the test and that code
     * stay, since the trim stops at the first frame that is none of these, the test method or the task's body.
     */
    private fun tidy(frames: List<StackTraceElement>, thread: LoggingThread): List<StackTraceElement> {
        val dispatcher = when (thread) {
            LoggingThread.TEST -> emptyList()
            LoggingThread.EDT -> EDT_DISPATCH
            LoggingThread.BACKGROUND -> BACKGROUND_DISPATCH
        }

        return frames.dropLastWhile { frame ->
            val className = frame.className
            (RUNNER_PACKAGES + dispatcher).any { className.startsWith(it) } ||
                RUNNER_CLASSES.any { className == it || className.startsWith($$"$$it$") }
        }
    }

    private val LOGGING_PACKAGES = listOf("com.intellij.testFramework.TestLoggerFactory", "com.intellij.openapi.diagnostic.")

    private val RUNNER_PACKAGES = listOf(
        "jdk.internal.reflect.",
        "java.lang.reflect.",
        "junit.framework.",
        "org.junit.",
        "org.gradle.",
        "worker.org.gradle.",
        "jdk.proxy",
    )

    // Exact, so the fixtures' own classes in the same packages stay.
    private val RUNNER_CLASSES = listOf(
        "org.elixir_lang.junit.HeavyTestCase",
        "org.elixir_lang.junit.LightTestCase",
        "org.elixir_lang.junit.UnitTestCase",
        "org.elixir_lang.junit.logs.UnexpectedLogs",
        "com.intellij.testFramework.UsefulTestCase",
        "com.intellij.testFramework.TestLoggerKt",
        "com.intellij.testFramework.EdtTestUtil",
        "com.intellij.testFramework.HeavyPlatformTestCase",
        "com.intellij.testFramework.fixtures.BasePlatformTestCase",
    )

    private val EDT_DISPATCH = listOf(
        "java.awt.",
        "com.intellij.ide.IdeEventQueue",
        "com.intellij.openapi.application.TransactionGuardImpl",
        "com.intellij.openapi.application.impl.",
        "com.intellij.openapi.progress.impl.CoreProgressManager",
        "com.intellij.platform.locking.impl.",
        "com.intellij.util.concurrency.",
        "com.intellij.concurrency.",
    )

    private val BACKGROUND_DISPATCH = listOf(
        "java.lang.Thread",
        "com.intellij.openapi.application.impl.",
        "java.util.concurrent.",
        "kotlinx.coroutines.",
        "kotlin.coroutines.",
        "com.intellij.util.concurrency.",
        "com.intellij.concurrency.",
    )
}

private val RULE = "*".repeat(89)
private val TITLE = " UNEXPECTED LOGS ".let { title -> "*".repeat((89 - title.length) / 2).let { "$it$title$it" } }

/**
 * Each log is a suppressed [LoggedAt] carrying the frames of the code that logged it, which the IDE links to.
 *
 * Everything is in [toString], and there is no message: Gradle's exception formatter and the test-logger plugin print
 * only `toString()`, the frames and the causes, never suppressed exceptions, while an IDE prints the message and then
 * `toString()`, so a message would be shown twice there.
 */
class UnexpectedLogsError(logged: List<UnexpectedLogs.Logged>) : AssertionError() {
    private val grouped = logged.groupBy { Triple(it.header, it.frames, it.details) }.values

    // Ruled off, on lines of its own, so it can be found among the logs around it.
    private val text =
        listOf(
            javaClass.name,
            RULE,
            TITLE,
            RULE,
            "${logged.size} unexpected log(s) in ${logged.first().testName ?: "no test"}:",
            grouped.joinToString("\n") { same -> times(same.size) + same.first().header + same.first().detailsText("    ") },
            "A test that exercises a warning or an error should capture and validate it with",
            "expectWarnings(<logging class>::class.java, Regex(\"<message>\")) { ... }",
            "or expectErrors(...).",
            "Otherwise fix its cause.",
            "Platform noise belongs in ${IgnoredLogs::class.java.name}.",
            RULE,
        ).joinToString("\n")

    init {
        stackTrace = emptyArray()
        grouped.forEach { same -> addSuppressed(LoggedAt(same.first(), same.size)) }
    }

    override fun toString(): String = text
}

private fun times(count: Int): String = if (count > 1) "$count times: " else ""

/** One unexpected log, with the frames of the code that logged it as its stack. */
class LoggedAt(logged: UnexpectedLogs.Logged, times: Int) :
    Throwable(times(times) + logged.header, logged.informativeCause) {
    init {
        stackTrace = logged.frames.toTypedArray()
    }
}
