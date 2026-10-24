package org.elixir_lang.iex

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import org.elixir_lang.cli.getExecutableFilepathWslSafe
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.jps.shared.sdk.SdkPaths

object Mix {
    fun commandLine(
            environment: Map<String, String>,
            workingDirectory: String?,
            elixirSdk: Sdk,
            erlArgumentList: List<String>,
            iexArgumentList: List<String>,
            project: Project? = null,
    ): GeneralCommandLine {
        val commandLine =
            org.elixir_lang.IEx.commandLine(
                environment,
                workingDirectory,
                elixirSdk,
                erlArgumentList,
                project,
                SdkPaths.mixHomeEnvironment(elixirSdk.homePath),
            )
        commandLine.addParameters(iexArgumentList)
        addMix(commandLine, elixirSdk)
        return commandLine
    }

    private fun addMix(commandLine: GeneralCommandLine, sdk: Sdk) {
        val sdkHomePath =
            sdk.homePath ?: throw ExecutionException("Elixir SDK home path is not configured")
        val mixPath = CliTool.MIX.getExecutableFilepathWslSafe(sdkHomePath)
        commandLine.addParameters("-S", mixPath)
    }
}
