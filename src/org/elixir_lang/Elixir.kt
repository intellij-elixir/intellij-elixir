package org.elixir_lang

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.cli.CliArguments
import org.elixir_lang.jps.shared.cli.CliArgs
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.run.baseCommandLine
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData

object Elixir {
    /**
     * Keep in-sync with [org.elixir_lang.jps.Builder.elixirCommandLine]
     */
    @RequiresReadLock
    fun commandLine(
        environment: Map<String, String>,
        workingDirectory: String?,
        elixirSdk: Sdk,
        erlArgumentList: kotlin.collections.List<String> = emptyList(),
        project: Project? = null,
    ): GeneralCommandLine {
        val args: CliArgs =
            CliArguments.argsOrThrow(
                elixirSdk,
                CliTool.ELIXIR,
                extraErlangArguments = erlArgumentList,
                project = project,
            )
        val commandLine = baseCommandLine(false, environment, workingDirectory, project)
        commandLine.exePath = args.exePath
        commandLine.addParameters(args.arguments)
        return commandLine
    }

    fun elixirSdkHasErlangSdk(elixirSdk: Sdk): Boolean = elixirSdkToErlangSdk(elixirSdk) != null
    fun elixirSdkToErlangSdk(elixirSdk: Sdk): Sdk? = elixirSdk.sdkAdditionalData?.let { it as SdkAdditionalData }?.getErlangSdk()
}
