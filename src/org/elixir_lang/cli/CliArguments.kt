package org.elixir_lang.cli

import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import com.intellij.util.system.OS
import org.elixir_lang.jps.shared.cli.CliArgs
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.sdk.erlang_dependent.ElixirSdkSnapshot
import org.elixir_lang.sdk.wsl.wslCompat
import org.elixir_lang.jps.shared.cli.CliArguments as SharedCliArguments

object CliArguments {
    fun argsOrThrow(
        snapshot: ElixirSdkSnapshot,
        tool: CliTool,
        extraElixirArguments: List<String> = emptyList(),
        extraErlangArguments: List<String> = emptyList(),
        os: OS = snapshot.os,
        project: Project? = null,
        ansiEnabled: Boolean = true,
    ): CliArgs {
        val elixirHomePath = snapshot.elixirHomeOrThrow()
        val erlangHomePath = snapshot.erlangHomeOrNotifyAndThrow(project)
        wslCompat.requireReachable(elixirHomePath, "Elixir SDK home")
        wslCompat.requireReachable(erlangHomePath, "Erlang SDK home")

        return SharedCliArguments.args(
            elixirHomePath,
            snapshot.elixirVersion,
            erlangHomePath,
            tool,
            extraElixirArguments,
            extraErlangArguments,
            os,
            ansiEnabled
        ) ?: throw ExecutionException("Unable to compute CLI arguments for SDK ${snapshot.elixirSdkName}")
    }
}
