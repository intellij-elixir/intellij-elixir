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

/** What a tool manager's pins on one Elixir install would make, decided before anything is registered. */
internal sealed interface PinsPlan {
    /** The installed Erlang pins, in turn: the first that registers is paired with the install. */
    data class Paired(val erlangs: List<ToolEntry>) : PinsPlan

    /** No installed Erlang pin: the install's own Elixir SDKs decide. */
    sealed interface Unpaired : PinsPlan

    /** The install's one Elixir SDK. */
    data class OnInstall(val sdk: Sdk) : Unpaired

    /** The install's Elixir SDKs, which no Erlang pin tells apart. */
    data class Several(val sdks: List<Sdk>) : Unpaired

    /** The install has no Elixir SDK, and the pins name no installed Erlang to pair one with. */
    data object NeedsErlang : Unpaired
}

internal object ToolManagerPins {
    /**
     * The Elixir SDK for the install at [elixirInstallPath], from the pins of every module [sharing] it, as [plan]
     * decides.
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

    /**
     * What [model] does with [sharing]'s pins on the install at [elixirInstallPath], registering nothing. One module
     * with no pin doesn't hide another's, and an entry the tool manager resolves but has not installed has no directory
     * to register.
     */
    @RequiresBackgroundThread
    suspend fun plan(elixirInstallPath: String, sharing: List<ToolManagerVersions>): PinsPlan {
        val erlangs = sharing.mapNotNull { versions -> versions.erlang?.takeIf { it.installed } }
        return if (erlangs.isNotEmpty()) PinsPlan.Paired(erlangs) else unpaired(elixirInstallPath)
    }

    @RequiresBackgroundThread
    private suspend fun unpaired(elixirInstallPath: String): PinsPlan.Unpaired {
        val onInstall = SdkRegistrar.elixirSdksOnInstall(elixirInstallPath)
        return when (onInstall.size) {
            0 -> PinsPlan.NeedsErlang
            1 -> PinsPlan.OnInstall(onInstall.single())
            else -> PinsPlan.Several(onInstall)
        }
    }

    @RequiresBackgroundThread
    private suspend fun find(elixirInstallPath: String, sharing: List<ToolManagerVersions>, project: Project, parent: Component?): PinsModel {
        val plan = plan(elixirInstallPath, sharing)
        // A pin that fails to register doesn't hide the next, and when none registers the install decides.
        val pinnedErlangSdk = (plan as? PinsPlan.Paired)?.erlangs?.firstNotNullOfOrNull { erlang ->
            LOG.trace("model: registering Erlang SDK at '${erlang.installPath}'")
            SdkRegistrar.registerOrUpdateErlangSdk(erlang.installPath)
        }
        val erlangSdk = pinnedErlangSdk ?: when (val unpaired = plan as? PinsPlan.Unpaired ?: unpaired(elixirInstallPath)) {
            // The step registers its pick in a write action, which `Dispatchers.UI` refuses.
            PinsPlan.NeedsErlang -> @Suppress("ObsoleteDispatchersEdt") withContext(Dispatchers.EDT) {
                ErlangStep.getInstance().ask(parent, elixirInstallPath, null)
            } ?: return PinsModel.None
            is PinsPlan.OnInstall -> return PinsModel.Found(unpaired.sdk)
            is PinsPlan.Several -> return PinsModel.Several(unpaired.sdks)
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
