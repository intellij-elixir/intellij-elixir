package org.elixir_lang.tool_manager

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.system.OS
import org.elixir_lang.jps.shared.tool_manager.ToolManagerCommandOutput
import org.elixir_lang.jps.shared.tool_manager.ToolManagerCommandRunner
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironment
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver
import org.elixir_lang.jps.shared.tool_manager.ToolManagerSettingsState
import org.elixir_lang.jps.shared.tool_manager.builtInToolManagerEnvironments
import org.elixir_lang.jps.shared.tool_manager.resolveToolManagerEnvironment
import org.elixir_lang.sdk.wsl.wslCompat
import java.io.IOException
import java.nio.file.Path

private const val TIMEOUT_MS = 10_000

private val runner = ToolManagerCommandRunner { command, workDir, environment ->
    val output = try {
        CapturingProcessHandler(
            GeneralCommandLine(command)
                .withCharset(Charsets.UTF_8)
                .withWorkingDirectory(workDir)
                .withEnvironment(environment)
        ).runProcess(TIMEOUT_MS)
    } catch (e: ExecutionException) {
        throw IOException(e.message, e)
    }
    if (output.isTimeout) throw IOException("timed out after ${TIMEOUT_MS / 1000} s")

    ToolManagerCommandOutput(output.exitCode, output.stdout, output.stderr)
}

/**
 * The environment of each tool manager that applies one, by name: [builtInToolManagerEnvironments], run as this IDE runs
 * processes.
 *
 * A test replaces the service with one built from its own map. A light service's class must be final.
 */
@Service
class ToolManagerEnvironments(val resolvers: Map<String, ToolManagerEnvironmentResolver>) {
    @Suppress("unused") // The platform makes the service through it.
    constructor() : this(builtInToolManagerEnvironments(runner))

    companion object {
        fun getInstance(): ToolManagerEnvironments = ApplicationManager.getApplication().service()
    }
}

/** Whether [manager] can apply its environment to the processes the plugin starts. */
fun providesEnvironment(manager: ElixirToolManager): Boolean = manager.name in ToolManagerEnvironments.getInstance().resolvers

/**
 * The environment of the tool managers [project] opted into, as its settings stand now, with the opt-ins in [pending]
 * on top. A wizard starts `mix` before it adopts what its chooser ticked, and nothing pending is saved here.
 *
 * The tool manager is asked about [folder] when it is given, and otherwise about each process's working directory.
 */
@JvmOverloads
fun toolManagerEnvironment(
    project: Project?,
    pending: ToolManagerOptIns? = null,
    folder: Path? = null,
): ToolManagerEnvironmentResolver? {
    if (project?.isDisposed == true) return null

    val settings = when {
        project != null -> ToolManagerSettings.getInstance(project).state.copy()
        pending != null -> ToolManagerSettingsState()
        else -> return null
    }
    pending?.adoptInto(settings)
    val resolvers = ToolManagerEnvironments.getInstance().resolvers

    return ToolManagerEnvironmentResolver { workDir, launchVariables ->
        resolveToolManagerEnvironment(settings, resolvers, folder ?: workDir, launchVariables)
    }
}

/**
 * The variables [environment] applies to a process started in [workDir] with [launchVariables] set by the launch.
 *
 * Blocks on a subprocess, on whichever thread starts the process.
 *
 * @throws IOException when the tool manager cannot produce its environment, so the process is not started
 * without it.
 */
@Throws(IOException::class)
fun toolManagerVariables(
    environment: ToolManagerEnvironmentResolver?,
    workDir: Path?,
    launchVariables: Map<String, String>,
): Map<String, String> {
    if (environment == null || workDir == null) return emptyMap()

    return when (val resolved = environment.resolveEnvironment(workDir, launchVariables)) {
        null -> emptyMap()
        is ToolManagerEnvironment.Variables -> resolved.variables
        is ToolManagerEnvironment.Error -> throw IOException(resolved.message)
    }
}

/** Whether the process this command line starts reads its environment ignoring case: on Windows, but not in WSL. */
fun GeneralCommandLine.ignoresVariableCase(): Boolean =
    OS.CURRENT == OS.Windows && !wslCompat.isWslUncPath(workingDirectory?.toString())
