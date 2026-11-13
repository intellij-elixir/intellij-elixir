package org.elixir_lang.sdk.erlang_dependent

import com.intellij.execution.CantRunException
import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.util.system.OS
import org.elixir_lang.notification.setup_sdk.Notifier
import org.elixir_lang.sdk.wsl.effectiveOS
import org.elixir_lang.util.ReadActions

/**
 * An Elixir SDK and the Erlang SDK it resolves to, read in one read action so that every field is from the same SDK
 * commit.
 */
data class ElixirSdkSnapshot(
    val elixirSdkName: String,
    val elixirHome: String?,
    val elixirVersion: String?,
    val erlangHome: String?,
    val missingErlang: ErlangSdkResult.Missing? = null,
) {
    val os: OS get() = effectiveOS(elixirHome)

    fun elixirHomeOrThrow(): String = elixirHome ?: throw ExecutionException("Elixir SDK home path is not configured")

    fun erlangHomeOrNotifyAndThrow(project: Project?): String {
        missingErlang?.let {
            Notifier.elixirSdkMissingErlangDependency(project, it)
            throw CantRunException.CustomProcessedCantRunException()
        }
        return erlangHome ?: throw ExecutionException("Erlang SDK home path is not configured")
    }

    companion object {
        fun of(elixirSdk: Sdk): ElixirSdkSnapshot =
            ReadActions.compute {
                val erlang = ErlangSdkResolver.getInstance().resolveErlangSdkResult(elixirSdk)
                ElixirSdkSnapshot(
                    elixirSdk.name,
                    elixirSdk.homePath,
                    elixirSdk.versionString,
                    (erlang as? ErlangSdkResult.Success)?.sdk?.homePath,
                    erlang as? ErlangSdkResult.Missing,
                )
            }
    }
}
