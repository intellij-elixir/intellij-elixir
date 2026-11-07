package org.elixir_lang.sdk

import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import org.elixir_lang.sdk.erlang.PairingRepairs
import org.elixir_lang.sdk.erlang_dependent.ErlangDeletionAsk
import org.elixir_lang.sdk.erlang_dependent.ErlangSdkResolver
import org.elixir_lang.sdk.erlang_dependent.Type.Companion.staticIsValidDependency
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType

/**
 * The plugin's SDK table listener, registered in `plugin.xml` so it hears an addition or a removal with no project open,
 * as from IntelliJ IDEA's welcome screen.
 */
internal class SdkTableListener : ProjectJdkTable.Listener {
    override fun jdkAdded(jdk: Sdk) {
        if (staticIsValidDependency(jdk)) {
            // Before the SDK is renamed or moved: `PairingRepairs` needs its name and home from before.
            PairingRepairs.getInstance().ensureStarted()
            // An Elixir SDK set up while no Erlang SDK was at its stored home has none of the code paths it now resolves to.
            // One still resolving to another SDK there keeps its roots, the user's included.
            val resolvingToIt = ProjectJdkTable.getInstance().getSdksOfType(ElixirSdkType.instance).filter {
                SdkRegistrar.sameErlangHome(it, jdk) && ErlangSdkResolver.getInstance().resolveErlangSdk(it) === jdk
            }
            ElixirSdkPathConfigurator.followErlangHome(resolvingToIt)
        }
    }

    override fun jdkRemoved(jdk: Sdk) {
        if (staticIsValidDependency(jdk)) {
            ErlangDeletionAsk.getInstance().erlangRemoved(jdk)
        }
    }
}
