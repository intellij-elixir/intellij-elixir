package org.elixir_lang.cli

import com.intellij.execution.ExecutionException
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.system.OS
import org.elixir_lang.jps.shared.cli.CliArgs
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.sdk.erlang_dependent.getErlangSdk
import org.elixir_lang.sdk.erlang_dependent.requireErlangSdkOrNotifyAndThrow
import org.elixir_lang.jps.shared.cli.CliArguments as SharedCliArguments
import org.elixir_lang.sdk.wsl.wslCompat

object CliArguments {
    fun args(
        elixirSdk: Sdk,
        tool: CliTool,
        extraElixirArguments: List<String> = emptyList(),
        extraErlangArguments: List<String> = emptyList(),
        os: OS = effectiveOS(elixirSdk),
        ansiEnabled: Boolean = true
    ): CliArgs? {
        return SharedCliArguments.args(
            elixirSdk.homePath,
            elixirSdk.versionString,
            elixirSdk.getErlangSdk()?.homePath,
            tool,
            extraElixirArguments,
            extraErlangArguments,
            os,
            ansiEnabled
        )
    }

    @RequiresReadLock
    fun argsOrThrow(
        elixirSdk: Sdk,
        tool: CliTool,
        extraElixirArguments: List<String> = emptyList(),
        extraErlangArguments: List<String> = emptyList(),
        os: OS = effectiveOS(elixirSdk),
        project: Project? = null,
        ansiEnabled: Boolean = true,
    ): CliArgs {
        val elixirHomePath =
            elixirSdk.homePath
                ?: throw ExecutionException("Elixir SDK home path is not configured")
        val erlangSdk = elixirSdk.requireErlangSdkOrNotifyAndThrow(project = project)
        val erlangHomePath =
            erlangSdk.homePath
                ?: throw ExecutionException("Erlang SDK home path is not configured")
        wslCompat.requireReachable(elixirHomePath, "Elixir SDK home")
        wslCompat.requireReachable(erlangHomePath, "Erlang SDK home")

        return SharedCliArguments.args(
            elixirHomePath,
            elixirSdk.versionString,
            erlangHomePath,
            tool,
            extraElixirArguments,
            extraErlangArguments,
            os,
            ansiEnabled
        ) ?: throw ExecutionException("Unable to compute CLI arguments for SDK ${elixirSdk.name}")
    }

    internal fun effectiveOS(elixirSdk: Sdk): OS {
        if (OS.CURRENT == OS.Windows) {
            elixirSdk.homePath?.let { if (WslPath.isWslUncPath(it)) return OS.Linux }
        }
        return OS.CURRENT
    }
}
