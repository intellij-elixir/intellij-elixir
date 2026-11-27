package org.elixir_lang.junit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * Runs [block] on a pooled thread and waits for its result.
 *
 * Never wait with `Future.get` on the EDT: a background write action (the indexing scanner's, after a roots change)
 * can be pending, a read action on the pooled thread waits for it, and it needs the write-intent lock the EDT holds.
 * [PlatformTestUtil.waitForFuture] releases that lock while it dispatches events.
 */
fun <T> onPooledThread(timeoutMillis: Long = TimeUnit.MINUTES.toMillis(10), block: () -> T): T =
    PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(Callable(block)), timeoutMillis)
