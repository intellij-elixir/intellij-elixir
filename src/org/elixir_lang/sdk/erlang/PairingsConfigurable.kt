package org.elixir_lang.sdk.erlang

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.AdditionalDataConfigurable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import org.elixir_lang.sdk.wsl.wslCompat
import javax.swing.JComponent

/**
 * The Erlang SDK editor's part that shows nothing. When the editor applies a rename to its copy of the SDK, the Elixir
 * copies in the same model that store the old name are paired under the new one, and when it applies a new home, those
 * that store the old home are paired at the new one. Those the repair pairs at another home take that home's code
 * paths. So every apply of the model writes them that way: the model writes each copy over its SDK, and so over what
 * the table listener commits on the rename. The editor calls [apply] after committing the copy's change and before the
 * model applies.
 */
internal class PairingsConfigurable(private val sdkModel: SdkModel) : AdditionalDataConfigurable {
    private var sdk: Sdk? = null

    /** The copy's name when last reset or applied, which the Elixir copies store. */
    private var name: String? = null

    /**
     * The copy's home when last reset or applied, which the Elixir copies store, or null when blank: `pathsEqualWslAware`
     * is false for a blank path, so a blank home left as it was would read as a move.
     */
    private var home: String? = null

    override fun setSdk(sdk: Sdk) {
        this.sdk = sdk
    }

    override fun createComponent(): JComponent? = null

    override fun isModified(): Boolean = false

    override fun reset() {
        name = sdk?.name
        home = sdk?.homePath?.takeIf(String::isNotBlank)
    }

    override fun apply() {
        val erlangSdk = sdk ?: return
        val renamedFrom = name?.takeIf { it != erlangSdk.name }
        val movedFrom = home?.takeUnless { wslCompat.pathsEqualWslAware(it, erlangSdk.homePath) }
        reset()
        if (renamedFrom == null && movedFrom == null) return

        val rehomed = WriteAction.compute<List<Sdk>, Throwable> {
            ElixirSdkMutation.repairPairings(sdkModel.sdks.asList(), erlangSdk, renamedFrom, movedFrom)
        }
        ElixirSdkPathConfigurator.followErlangHome(rehomed, sdkModel)
    }
}
