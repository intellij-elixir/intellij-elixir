package org.elixir_lang.sdk

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import org.elixir_lang.sdk.erlang_dependent.ErlangDeletionAsk
import org.elixir_lang.sdk.erlang_dependent.Type.Companion.staticIsValidDependency

private val LOG = logger<SdkTableListener>()

/**
 * The plugin's SDK table listener, registered in `plugin.xml` so it hears a removal or a rename with no project open,
 * as from IntelliJ IDEA's welcome screen.
 */
internal class SdkTableListener : ProjectJdkTable.Listener {
    override fun jdkRemoved(jdk: Sdk) {
        if (staticIsValidDependency(jdk)) {
            ErlangDeletionAsk.getInstance().erlangRemoved(jdk)
        }
    }

    override fun jdkNameChanged(jdk: Sdk, previousName: String) {
        if (staticIsValidDependency(jdk)) {
            LOG.info("Erlang SDK renamed from '$previousName' to '${jdk.name}'")
            // The table's rename is applied in a write action, which this event fires inside.
            val rehomed = ElixirSdkMutation.repairPairings(ProjectJdkTable.getInstance().allJdks.asList(), jdk, previousName, previousHome = null)
            ElixirSdkPathConfigurator.followErlangHome(rehomed)
        }
    }
}
