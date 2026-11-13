package org.elixir_lang

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import org.elixir_lang.cli.CliArguments
import org.elixir_lang.jps.shared.cli.CliArgs
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.jps.shared.tool_manager.SdkEnvironment
import org.elixir_lang.run.baseCommandLine
import org.elixir_lang.sdk.erlang_dependent.ElixirSdkSnapshot

object IEx {
    fun commandLine(
        environment: Map<String, String>,
        workingDirectory: String?,
        elixirSdk: Sdk,
        erlArgumentList: List<String>,
        project: Project? = null,
        sdkEnvironment: SdkEnvironment? = null,
    ): GeneralCommandLine =
        commandLine(environment, workingDirectory, ElixirSdkSnapshot.of(elixirSdk), erlArgumentList, project, sdkEnvironment)

    fun commandLine(
        environment: Map<String, String>,
        workingDirectory: String?,
        snapshot: ElixirSdkSnapshot,
        erlArgumentList: List<String>,
        project: Project? = null,
        sdkEnvironment: SdkEnvironment? = null,
    ): GeneralCommandLine {
        val args: CliArgs =
            CliArguments.argsOrThrow(
                snapshot,
                CliTool.IEX,
                extraErlangArguments = erlArgumentList,
                project = project,
            )
        val commandLine = baseCommandLine(true, environment, workingDirectory, project, sdkEnvironment)
        commandLine.exePath = args.exePath
        commandLine.addParameters(args.arguments)
        return commandLine
    }
}
