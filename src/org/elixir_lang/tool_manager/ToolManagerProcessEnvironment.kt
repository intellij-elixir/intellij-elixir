package org.elixir_lang.tool_manager

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.util.system.OS
import org.elixir_lang.jps.shared.tool_manager.ToolManagerCommandOutput
import org.elixir_lang.jps.shared.tool_manager.ToolManagerCommandRunner
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironment
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver
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

private val resolvers: Map<String, ToolManagerEnvironmentResolver> = builtInToolManagerEnvironments(runner)

/** Whether [manager] can apply its environment to the processes the plugin starts. */
fun providesEnvironment(manager: ElixirToolManager): Boolean = manager.name in resolvers

/** The environment of the tool managers [project] opted into, as its settings stand now. */
fun toolManagerEnvironment(project: Project?): ToolManagerEnvironmentResolver? {
    if (project == null || project.isDisposed) return null

    val settings = ToolManagerSettings.getInstance(project).state.copy()

    return ToolManagerEnvironmentResolver { workDir, launchVariables ->
        resolveToolManagerEnvironment(settings, resolvers, workDir, launchVariables)
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
