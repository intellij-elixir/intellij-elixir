package org.elixir_lang.expander

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.AbstractProgressIndicatorBase
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
