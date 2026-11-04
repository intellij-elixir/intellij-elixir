package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.sdk.SdkRegistrar
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType

/**
 * Asks which Erlang an Elixir SDK runs on when the Erlang SDK it was paired with is deleted. The Elixir SDKs are recorded
 * at the removal and asked once no modal dialog is open, so after the settings dialog that deleted it closes; one that
 * is gone by then, or whose pairing again resolves to the Erlang SDK it names, is not asked.
 */
@Service
class ErlangDeletionAsk {
    private val recorded = LinkedHashSet<Sdk>()

    /** At `jdkRemoved`: records the table's Elixir SDKs paired with [deleted]. */
    fun erlangRemoved(deleted: Sdk) {
        val paired = ProjectJdkTable.getInstance().getSdksOfType(ElixirSdkType.instance).filter { pairedWith(it, deleted) }
        if (paired.isEmpty()) return

        val first = synchronized(recorded) { recorded.isEmpty().also { recorded += paired } }
        if (first) ApplicationManager.getApplication().invokeLater(::askRecorded, ModalityState.nonModal())
    }

    @RequiresEdt
    private fun askRecorded() {
        val elixirSdks = synchronized(recorded) { recorded.toList().also { recorded.clear() } }
        elixirSdks.filter(::unpaired).forEach(::ask)
    }

    @RequiresEdt
    private fun ask(elixirSdk: Sdk) {
        val elixirHome = elixirSdk.homePath ?: return
        val erlangSdk = ErlangStep.getInstance().ask(null, elixirHome, null, asking = elixirSdk) ?: return

        if (WriteAction.compute<Boolean, Throwable> { ElixirSdkMutation.applyDependencySelection(elixirSdk, erlangSdk) }) {
            ElixirSdkPathConfigurator.followErlangHome(listOf(elixirSdk))
        }
    }

    companion object {
        fun getInstance(): ErlangDeletionAsk = ApplicationManager.getApplication().service()

        /** By the registrar's variant rule, or by its stored name; a deleted Erlang SDK with no home, by name only. */
        private fun pairedWith(elixirSdk: Sdk, deleted: Sdk): Boolean {
            val data = elixirSdk.elixirAdditionalData ?: return false

            return data.getErlangSdkName() == deleted.name ||
                (!deleted.homePath.isNullOrBlank() && SdkRegistrar.sameErlangHome(elixirSdk, deleted))
        }

        /** Still in the table, with a pairing that resolves to nothing or to an Erlang SDK other than the one it names. */
        private fun unpaired(elixirSdk: Sdk): Boolean {
            if (ProjectJdkTable.getInstance().allJdks.none { it === elixirSdk }) return false
            val named = elixirSdk.elixirAdditionalData?.getErlangSdkName()
            val resolved = ReadAction.computeBlocking<Sdk?, RuntimeException> { elixirSdk.getErlangSdk() }

            return resolved == null || (named != null && resolved.name != named)
        }
    }
}
