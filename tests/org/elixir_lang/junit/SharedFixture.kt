package org.elixir_lang.junit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ThrowableRunnable
import com.intellij.util.ui.EDT
import junit.framework.Test
import junit.framework.TestCase
import junit.framework.TestResult
import junit.framework.TestSuite
import org.elixir_lang.junit.logs.UnexpectedLogs
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter
import org.junit.runner.manipulation.Filterable
import org.junit.runner.manipulation.NoTestsRemainException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlin.concurrent.thread

/** The test case that sets up a [SharedFixture] and checks its cases. */
interface SharedFixtureHost<T : TestCase> {
    /** Sets up, runs [serve], and tears down: the host's own `runBare`. */
    @Throws(Throwable::class)
    fun runShared(serve: ThrowableRunnable<Throwable>)

    @Throws(Throwable::class)
    fun check(case: T)
}

/**
 * Cases share one fixture: the first selected case sets it up on a host thread, each case checks itself there during
 * its own run, so its failure and time are its own, and the last selected case tears it down, as does the [suite]
 * when it ends. The first case's time includes the set-up and the last case's the tear-down.
 */
class SharedFixture<T : TestCase>(private val newHost: Supplier<out SharedFixtureHost<T>>) {
    private val cases: MutableSet<Test> = Collections.newSetFromMap(IdentityHashMap())
    private var remaining = 0
    private var host: Host<T>? = null
    private var deadIn: String? = null

    fun add(case: T) {
        cases += case
    }

    /** A suite a filter can narrow without losing the fixture's tear-down. */
    fun suite(name: String): TestSuite = Suite(name)

    /** Checks [case] on the host, from the case's own run. */
    fun check(case: T) {
        val failure = runCatching { checkOnHost(case) }.exceptionOrNull()
        val tornDown = if (--remaining == 0) runCatching { close() }.exceptionOrNull() else null

        if (failure != null) {
            tornDown?.let(failure::addSuppressed)
            throw failure
        }
        tornDown?.let { throw it }
    }

    private fun checkOnHost(case: T) {
        deadIn?.let { throw AssertionError("Not checked: the shared fixture ended in $it") }
        val host = host ?: Host(newHost.get(), case.javaClass.name).also { host = it }

        host.check(case)?.let { ended ->
            deadIn = case.name
            throw ended
        }
    }

    private fun close() {
        val host = host ?: return
        this.host = null
        val ended = host.end()

        if (deadIn == null) ended?.let { throw it }
    }

    private class Request<T>(val case: T?) {
        val checked = CompletableFuture<Throwable?>()
    }

    private class Host<T : TestCase>(host: SharedFixtureHost<T>, name: String) {
        private val requests = LinkedBlockingQueue<Request<T>>()
        private val ended = CompletableFuture<Throwable?>()

        init {
            thread(name = "shared fixture: $name", isDaemon = true) {
                ended.complete(runCatching { host.runShared { serve(host) } }.exceptionOrNull())
            }
        }

        private fun serve(host: SharedFixtureHost<T>) {
            while (true) {
                val request = nextRequest()
                val case = request.case ?: return
                request.checked.complete(runCatching { host.check(case) }.exceptionOrNull())
            }
        }

        // A platform host serves on the EDT, which would otherwise hold everything posted to it until tear-down.
        private fun nextRequest(): Request<T> {
            while (true) {
                dispatchEdtEvents()
                requests.poll(10, TimeUnit.MILLISECONDS)?.let { return it }
            }
        }

        // A mock-application host runs with write access, where the platform refuses to dispatch.
        private fun dispatchEdtEvents() {
            if (EDT.isCurrentThreadEdt() && !ApplicationManager.getApplication().isWriteAccessAllowed) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
        }

        /** Throws what [case]'s check threw; returns what ended the host if it ended first. */
        fun check(case: T): Throwable? {
            val request = Request(case)
            requests.put(request)
            CompletableFuture.anyOf(request.checked, ended).join()

            if (!request.checked.isDone) {
                return ended.get() ?: IllegalStateException("The shared fixture ended before checking ${case.name}")
            }
            request.checked.get()?.let { throw it }

            return null
        }

        /** Tears down, returning what set-up or tear-down threw. */
        fun end(): Throwable? {
            requests.put(Request(null))

            return ended.join()
        }
    }

    // JUnit 4's `JUnit38ClassRunner` narrows a `Filterable` suite itself, rather than copying the tests it keeps into a
    // plain `TestSuite`, which would drop `run`.
    private inner class Suite(name: String) : TestSuite(name), Filterable {
        private val tests = mutableListOf<Test>()

        override fun addTest(test: Test) {
            tests += test
        }

        override fun testCount(): Int = tests.size

        override fun testAt(index: Int): Test = tests[index]

        override fun tests(): java.util.Enumeration<Test> = Collections.enumeration(tests)

        override fun countTestCases(): Int = tests.sumOf { it.countTestCases() }

        override fun filter(filter: Filter) {
            tests.retainAll { test ->
                // A nested suite's description would need its children for a filter to match any of them.
                check(test is TestCase) { "A shared-fixture suite holds only test cases, not $test" }
                filter.shouldRun(Description.createTestDescription(test.javaClass, test.name))
            }
            if (tests.isEmpty()) throw NoTestsRemainException()
        }

        override fun run(result: TestResult) {
            remaining = tests.count { it in cases }
            deadIn = null

            try {
                for (test in tests) {
                    if (result.shouldStop()) break
                    runTest(test, result)
                }
            } finally {
                close()
            }
        }
    }

    companion object {
        /**
         * A host's `runBare`: [run], the case's own, when it has no [fixture], as the host and a test method have not;
         * otherwise [case] checked on the fixture's host, failing for the logs during its check.
         */
        @JvmStatic
        @Throws(Throwable::class)
        fun <T : TestCase> runCase(fixture: SharedFixture<T>?, case: T, run: ThrowableRunnable<Throwable>) {
            if (fixture == null) run.run() else UnexpectedLogs.failOnUnexpectedLogs { fixture.check(case) }
        }
    }
}
