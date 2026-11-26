package org.elixir_lang.expander

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.ui.EDT
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The threads the expander runs on, each with a stack of [STACK] bytes whatever the JVM's `-Xss`, so how deep a
 * recursion in the code it runs goes before it stops doesn't depend on the thread that asked.
 */
@Service(Service.Level.APP)
internal class ExpanderThreads : Disposable {
    // Unbounded: a caller waits on its child, and `ProjectModules.compute`'s `inProgress` bounds how deep they nest.
    private val pool = ThreadPoolExecutor(0, Int.MAX_VALUE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
        Thread(null, runnable, THREAD_NAME, STACK).apply { isDaemon = true }
    }

    private val dispatcher = pool.asCoroutineDispatcher()

    override fun dispose() {
        pool.shutdownNow()
    }

    companion object {
        /** Each thread's stack: the deepest construct's bytes per level, twice, for every level the interpreter allows. */
        private const val STACK = 1L shl 30

        const val KEEP_ALIVE_SECONDS = 10L

        const val THREAD_NAME = "Elixir expander"

        const val ASSERTION_KEY = "ide.run.blocking.cancellable.assert.in.tests"

        /**
         * [block]'s value, run on one of the expander's threads in a read action. The caller's read access is shared with
         * it, so a nested hop runs while a write waits, and the caller's job or indicator cancels it.
         */
        fun <T> hop(block: () -> T): T {
            val threads = service<ExpanderThreads>()

            return runBlockingMaybeCancellable {
                withContext(threads.dispatcher) { ReadAction.computeBlocking<T, Throwable> { block() } }
            }
        }

        /**
         * Whether this thread may wait on [hop]: false where `runBlockingCancellable` would log an error, on the EDT
         * outside unit-test mode, or in it under the key the platform's own tests set to make it log there too.
         */
        fun mayExpand(): Boolean =
            !EDT.isCurrentThreadEdt() || ApplicationManager.getApplication().isUnitTestMode && !Registry.`is`(ASSERTION_KEY, false)
    }
}
