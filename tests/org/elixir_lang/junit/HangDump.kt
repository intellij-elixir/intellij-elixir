package org.elixir_lang.junit

import com.intellij.diagnostic.ThreadDumper
import com.intellij.diagnostic.dumpCoroutines

/** What a test that gave up waiting reports: the JVM's coroutines and threads. */
fun hangDump(): String =
    "Coroutines:\n${dumpCoroutines() ?: "(coroutine debug probes are not installed)"}\n\n" +
        "Threads:\n${ThreadDumper.dumpThreadsToString()}"
