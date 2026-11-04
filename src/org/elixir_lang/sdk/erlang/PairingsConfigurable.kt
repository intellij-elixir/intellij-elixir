package org.elixir_lang.sdk.erlang

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.AdditionalDataConfigurable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import javax.swing.JComponent

/**
 * The Erlang SDK editor's part that shows nothing. When the editor applies a rename to its copy of the SDK, the Elixir
 * copies in the same model that store the old name are paired under the new one, so every apply of the model writes
 * them that way: the model writes each copy over its SDK, and so over what the table listener commits on the rename.
 * The editor calls [apply] after committing the copy's new name and before the model applies.
 */
internal class PairingsConfigurable(private val sdkModel: SdkModel) : AdditionalDataConfigurable {
    private var sdk: Sdk? = null

    /** The copy's name when last reset or applied, which the Elixir copies store. */
    private var name: String? = null

    override fun setSdk(sdk: Sdk) {
        this.sdk = sdk
    }

    override fun createComponent(): JComponent? = null

    override fun isModified(): Boolean = false

    override fun reset() {
        name = sdk?.name
    }

    override fun apply() {
        val erlangSdk = sdk ?: return
        val previousName = name
        name = erlangSdk.name
        if (previousName == null || previousName == erlangSdk.name) return

        WriteAction.run<Throwable> { ElixirSdkMutation.repairRenamedPairings(sdkModel.sdks.asList(), erlangSdk, previousName) }
    }
}
