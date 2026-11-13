package org.elixir_lang.util

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.junit.onPooledThread
import java.io.IOException

class ReadActionsTest : BasePlatformTestCase() {
    fun testAPooledThreadGetsTheCheckedExceptionTheActionThrew() {
        val thrown = IOException("thrown inside the read action")

        val failure = onPooledThread { runCatching { ReadActions.compute { throw thrown } }.exceptionOrNull() }

        assertSame(thrown, failure)
    }
}
