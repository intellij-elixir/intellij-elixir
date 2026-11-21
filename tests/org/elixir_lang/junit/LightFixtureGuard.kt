package org.elixir_lang.junit

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.testFramework.StartupActivityTestUtil
import com.intellij.testFramework.common.runAll
import com.intellij.util.ThrowableRunnable
import org.elixir_lang.junit.logs.UnexpectedLogs
import org.elixir_lang.mix.sync.MixSyncTestHelpers

/**
 * What a light test runs around its fixture, whichever platform base it extends: it fails for any warning, error or
 * uncaught exception it does not expect.
 *
 * Its module and project are shared with every later light test in the fork, so what a test adds to the part
 * [SharedModuleState] covers is removed after the subclass's tear-down, before the fixture and its
 * `testRootDisposable` are disposed, and the test fails for anything that still differs.
 *
 * Mix deps syncs queued by the time the state is recorded or restored, by the test or the previous test's fixture
 * tear-down, are let finish first.
 */
internal class LightFixtureGuard {
    private var sharedStateBefore: SharedModuleState? = null

    fun runBare(runBare: ThrowableRunnable<Throwable>) = UnexpectedLogs.failOnUnexpectedLogs(runBare)

    /** Runs after the fixture's set-up; [resetSharedState] runs before the shared state is recorded. */
    fun setUp(project: Project, module: Module, resetSharedState: () -> Unit = {}) {
        // A light project's startup activities outlive the test that opens it, and one that reaches the project after
        // that test closes it caches its service's failure for the rest of the fork.
        @Suppress("DEPRECATION")
        StartupActivityTestUtil.waitForProjectActivitiesToComplete(project)
        // The fixture's tear-down deletes the previous test's files, which queues syncs after the restore.
        MixSyncTestHelpers.awaitMixDepsSyncs(project)
        resetSharedState()
        sharedStateBefore = SharedModuleState.of(module)
    }

    /** Runs in place of the fixture's tear-down, [fixtureTearDown]. */
    fun tearDown(project: Project, module: Module, testName: String, fixtureTearDown: () -> Unit) {
        runAll(
            { MixSyncTestHelpers.awaitMixDepsSyncs(project) },
            { restoreSharedState(module, testName) },
            { fixtureTearDown() },
        )
    }

    private fun restoreSharedState(module: Module, testName: String) {
        val before = sharedStateBefore ?: return
        sharedStateBefore = null
        val differences = before.differences(SharedModuleState.restore(module, before))
        if (differences.isNotEmpty()) {
            throw AssertionError("$testName left the shared light module changed:\n" + differences.joinToString("\n"))
        }
    }
}
