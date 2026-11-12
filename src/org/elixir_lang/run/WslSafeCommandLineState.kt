package org.elixir_lang.run

import com.intellij.execution.CantRunException
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.platform.ide.progress.runWithModalProgressBlocking

abstract class WslSafeCommandLineState<T>(
    environment: ExecutionEnvironment,
    protected val configuration: T
) : CommandLineState(environment) where T : Configuration {

    protected open val modalProgressMessage: String = "Starting ${configuration.name}"

    @Throws(ExecutionException::class)
    override fun startProcess(): ProcessHandler {
        val commandLine =
            try {
                configuration.commandLine()
            } catch (e: CantRunException.CustomProcessedCantRunException) {
                // Already reported where it can be fixed; `handleExecutionException` would report it again.
                throw e
            } catch (e: ExecutionException) {
                handleExecutionException(e)
                throw e
            }

        try {
            // Create process in background thread to avoid EDT violations with WSL command line patching
            val process = runWithModalProgressBlocking(configuration.project, modalProgressMessage) {
                commandLine.createProcess()
            }

            return createProcessHandler(process, commandLine)
        } catch (e: ExecutionException) {
            handleExecutionException(e)
            throw e
        }
    }

    /**
     * Create the appropriate ProcessHandler for the started process.
     * Subclasses override this to return different ProcessHandler types.
     */
    protected abstract fun createProcessHandler(
        process: Process,
        commandLine: GeneralCommandLine
    ): ProcessHandler

    /**
     * Handle execution exceptions. Default implementation does nothing.
     * Subclasses can override to add custom error notification.
     */
    protected open fun handleExecutionException(e: ExecutionException) {
        // Default: do nothing
    }
}
