package org.elixir_lang.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Elixir
import org.elixir_lang.notification.setup_sdk.Notifier
import org.elixir_lang.sdk.SdkVersionsFiller
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType
import java.util.concurrent.Callable

private val LOG = logger<RefreshActiveElixirSdkAction>()

class RefreshActiveElixirSdkAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        try {
            refreshElixirSdkPaths(project)
        } catch (ex: Exception) {
            LOG.warn("Failed to refresh SDK paths (RefreshActiveElixirSdkAction)", ex)
            Notifier.sdkRefreshError(project, ex.message ?: "Unknown error")
        }
    }

    private fun refreshElixirSdkPaths(project: Project) {
        val (activeElixirSdks, activeErlangSdks) = collectActiveSdks(project)

        val totalElixirSdks = activeElixirSdks.size
        val totalErlangSdks = activeErlangSdks.size
        val totalActiveSdks = totalElixirSdks + totalErlangSdks

        if (totalActiveSdks == 0) {
            Notifier.sdkRefreshWarning(project, "No active Elixir or Erlang SDKs found in this project.")
            return
        }

        var refreshedElixirCount = 0
        var refreshedErlangCount = 0

        runWithModalProgressBlocking(
            ModalTaskOwner.project(project), "Refreshing Active SDK Paths"
        ) {
            val elixirSdkType = ElixirSdkType.instance
            // Read again rather than only if unread: refreshing is the user saying an installation changed.
            for (homePath in (activeErlangSdks + activeElixirSdks).mapNotNull { it.homePath }.distinct()) {
                SdkVersionsFiller.fill(homePath)
            }

            // Refresh active Elixir SDKs
            for (elixirSdk in activeElixirSdks) {
                try {
                    refreshSingleElixirSdk(elixirSdk, elixirSdkType)
                    refreshedElixirCount++
                } catch (_: Exception) {
                    // Continue with other SDKs if one fails
                    continue
                }
            }

            // Refresh active Erlang SDKs
            for (erlangSdk in activeErlangSdks) {
                try {
                    refreshSingleErlangSdk(erlangSdk, erlangSdk.sdkType as ErlangSdkType)
                    refreshedErlangCount++
                } catch (_: Exception) {
                    // Continue with other SDKs if one fails
                    continue
                }
            }
        }

        // Show success notification with counts
        Notifier.sdkRefreshSuccess(project, refreshedElixirCount, totalElixirSdks, refreshedErlangCount, totalErlangSdks)
    }

    private fun refreshSingleElixirSdk(sdk: Sdk, sdkType: ElixirSdkType) {
        // setupSdkPaths handles its own write action and SDK modificator management
        // This clears existing paths and reconfigures them using the existing logic
        sdkType.setupSdkPaths(sdk)
    }

    private fun refreshSingleErlangSdk(sdk: Sdk, sdkType: ErlangSdkType) {
        // setupSdkPaths handles its own write action and SDK modificator management
        // This clears existing paths and reconfigures them using the existing logic
        sdkType.setupSdkPaths(sdk)
    }

    override fun isDumbAware(): Boolean = true

    private fun collectActiveSdks(project: Project): Pair<Set<Sdk>, Set<Sdk>> =
        ReadAction.nonBlocking(Callable {
            val elixirSdks = ElixirSdkLookup.resolveAll(project)
            Pair(elixirSdks, collectActiveErlangSdks(elixirSdks))
        }).executeSynchronously()

    // Must be called under a read action: SdkAdditionalData.getErlangSdk() resolves via ErlangSdkResolver.
    @RequiresReadLock
    private fun collectActiveErlangSdks(elixirSdks: Set<Sdk>): Set<Sdk> {
        ThreadingAssertions.assertReadAccess()
        val activeSdks = mutableSetOf<Sdk>()
        for (elixirSdk in elixirSdks) {
            ProgressManager.checkCanceled()
            val erlangSdk = Elixir.elixirSdkToErlangSdk(elixirSdk)
            if (erlangSdk != null) activeSdks.add(erlangSdk)
        }
        return activeSdks
    }
}
