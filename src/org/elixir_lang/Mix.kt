package org.elixir_lang

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.cli.CliArguments
import org.elixir_lang.jps.shared.cli.CliArgs
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.jps.shared.sdk.SdkPaths
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver
import org.elixir_lang.run.baseCommandLine
import org.elixir_lang.tool_manager.toolManagerEnvironment

object Mix {
    @JvmStatic
    @RequiresReadLock
    fun commandLine(
        environment: Map<String, String>,
        workingDirectory: String?,
        elixirSdk: Sdk,
        erlParameters: List<String> = emptyList(),
        elixirParameters: List<String> = emptyList(),
        pty: Boolean = false,
        project: Project? = null,
        ansiEnabled: Boolean = true,
        toolManagerEnvironment: ToolManagerEnvironmentResolver? = toolManagerEnvironment(project),
    ): GeneralCommandLine {
        val args: CliArgs =
            CliArguments.argsOrThrow(
                elixirSdk,
                CliTool.MIX,
                extraElixirArguments = elixirParameters,
                extraErlangArguments = erlParameters,
                project = project,
                ansiEnabled = ansiEnabled,
            )
        val commandLine = baseCommandLine(
            pty,
            environment,
            workingDirectory,
            project,
            SdkPaths.mixHomeEnvironment(elixirSdk.homePath),
            toolManagerEnvironment,
        )
        commandLine.exePath = args.exePath
        commandLine.addParameters(args.arguments)
        return commandLine
    }
}
