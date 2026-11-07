package org.elixir_lang.mix.runner

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Key
import org.elixir_lang.Mix
import java.util.concurrent.Callable

private val LOG = logger<MixTaskRunner>()

/** The Mix tasks a Mix import runs, with progress, before any project exists. */
object MixTaskRunner {
    /**
     * Updates hex and rebar, then fetches the dependencies, each with a modal progress dialog. A failure is logged, not
     * thrown.
     *
     * @param workingDirectory The directory to run the mix commands in
     * @param sdk The Elixir SDK to use
     */
    @JvmStatic
    fun runImportTasks(workingDirectory: String, sdk: Sdk) {
        runMixTask(workingDirectory, sdk, "Updating hex", "local.hex", "--force")
        runMixTask(workingDirectory, sdk, "Updating rebar", "local.rebar", "--force")
        runMixTask(workingDirectory, sdk, "Fetching deps", "deps.get")
    }

    private fun runMixTask(
        workingDirectory: String,
        sdk: Sdk,
        title: String,
        task: String,
        vararg taskParameters: String
    ) {
        ProgressManager.getInstance().run(
            object : Task.Modal(null, title, true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        executeMixTask(indicator, workingDirectory, sdk, task, *taskParameters)
                    } catch (e: ExecutionException) {
                        LOG.warn("Mix task '$task' failed", e)
                    }
                }
            }
        )
    }

    private fun executeMixTask(
        indicator: ProgressIndicator,
        workingDirectory: String,
        sdk: Sdk,
        task: String,
        vararg taskParameters: String
    ) {
        indicator.isIndeterminate = true

        // Mix.commandLine() -> argsOrThrow() -> requireErlangSdkOrNotifyAndThrow()
        // -> findErlangSdkByHomePath() asserts a read lock; acquire one here.
        val commandLine = ReadAction.nonBlocking(Callable {
            Mix.commandLine(emptyMap(), workingDirectory, sdk)
        }).executeSynchronously()
        commandLine.addParameter(task)
        commandLine.addParameters(*taskParameters)

        // Use ColoredProcessHandler to automatically strip ANSI escape codes
        val handler = ProcessHandlerFactory.getInstance()
            .createColoredProcessHandler(commandLine)

        handler.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    indicator.text2 = event.text
                }
            }
        )

        ProcessTerminatedListener.attach(handler)
        handler.startNotify()
        handler.waitFor()

        indicator.text2 = "Refreshing"
    }
}
