package org.elixir_lang.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/**
 * A [CapturingProcessHandler] for a command that runs a BEAM, stopped the way [ElixirProcessHandler] stops one: the
 * default kills the process tree only where the OS can, so a BEAM in a WSL distribution would be left running.
 */
internal open class ElixirCapturingProcessHandler(commandLine: GeneralCommandLine) : CapturingProcessHandler(commandLine) {
    private val targetPid: Long? by lazy { ProcessTargetPid.select(process, commandLine.exePath) }
    private val terminator = ElixirDoubleSignalTerminator { targetPid }

    override fun destroyProcessImpl() {
        interrupt { super.destroyProcessImpl() }
        // An interrupt can be sent and still not stop it, such as an IJent one accepted but never delivered, and
        // `runProcess` waits for the exit without a timeout once it has destroyed the process.
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { if (process.isAlive) super.destroyProcessImpl() },
            KILL_AFTER_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    protected open fun interrupt(fallback: () -> Unit) {
        terminator.performDoubleSignalTermination(process, fallback)
    }

    private companion object {
        const val KILL_AFTER_MS = 5_000L
    }
}
