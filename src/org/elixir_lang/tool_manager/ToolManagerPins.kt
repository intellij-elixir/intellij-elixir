package org.elixir_lang.tool_manager

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.elixir_lang.sdk.SdkRegistrar
import org.elixir_lang.sdk.choice.saveSdkTable
import org.elixir_lang.sdk.erlang_dependent.ErlangStep
import java.awt.Component

private val LOG = logger<ToolManagerPins>()

/** The Elixir SDK a tool manager's pins on one Elixir install make. */
internal sealed interface PinsModel {
    data class Found(val sdk: Sdk) : PinsModel

    /** The install has several Elixir SDKs, and no Erlang pin registers to tell them apart. */
    data class Several(val sdks: List<Sdk>) : PinsModel

    /** The Erlang step was cancelled, or the Elixir SDK failed to register. */
    data object None : PinsModel
}

internal object ToolManagerPins {
    /**
     * The Elixir SDK for the install at [elixirInstallPath], from the pins of every module [sharing] it.
     *
     * The first installed Erlang pin that registers is paired with it: the SDK with that pairing is reused, or one is
     * made. With none, the install's own SDK is used when it has one, and when it has none the Erlang step, over
     * [parent], pairs a new one. No SDK is paired again. Every SDK it answers is in `jdk.table.xml` before it answers.
     */
    @RequiresBackgroundThread
    suspend fun model(elixirInstallPath: String, sharing: List<ToolManagerVersions>, project: Project, parent: Component?): PinsModel {
        val model = find(elixirInstallPath, sharing, project, parent)
        if (model != PinsModel.None) withContext(Dispatchers.IO) { saveSdkTable() }
        return model
    }

    @RequiresBackgroundThread
    private suspend fun find(elixirInstallPath: String, sharing: List<ToolManagerVersions>, project: Project, parent: Component?): PinsModel {
        // One module with no pin, or with one that fails, doesn't hide another's. An entry the tool manager resolves
        // but has not installed has no directory to register.
        val pinnedErlangSdk = sharing.firstNotNullOfOrNull { versions ->
            versions.erlang?.takeIf { it.installed }?.let { erlang ->
                LOG.trace("model: registering Erlang SDK at '${erlang.installPath}'")
                SdkRegistrar.registerOrUpdateErlangSdk(erlang.installPath)
            }
        }
        val erlangSdk = pinnedErlangSdk ?: run {
            val onInstall = SdkRegistrar.elixirSdksOnInstall(elixirInstallPath)
            when (onInstall.size) {
                0 -> {
                    // The step registers its pick in a write action, which `Dispatchers.UI` refuses.
                    @Suppress("ObsoleteDispatchersEdt")
                    withContext(Dispatchers.EDT) { ErlangStep.getInstance().ask(parent, elixirInstallPath, null) }
                        ?: return PinsModel.None
                }
                1 -> return PinsModel.Found(onInstall.single())
                else -> return PinsModel.Several(onInstall)
            }
        }
        LOG.trace("model: registering Elixir SDK at '$elixirInstallPath' erlang='${erlangSdk.name}'")
        val elixirSdk = SdkRegistrar.registerOrUpdateElixirSdk(homePath = elixirInstallPath, erlangSdk = erlangSdk, project = project)
            ?: run {
                LOG.warn("model: registerOrUpdateElixirSdk returned null for '$elixirInstallPath'")
                return PinsModel.None
            }
        LOG.trace("model: registered Elixir SDK '${elixirSdk.name}' for '$elixirInstallPath'")
        return PinsModel.Found(elixirSdk)
    }
}
