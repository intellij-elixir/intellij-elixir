package org.elixir_lang.model.psi.function

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.util.concurrency.AppExecutorUtil
import org.elixir_lang.PlatformTestCase
import java.util.concurrent.TimeUnit

class ReachableTest : PlatformTestCase() {
    fun testTwoNodesThatNameEachOtherAreBothReached() {
        val edges = mapOf("a" to listOf("b"), "b" to listOf("a"))

        assertEquals(setOf("a", "b"), bounded { reachable("a") { edges.getValue(it) } })
    }

    fun testANodeWithNoEdgesReachesItself() = assertEquals(setOf("a"), reachable("a") { emptyList() })

    fun testEdgesAreFollowedPastTheFirst() {
        val edges = mapOf("a" to listOf("b"), "b" to listOf("c"), "c" to emptyList())

        assertEquals(setOf("a", "b", "c"), reachable("a") { edges.getValue(it) })
    }

    /** [compute], cancelled if it runs past a few seconds. */
    private fun <T> bounded(compute: () -> T): T {
        val indicator = ProgressIndicatorBase()
        val cancel = AppExecutorUtil.getAppScheduledExecutorService().schedule({ indicator.cancel() }, 5, TimeUnit.SECONDS)

        try {
            return ProgressManager.getInstance().runProcess(compute, indicator)
        } finally {
            cancel.cancel(false)
        }
    }
}
