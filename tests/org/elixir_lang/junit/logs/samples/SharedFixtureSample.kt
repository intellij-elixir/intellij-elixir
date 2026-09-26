package org.elixir_lang.junit.logs.samples

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.ThrowableRunnable
import junit.framework.Test
import org.elixir_lang.junit.LightTestCase
import org.elixir_lang.junit.SharedFixture
import org.elixir_lang.junit.SharedFixtureHost
import org.elixir_lang.junit.logs.UnexpectedLogs
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Run only by `SharedFixtureTest`, in a nested launcher; the `test` task excludes this package. */
@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class SharedFixtureSample private constructor(private val fixture: SharedFixture<SharedFixtureSample>?, case: String?) :
    LightTestCase(), SharedFixtureHost<SharedFixtureSample> {
    init {
        name = case ?: "shared fixture"
        fixture?.add(this)
    }

    override fun setUp() {
        super.setUp()
        setUps.incrementAndGet()
        if (failSetUp) throw IllegalStateException(SET_UP_FAILURE)
    }

    override fun tearDown() {
        tearDowns.incrementAndGet()
        super.tearDown()
    }

    override fun runShared(serve: ThrowableRunnable<Throwable>) {
        runBare(serve)
    }

    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        if (fixture == null) {
            super.runBare(testRunnable)
        } else {
            UnexpectedLogs.failOnUnexpectedLogs { fixture.check(this) }
        }
    }

    override fun check(case: SharedFixtureSample) {
        checked += case.name
        when (case.name) {
            "logs" -> logger<SharedFixtureSample>().warn(SAMPLE_WARNING)
            "fails" -> fail(SAMPLE_FAILURE)
            "errs" -> logger<SharedFixtureSample>().error(SAMPLE_ERROR)
            "posts" -> {
                posted = false
                ApplicationManager.getApplication().invokeLater { posted = true }
            }
            "sleeps" -> Thread.sleep(SLEEP_MILLIS)
            "c" -> assertTrue("what an earlier case posted to the EDT has run", posted)
        }
    }

    companion object {
        const val SLEEP_MILLIS = 1_000L
        const val SET_UP_FAILURE = "set-up failure"
        val CASES = listOf("a", "logs", "fails", "errs", "posts", "sleeps", "c", "d")

        val setUps = AtomicInteger()
        val tearDowns = AtomicInteger()
        val checked = CopyOnWriteArrayList<String>()

        @Volatile
        var failSetUp = false

        @Volatile
        private var posted = false

        @JvmStatic
        fun suite(): Test {
            setUps.set(0)
            tearDowns.set(0)
            checked.clear()
            val fixture = SharedFixture { SharedFixtureSample(null, null) }

            return fixture.suite(SharedFixtureSample::class.java.name).apply {
                for (case in CASES) addTest(SharedFixtureSample(fixture, case))
            }
        }
    }
}
