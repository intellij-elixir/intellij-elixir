package org.elixir_lang.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import org.elixir_lang.notification.setup_sdk.Notifier
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType

class DeleteAllSdksAction : AnAction() {
    companion object {
        private val LOG = Logger.getInstance(DeleteAllSdksAction::class.java)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        try {
            removeAllElixirAndErlangSdks(project)
        } catch (ex: Exception) {
            LOG.warn("Failed to refresh SDK paths (DeleteAllSdksAction)", ex)
            Notifier.sdkRefreshError(project, "Error removing SDKs: ${ex.message ?: "Unknown error"}")
        }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        e.presentation.isVisible = project != null
    }

    private fun removeAllElixirAndErlangSdks(project: Project) {
        val projectJdkTable = ProjectJdkTable.getInstance()
        val allSdks = projectJdkTable.allJdks

        // Find all Elixir and Erlang SDKs
        val elixirSdks = allSdks.filter { it.sdkType is ElixirSdkType }
        val erlangSdks = allSdks.filter { it.sdkType is ErlangSdkType }
        // Remove Erlang SDKs first since Elixir SDKs depend on them
        val allTargetSdks = erlangSdks + elixirSdks

        if (allTargetSdks.isEmpty()) {
            Notifier.sdkRefreshWarning(project, "No Elixir or Erlang SDKs found in the IDE.")
            return
        }

        // Show confirmation dialog
        val sdkNames = allTargetSdks.joinToString("\n") { "• ${it.name}" }
        val result = Messages.showYesNoDialog(
            project,
            "Are you sure you want to delete all Elixir and Erlang SDKs?\n\n" +
                    "This will remove the following SDKs from the IDE:\n$sdkNames\n\n" +
                    "This action cannot be undone.",
            "Delete All Elixir/Erlang SDKs",
            Messages.getQuestionIcon()
        )

        if (result != Messages.YES) {
            return
        }

        val sdksRemoved = runWithModalProgressBlocking(project, "Removing All Elixir/Erlang SDKs") {
            var removedCount = 0

            edtWriteAction {
                LOG.info("Starting removal of ${allTargetSdks.size} SDK(s)")

                for (sdk in allTargetSdks) {
                    try {
                        LOG.debug("Removing SDK: ${sdk.name}")
                        projectJdkTable.removeJdk(sdk)
                        removedCount++

                        LOG.debug("Successfully removed SDK: ${sdk.name}")
                    } catch (ex: Exception) {
                        LOG.error("Failed to remove SDK: ${sdk.name}", ex)
                        // Continue with other SDKs if one fails
                        continue
                    }
                }
                LOG.info("Completed removal of $removedCount SDK(s)")
            }

            removedCount
        }

        // Show success notification
        if (sdksRemoved > 0) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Elixir")
                .createNotification(
                    "Elixir SDKs removed successfully",
                    "Successfully removed $sdksRemoved SDK${if (sdksRemoved == 1) "" else "s"} (${elixirSdks.size} Elixir, ${erlangSdks.size} Erlang) from the IDE.",
                    NotificationType.INFORMATION
                )
                .notify(project)
        } else {
            Notifier.sdkRefreshWarning(
                project,
                "No Elixir SDKs were removed. They may have been removed already or there was an error."
            )
        }
    }

    override fun isDumbAware(): Boolean = true

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
