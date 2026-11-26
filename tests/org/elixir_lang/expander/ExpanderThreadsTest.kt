package org.elixir_lang.expander

import com.intellij.openapi.application.ApplicationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.elixir_lang.FIXED_STACK
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.expander.ExpanderSourceRows.read
import org.elixir_lang.expander.ExpanderThreads.Companion.ASSERTION_KEY
import org.elixir_lang.onAStack
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The expander's threads: a stack set by the plugin, a hop that a pending write doesn't block and that the caller's job
 * or indicator cancels, and the one answer to whether the current thread may expand.
 */
class ExpanderThreadsTest : PlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    /** A recursion eight times as deep as a thread of [FIXED_STACK] bytes holds completes through the hop. */
    fun testAPoolThreadHoldsARecursionEightTimesWhatAMebibyteHolds() {
        val held = onAStack(FIXED_STACK) { overflowDepth() }!!.getOrThrow()

        assertEquals(8 * held, read { ExpanderThreads.hop { nested(8 * held) } })
    }

    /**
     * A hop inside a hop, the inner asked once a write is pending on the EDT: the inner hop reads while the write waits,
     * and the write runs after.
     */
    fun testANestedHopFinishesWhileAWriteIsPending() {
        val pending = CountDownLatch(1)
        ApplicationManager.getApplication().addApplicationListener(
            object : ApplicationListener {
                override fun beforeWriteActionStart(action: Any) {
                    pending.countDown()
                }
            },
            testRootDisposable,
        )
        val order = Collections.synchronizedList(ArrayList<String>())
        val outer = CountDownLatch(1)
        val reader = AppExecutorUtil.getAppExecutorService().submit {
            ReadAction.computeBlocking<Unit, Throwable> {
                ExpanderThreads.hop {
                    outer.countDown()
                    assertTrue("a write is pending", pending.await(BOUND_SECONDS, TimeUnit.SECONDS))
                    ExpanderThreads.hop { order += "inner read" }
                }
            }
        }
        assertTrue("the outer hop runs", outer.await(BOUND_SECONDS, TimeUnit.SECONDS))
        val wrote = CompletableFuture<Unit>()
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction { order += "write" }
            wrote.complete(Unit)
        }

        reader.get(BOUND_SECONDS, TimeUnit.SECONDS)
        wrote.get(BOUND_SECONDS, TimeUnit.SECONDS)

        assertEquals(listOf("inner read", "write"), order)
    }

    /** A caller with a job, cancelled while the hop's block checks for cancellation, ends the hop cancelled. */
    fun testTheCallersJobCancelsTheChild() {
        val checking = CountDownLatch(1)
        val thrown = CompletableFuture<Throwable?>()

        val caller = CoroutineScope(Dispatchers.IO).launch { thrown.complete(runCatching { ExpanderThreads.hop { checkingForABound(checking) } }.exceptionOrNull()) }
        assertTrue("the block runs", checking.await(BOUND_SECONDS, TimeUnit.SECONDS))

        caller.cancel()

        assertInstanceOf(thrown.get(BOUND_SECONDS, TimeUnit.SECONDS), ProcessCanceledException::class.java)
    }

    /** A caller under an indicator, cancelled while the hop's block checks for cancellation, ends the hop cancelled. */
    fun testTheCallersIndicatorCancelsTheChild() {
        val checking = CountDownLatch(1)
        val indicator = EmptyProgressIndicator()
        val caller = AppExecutorUtil.getAppExecutorService().submit(
            Callable {
                runCatching {
                    ProgressManager.getInstance().runProcess(Computable { ExpanderThreads.hop { checkingForABound(checking) } }, indicator)
                }.exceptionOrNull()
            },
        )
        assertTrue("the block runs", checking.await(BOUND_SECONDS, TimeUnit.SECONDS))

        indicator.cancel()

        assertInstanceOf(caller.get(BOUND_SECONDS, TimeUnit.SECONDS), ProcessCanceledException::class.java)
    }

    /** On the EDT in unit-test mode, with no job and no indicator, the hop gives its block's value. */
    fun testAnOrphanCallerOnTheEdtRuns() {
        val value = runInEdtAndGet {
            assertNull(ProgressManager.getGlobalProgressIndicator())
            ExpanderThreads.hop { "ran" }
        }

        assertEquals("ran", value)
    }

    /** Under the key that makes `runBlockingCancellable` refuse the EDT in tests, the EDT may not expand. */
    fun testMayExpandIsFalseOnTheEdtUnderTheAssertionKey() {
        Registry.get(ASSERTION_KEY).setValue(true, testRootDisposable)

        assertFalse(runInEdtAndGet { ExpanderThreads.mayExpand() })
    }

    /** Off the EDT, a thread may expand, under the key too. */
    fun testMayExpandIsTrueOffTheEdt() {
        Registry.get(ASSERTION_KEY).setValue(true, testRootDisposable)

        assertTrue(ExpanderThreads.mayExpand())
    }

    /** On the EDT in unit-test mode without the key, as the platform lets `runBlockingCancellable` run there, it may expand. */
    fun testMayExpandIsTrueOnTheEdtWithoutTheKey() {
        assertTrue(runInEdtAndGet { ExpanderThreads.mayExpand() })
    }

    private companion object {
        const val BOUND_SECONDS = 30L

        /** How deep [dive] gets before the thread's stack overflows. */
        fun overflowDepth(): Int {
            val depth = IntArray(1)

            try {
                dive(depth)
            } catch (_: StackOverflowError) {
            }

            return depth[0]
        }

        private fun dive(depth: IntArray) {
            depth[0]++
            dive(depth)
        }

        /** [n], counted one frame at a time, as deep as [dive] goes. */
        fun nested(n: Int): Int = if (n == 0) 0 else nested(n - 1) + 1

        /** Checks for cancellation until twice [BOUND_SECONDS] have passed, so a cancel that never comes ends the thread too. */
        fun checkingForABound(checking: CountDownLatch) {
            checking.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2 * BOUND_SECONDS)

            while (System.nanoTime() < deadline) ProgressManager.checkCanceled()
        }
    }
}
