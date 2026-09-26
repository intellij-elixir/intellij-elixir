package org.elixir_lang.junit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.job
import org.elixir_lang.sdk.SdkVersionWatchService
import org.elixir_lang.sdk.SdkVersionsFiller
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.util.ElixirAppCoroutineService
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier

private val LOG = logger<SdkVersionsStoreReset>()

/**
 * Empties [SdkVersionsStore] before every test. The store is application-wide, and a home one test leaves in it is
 * read, and its version files watched, by whichever tests share the JVM after it.
 *
 * Waits for the fills and rewatches earlier tests launched first: one finishing after the clear would write its home
 * into the next test's store, or rebuild the watch just stopped.
 */
class SdkVersionsStoreReset : TestExecutionListener {
    override fun executionStarted(testIdentifier: TestIdentifier) {
        if (!testIdentifier.isTest) return

        val store = ApplicationManager.getApplication()?.getServiceIfCreated(SdkVersionsStore::class.java) ?: return
        try {
            awaitIdle(testIdentifier)
        } finally {
            store.clearForTests()
            SdkVersionWatchService.stopWatchingForTests()
        }
    }

    // A listener cannot fail the test, so a store still busy is logged: the unexpected-log guard fails the build on it.
    private fun awaitIdle(testIdentifier: TestIdentifier) {
        val deadline = System.currentTimeMillis() + IDLE_TIMEOUT_MILLIS
        while (!SdkVersionWatchService.isIdleForTests() || cancelledScopesStillFinishing()) {
            if (System.currentTimeMillis() > deadline) {
                LOG.error(
                    "SDK version fills or rewatches were still running ${IDLE_TIMEOUT_MILLIS / 1000} s after the test " +
                        "before ${testIdentifier.displayName} finished; cleared anyway (watch: " +
                        "${SdkVersionWatchService.describeForTests()}; ${SdkVersionsFiller.describeForTests()})"
                )
                return
            }
            Thread.sleep(10)
        }
    }

    // `isIdleForTests` sees only the live installation. A test's tear-down cancels its own, but cancelling does not stop
    // a fill blocked on I/O, which still records its home when it returns.
    // Only direct children, each a watch installation's scope: cancelled jobs nested in a live scope are not awaited.
    private fun cancelledScopesStillFinishing(): Boolean =
        ApplicationManager.getApplication()
            ?.getServiceIfCreated(ElixirAppCoroutineService::class.java)
            ?.scope
            ?.coroutineContext
            ?.job
            ?.children
            ?.any { it.isCancelled && !it.isCompleted }
            ?: false

    private companion object {
        const val IDLE_TIMEOUT_MILLIS = 10_000L
    }
}
