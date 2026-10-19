package org.elixir_lang.junit.logs

import com.intellij.testFramework.LoggedErrorProcessor
import org.elixir_lang.junit.logs.UnexpectedLogs.Level
import java.util.EnumSet

/**
 * The base for any `LoggedErrorProcessor` a test installs. `LoggedErrorProcessor.executeWith` replaces the JVM-wide
 * processor rather than chaining to it, so a processor that did not pass what it does not handle on would switch
 * [UnexpectedLogs] off for as long as it is installed.
 */
open class GuardedLoggedErrorProcessor : LoggedErrorProcessor() {
    override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
        if (IgnoredLogs.isSilent(category, message, t)) return false

        UnexpectedLogs.record(Level.WARN, category, message, t)
        return true
    }

    override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
        if (IgnoredLogs.isSilent(category, message, t)) return Action.NONE

        UnexpectedLogs.record(Level.ERROR, category, message, t, details.asList())
        // Never rethrown: code that catches what its callees throw would swallow it. In fail mode the test's failure
        // prints it; the platform's stderr copy would repeat it down to the test runner's frames.
        return if (UnexpectedLogs.failing) LOG else LOG_AND_STDERR
    }

    private companion object {
        val LOG: Set<Action> = EnumSet.of(Action.LOG)
        val LOG_AND_STDERR: Set<Action> = EnumSet.of(Action.LOG, Action.STDERR)
    }
}
