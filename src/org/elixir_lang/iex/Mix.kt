package org.elixir_lang.iex

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import org.elixir_lang.cli.getExecutableFilepathWslSafe
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.jps.shared.sdk.SdkPaths
import org.elixir_lang.sdk.erlang_dependent.ElixirSdkSnapshot

object Mix {
    fun commandLine(
            environment: Map<String, String>,
            workingDirectory: String?,
            elixirSdk: Sdk,
            erlArgumentList: List<String>,
            iexArgumentList: List<String>,
            project: Project? = null,
    ): GeneralCommandLine {
        val snapshot = ElixirSdkSnapshot.of(elixirSdk)
        val commandLine =
            org.elixir_lang.IEx.commandLine(
                environment,
                workingDirectory,
                snapshot,
                erlArgumentList,
                project,
                SdkPaths.mixHomeEnvironment(snapshot.elixirHome),
            )
        commandLine.addParameters(iexArgumentList)
        addMix(commandLine, snapshot)
        return commandLine
    }

    private fun addMix(commandLine: GeneralCommandLine, snapshot: ElixirSdkSnapshot) {
        val mixPath = CliTool.MIX.getExecutableFilepathWslSafe(snapshot.elixirHomeOrThrow())
        commandLine.addParameters("-S", mixPath)
    }
}
