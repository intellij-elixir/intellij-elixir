package org.elixir_lang.sdk.elixir

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.erlang_dependent.elixirAdditionalData
import org.elixir_lang.util.ReadActions

/**
 * Shared classification of a module's Elixir SDK, so the status text shown by the status-bar
 * widget ([org.elixir_lang.status_bar_widget.ElixirEditorBasedSdkWidget]) and by the
 * Settings -> Elixir per-module SDK page ([org.elixir_lang.facet.Configurable]) is defined in one
 * place rather than duplicated.
 */
sealed interface ModuleSdkStatus {
    object NoSdk : ModuleSdkStatus
    data class Invalid(val elixirSdk: Sdk) : ModuleSdkStatus
    data class MissingErlang(val elixirSdk: Sdk) : ModuleSdkStatus
    data class Ready(val elixirSdk: Sdk, val erlangSdk: Sdk) : ModuleSdkStatus

    companion object {
        /**
         * @param sdkModel the settings dialog's model, where there is one: an SDK added in that dialog is not in the
         * SDK table until committed, so without it a fresh pairing reports its Erlang dependency missing.
         * @param knownHomePath the home the caller filled the store from; re-reading the live SDK could see another
         * home if an edit was committed in between.
         */
        fun of(elixirSdk: Sdk?, sdkModel: SdkModel? = null, knownHomePath: String? = null): ModuleSdkStatus {
            if (elixirSdk == null) return NoSdk

            // One lock over every model read: a settings dialog commits a modificator to its editable clone inside a
            // write action, so separate reads could straddle that commit and classify a half-applied SDK.
            return ReadActions.compute {
                if (elixirSdk.sdkType !is Type) return@compute Invalid(elixirSdk)
                val homePath = knownHomePath ?: elixirSdk.homePath ?: return@compute Invalid(elixirSdk)
                // A recorded version is what makes the home an installation; nothing here touches the filesystem.
                if (SdkVersionsStore.getInstance().elixirVersions(homePath) == null) return@compute Invalid(elixirSdk)
                val erlangSdk = elixirSdk.elixirAdditionalData?.getErlangSdk(sdkModel)
                    ?: return@compute MissingErlang(elixirSdk)
                Ready(elixirSdk, erlangSdk)
            }
        }
    }
}

/**
 * HTML summary of the SDK status (usable in tooltips and [com.intellij.ui.components.JBLabel]s).
 * When [moduleName] is non-null it is appended, matching the status-bar widget's multi-module tooltip.
 */
fun ModuleSdkStatus.summaryHtml(moduleName: String? = null): String {
    val moduleSuffix = moduleName?.let { " (module '$it')" } ?: ""
    return when (this) {
        is ModuleSdkStatus.NoSdk ->
            "No Elixir SDK configured$moduleSuffix"
        is ModuleSdkStatus.Invalid ->
            "Elixir SDK: ${elixirSdk.name} - Invalid SDK$moduleSuffix"
        is ModuleSdkStatus.MissingErlang ->
            "Elixir SDK: ${elixirSdk.name} - Missing Erlang SDK$moduleSuffix"
        is ModuleSdkStatus.Ready -> buildString {
            append("Elixir: <b>${elixirSdk.name}</b>")
            append("<br>Erlang: <b>${erlangSdk.name}</b>")
            if (moduleName != null) append("<br>Module: <b>$moduleName</b>")
        }
    }
}
