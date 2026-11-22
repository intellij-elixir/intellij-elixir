package org.elixir_lang.junit

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable

/** A [BasePlatformTestCase] under a [LightFixtureGuard]. */
abstract class LightTestCase : BasePlatformTestCase() {
    private val guard = LightFixtureGuard()

    @Throws(Throwable::class)
    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        guard.runBare { super.runBare(testRunnable) }
    }

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        guard.setUp(project, module, ::resetSharedState)
    }

    /** For a test that needs the shared state emptied, not merely restored: runs before it is recorded. */
    protected open fun resetSharedState() {}

    @Throws(Exception::class)
    override fun tearDown() {
        guard.tearDown(project, module, name) { super.tearDown() }
    }
}
