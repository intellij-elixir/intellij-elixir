package org.elixir_lang.junit

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.ex.ApplicationManagerEx
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Future

/** A background write action started on a pooled thread, returned once it is pending. */
internal fun pendingBackgroundWrite(): Future<*> {
    val application = ApplicationManagerEx.getApplicationEx()
    val writing = application.executeOnPooledThread { runBlocking { backgroundWriteAction { } } }
    val deadline = System.currentTimeMillis() + 30_000
    // Polled without dispatching, which would let the write run.
    while (!application.isWriteActionPending) {
        assertTrue("no background write action came", System.currentTimeMillis() < deadline)
        Thread.sleep(1)
    }
    return writing
}
