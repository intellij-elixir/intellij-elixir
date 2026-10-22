package org.elixir_lang.expander

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.AbstractProgressIndicatorBase
import junit.framework.TestCase
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Cancelled from its [n]th check on the thread that made it. */
internal class CancelAt(private val n: Int) : AbstractProgressIndicatorBase() {
    private val owner = Thread.currentThread()

    var checks = 0
        private set

    override fun isCanceled(): Boolean = checks >= n

    override fun checkCanceled() {
        if (Thread.currentThread() === owner) checks++

        super.checkCanceled()
    }
}

/**
 * Cancelled from the [n]th check that [walk], a method of the class [owner], makes itself on the thread that made it:
 * the frame that calls `ProgressManager.checkCanceled`. A local function's method ends in `$` and its name.
 */
internal class CancelIn(private val owner: String, private val walk: String, private val n: Int) :
    AbstractProgressIndicatorBase() {
    private val thread = Thread.currentThread()

    var checks = 0
        private set

    override fun isCanceled(): Boolean = checks >= n

    override fun checkCanceled() {
        if (Thread.currentThread() === thread && checker()?.let { isWalk(it, owner, walk) } == true) checks++

        super.checkCanceled()
    }
}

/** Never cancelled: counts each check on the thread that made it by the method that makes it. */
internal class ChecksBy : AbstractProgressIndicatorBase() {
    private val thread = Thread.currentThread()
    private val frames = mutableListOf<StackWalker.StackFrame>()

    /** The checks [walk], a method of the class [owner], made. */
    fun count(owner: String, walk: String): Int = frames.count { isWalk(it, owner, walk) }

    override fun checkCanceled() {
        if (Thread.currentThread() === thread) checker()?.let(frames::add)

        super.checkCanceled()
    }
}

/**
 * [block] is cancelled at the [n]th check [walk], a method of the class [owner], makes; [ended] says how it ended where
 * it isn't.
 */
internal fun assertCancelledIn(owner: String, walk: String, n: Int, ended: () -> String = { "" }, block: () -> Unit) {
    val indicator = CancelIn(owner, walk, n)

    try {
        whileCheckingIndicators { ProgressManager.getInstance().runProcess(block, indicator) }
        TestCase.fail("no cancel at $walk's check $n, of ${indicator.checks}; ${ended()}")
    } catch (_: ProcessCanceledException) {
    }
}

/** The frame that calls `ProgressManager.checkCanceled`: the first that isn't the platform's, the JDK's or Kotlin's. */
private fun checker(): StackWalker.StackFrame? =
    StackWalker.getInstance().walk { frames ->
        frames.filter { frame -> PLATFORM.none { frame.className.startsWith(it) } }.findFirst().orElse(null)
    }

private fun isWalk(frame: StackWalker.StackFrame, owner: String, walk: String): Boolean =
    frame.className == owner && (frame.methodName == walk || frame.methodName.endsWith($$"$$$walk"))

/** The frames between a walk and its check, this file's included. */
private val PLATFORM = listOf(
    "com.intellij.",
    "java.",
    "jdk.",
    "kotlin.",
    CancelIn::class.java.name,
    ChecksBy::class.java.name,
    "org.elixir_lang.expander.CancellingKt",
)

/**
 * Runs [block] while another thread runs under a cancelled indicator: only then does `ProgressManager.checkCanceled`
 * call each thread's indicator.
 */
internal fun whileCheckingIndicators(block: () -> Unit) {
    val running = CountDownLatch(1)
    val done = CountDownLatch(1)
    var failure: Throwable? = null
    val other = thread {
        val cancelled = EmptyProgressIndicator()

        try {
            ProgressManager.getInstance().runProcess(
                {
                    cancelled.cancel()
                    running.countDown()
                    done.await()
                },
                cancelled,
            )
        } catch (_: ProcessCanceledException) {
        } catch (e: Throwable) {
            failure = e
        } finally {
            running.countDown()
        }
    }
    running.await()
    failure?.let { throw it }

    try {
        block()
    } finally {
        done.countDown()
        other.join()
    }
}
