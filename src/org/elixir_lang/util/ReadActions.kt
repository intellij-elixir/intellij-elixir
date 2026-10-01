package org.elixir_lang.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.util.ui.EDT
import java.util.concurrent.Callable

/**
 * Read-action helper for blocking callers whose thread context varies.
 *
 * Mirrors [WriteActions] for the read side: use it for model reads in non-suspending code that can
 * be reached both from a caller that already holds the read lock and from a bare pooled thread, such as
 * [com.intellij.openapi.projectRoots.SdkType.setupSdkPaths], or the EDT.
 *
 * Code that always runs in one known context should keep taking the lock explicitly - prefer a
 * `@RequiresReadLock` contract on the caller where the caller can honour it.
 */
object ReadActions {
    /**
     * Runs [action] under a read action, or directly when the lock is already held.
     *
     * The guard is load-bearing, not an optimisation: when
     * [com.intellij.openapi.application.NonBlockingReadAction.executeSynchronously] cannot start the
     * computation immediately it waits in `blockUntilWriteActionIsDone`, which asserts that the
     * calling thread holds *no* read access. On an EDT without a lock it fails `assertIsNonDispatchThread`, so the EDT
     * blocks instead; a background thread blocking would hold writers up.
     */
    fun <T> compute(action: () -> T): T =
        when {
            ApplicationManager.getApplication().isReadAccessAllowed -> action()
            EDT.isCurrentThreadEdt() -> ReadAction.computeBlocking<T, RuntimeException> { action() }
            else -> ReadAction.nonBlocking(Callable { action() }).executeSynchronously()
        }
}
