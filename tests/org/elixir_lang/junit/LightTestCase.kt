package org.elixir_lang.junit

import com.intellij.testFramework.StartupActivityTestUtil
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable
import org.elixir_lang.junit.logs.UnexpectedLogs
import org.elixir_lang.mix.sync.MixSyncTestHelpers

/**
 * A [BasePlatformTestCase] failed for any warning, error or uncaught exception it does not expect.
 *
 * Its module and project are shared with every later light test in the fork, so what a test adds to the part
 * [SharedModuleState] covers is removed after the subclass's tear-down, before the fixture and its
 * `testRootDisposable` are disposed, and the test fails for anything that still differs.
 *
 * Mix deps syncs queued by the time the state is recorded or restored, by the test or the previous test's fixture
 * tear-down, are let finish first.
 */
abstract class LightTestCase : BasePlatformTestCase() {
    private var sharedStateBefore: SharedModuleState? = null

    @Throws(Throwable::class)
    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        UnexpectedLogs.failOnUnexpectedLogs { super.runBare(testRunnable) }
    }

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        // A light project's startup activities outlive the test that opens it, and one that reaches the project after
        // that test closes it caches its service's failure for the rest of the fork.
        @Suppress("DEPRECATION")
        StartupActivityTestUtil.waitForProjectActivitiesToComplete(project)
        // The fixture's tear-down deletes the previous test's files, which queues syncs after the restore.
        MixSyncTestHelpers.awaitMixDepsSyncs(project)
        resetSharedState()
        sharedStateBefore = SharedModuleState.of(module)
    }

    /** For a test that needs the shared state emptied, not merely restored: runs before it is recorded. */
    protected open fun resetSharedState() {}

    @Throws(Exception::class)
    override fun tearDown() {
        runAll(
            { MixSyncTestHelpers.awaitMixDepsSyncs(project) },
            { restoreSharedState() },
            { super.tearDown() },
        )
    }

    private fun restoreSharedState() {
        val before = sharedStateBefore ?: return
        sharedStateBefore = null
        val differences = before.differences(SharedModuleState.restore(module, before))
        if (differences.isNotEmpty()) {
            throw AssertionError("$name left the shared light module changed:\n" + differences.joinToString("\n"))
        }
    }
}
