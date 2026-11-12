package org.elixir_lang.dialyzer.service

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.ParametersList
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Key
import org.elixir_lang.Mix
import org.elixir_lang.isElixirMixModule
import org.elixir_lang.notification.setup_sdk.sdkOrNotify
import org.elixir_lang.run.ensureWorkingDirectory
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import java.util.concurrent.Callable

data class DialyzerWarn(
    val fileName: String,
    val line: Int,
    val message: String = "",
    val successfulTyping: String = ""
)

class DialyzerException(message: String?, inner: Throwable?) : Exception(message, inner)

@Service
@com.intellij.openapi.components.State(name = "Dialyzer", storages = [Storage(value = "elixir_dialyzer.xml")])
internal class DialyzerServiceImpl : DialyzerService {
    override var mixArguments = "dialyzer"
    override var elixirArguments = ""
    override var erlArguments = ""

    private val log = Logger.getInstance(DialyzerServiceImpl::class.java)

    override fun dialyzerWarnings(module: Module): List<DialyzerWarn> {
        val (workingDirectory, sdk, project) = ReadAction.nonBlocking(Callable {
            if (!module.isElixirMixModule()) return@Callable null
            val sdk = ElixirSdkLookup.resolveWithErlang(module).sdkOrNotify(module) ?: return@Callable null
            Triple(ensureWorkingDirectory(module), sdk, module.project)
        }).executeSynchronously() ?: return emptyList()

        return dialyzerWarnings(workingDirectory, sdk, project)
    }

    private fun dialyzerWarnings(workingDirectory: String, elixirSdk: Sdk, project: Project): List<DialyzerWarn> = try {
        parseDialyzerOutput(run(workingDirectory, elixirSdk, project))
    } catch (ex: Exception) {
        throw DialyzerException("Error while running Dialyzer: ${ex.message}", ex)
    }

    internal fun commandLine(workingDirectory: String, elixirSdk: Sdk, project: Project): GeneralCommandLine =
        Mix.commandLine(
            emptyMap(),
            workingDirectory,
            elixirSdk,
            ParametersList.parse(erlArguments).toList(),
            ParametersList.parse(elixirArguments).toList(),
            project = project,
        )

    private fun run(workingDirectory: String, elixirSdk: Sdk, project: Project): Pair<String, String> {
        log.info("Dialyzer starting...")
        val mixArgumentList = ParametersList.parse(mixArguments).toList()
        val commandLine = commandLine(workingDirectory, elixirSdk, project)
        commandLine.addParameters(mixArgumentList)
        // Note: add "--force-check" to the Mix arguments in the Dialyzer settings panel
        // if you need dialyxir to re-analyse all callers after a type-contract-only change
        // (@spec / @type / @opaque).  It is not added by default because it forces a full
        // project re-analysis on every run, which can be significantly slower than the
        // normal incremental mode.  The pre-run document save (in Global.runInspection)
        // handles the common stale-results case by ensuring files are flushed to disk
        // before the external process starts.

        val processHandler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(commandLine)
        val stderrBuilder = StringBuilder()
        val stdoutBuilder = StringBuilder()

        processHandler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {}

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                when (outputType) {
                    is ProcessOutputType -> when {
                        outputType.isStderr -> {
                            stderrBuilder.append(event.text)
                        }

                        outputType.isStdout -> {
                            stdoutBuilder.append(event.text)
                        }

                        outputType != ProcessOutputType.SYSTEM -> TODO()
                    }

                    else -> TODO()
                }
            }

            override fun processTerminated(event: ProcessEvent) {}
        })

        processHandler.startNotify()
        processHandler.waitFor()

        val stdout = stdoutBuilder.toString()
        val stderr = stderrBuilder.toString()
        log.info("Dialyzer stderr:\n$stderr\nDialyzer stdout:\n$stdout")

        return Pair(stdout, stderr)
    }

    override fun getState(): State = State(mixArguments, elixirArguments, erlArguments)

    override fun loadState(state: State) {
        mixArguments = state.mixArguments
        elixirArguments = state.elixirArguments
        erlArguments = state.erlArguments
    }
}
