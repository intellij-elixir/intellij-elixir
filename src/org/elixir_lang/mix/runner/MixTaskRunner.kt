package org.elixir_lang.mix.runner

import com.intellij.execution.CantRunException
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Key
import org.elixir_lang.Mix
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver

private val LOG = logger<MixTaskRunner>()

/**
 * The Mix tasks a Mix import runs, with progress, before any project exists. Each runs with the tool manager environment
 * it is given.
 */
object MixTaskRunner {
    /**
     * Updates hex and rebar, then fetches the dependencies, each with a modal progress dialog. A failure is logged, not
     * thrown.
     *
     * @param toolManagerEnvironment The tool manager environment the tasks run with
     * @param workingDirectory The directory to run the mix commands in
     * @param sdk The Elixir SDK to use
     */
    @JvmStatic
    fun runImportTasks(toolManagerEnvironment: ToolManagerEnvironmentResolver?, workingDirectory: String, sdk: Sdk) {
        runMixTask(toolManagerEnvironment, workingDirectory, sdk, "Updating hex", "local.hex", "--force") &&
            runMixTask(toolManagerEnvironment, workingDirectory, sdk, "Updating rebar", "local.rebar", "--force") &&
            runMixTask(toolManagerEnvironment, workingDirectory, sdk, "Fetching deps", "deps.get")
    }

    /** @return false once the SDK is reported as unable to run. */
    private fun runMixTask(
        toolManagerEnvironment: ToolManagerEnvironmentResolver?,
        workingDirectory: String,
        sdk: Sdk,
        title: String,
        task: String,
        vararg taskParameters: String
    ): Boolean {
        var canGoOn = true
        ProgressManager.getInstance().run(
            object : Task.Modal(null, title, true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        executeMixTask(indicator, toolManagerEnvironment, workingDirectory, sdk, task, *taskParameters)
                    } catch (_: CantRunException.CustomProcessedCantRunException) {
                        canGoOn = false
                    } catch (e: ExecutionException) {
                        LOG.warn("Mix task '$task' failed", e)
                    }
                }
            }
        )
        return canGoOn
    }

    private fun executeMixTask(
        indicator: ProgressIndicator,
        toolManagerEnvironment: ToolManagerEnvironmentResolver?,
        workingDirectory: String,
        sdk: Sdk,
        task: String,
        vararg taskParameters: String
    ) {
        indicator.isIndeterminate = true

        val commandLine = Mix.commandLine(emptyMap(), workingDirectory, sdk, toolManagerEnvironment = toolManagerEnvironment)
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
