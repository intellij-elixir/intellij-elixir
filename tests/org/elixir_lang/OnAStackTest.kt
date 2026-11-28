package org.elixir_lang

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.junit.pendingBackgroundWrite

class OnAStackTest : PlatformTestCase() {
    /**
     * A background write pending when the EDT starts to wait needs the EDT's write-intent lock, and from 2026.3 a
     * blocking read on the waited-for thread yields to it, so the wait has to give the lock up.
     */
    fun testAWaitOnTheEdtLetsAPendingWriteThrough() {
        val writing = pendingBackgroundWrite()

        val read = onAStack(FIXED_STACK, 5_000, cancel = ::failTheStuckRead) {
            ReadAction.computeBlocking<Unit, Throwable> {}
        }

        read!!.getOrThrow()
        PlatformTestUtil.waitForFuture(writing, 10_000)
    }

    private fun failTheStuckRead() {
        // Lets the stuck write and read finish before tearDown.
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        fail("the read was still waiting for the pending write")
    }
}
