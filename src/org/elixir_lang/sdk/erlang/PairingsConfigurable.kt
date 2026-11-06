package org.elixir_lang.sdk.erlang

import com.intellij.openapi.projectRoots.AdditionalDataConfigurable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import javax.swing.JComponent

/**
 * The Erlang SDK editor's part that shows nothing. While the editor is open, [PairingRepairs] repairs its model's Elixir
 * copies too when an Erlang SDK is renamed or moved: no platform registry lists the open models, and every model hosts
 * an editor per Erlang SDK.
 */
internal class PairingsConfigurable(sdkModel: SdkModel) : AdditionalDataConfigurable {
    private var opened: ProjectSdksModel? = (sdkModel as? ProjectSdksModel)?.also { PairingRepairs.getInstance().open(it) }

    override fun setSdk(sdk: Sdk) {}

    override fun createComponent(): JComponent? = null

    override fun isModified(): Boolean = false

    override fun apply() {}

    override fun disposeUIResources() {
        opened?.let(PairingRepairs.getInstance()::release)
        opened = null
    }
}
