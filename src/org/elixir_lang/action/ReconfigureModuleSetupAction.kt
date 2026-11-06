package org.elixir_lang.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.*
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.mix.project.CANONICAL_FOLDER_MARKS
import org.elixir_lang.mix.project.FolderMark
import org.elixir_lang.isElixirMixModule
import org.elixir_lang.mixContentRoots
import org.elixir_lang.mix.Project as MixProject
import org.elixir_lang.sdk.elixir.ElixirSdkLookup

private val LOG = logger<ReconfigureModuleSetupAction>()

/**
 * Additively applies canonical folder marks from [CANONICAL_FOLDER_MARKS] for all Elixir Mix modules in the
 * project.
 *
 * **Additive behavior**: Existing user-customized source roots and exclusions are preserved.
 * The action only *adds* missing canonical marks.
 *
 * Registered as `Elixir.ReconfigureModuleSetup` in `plugin.xml`.
 *
 * @see org.elixir_lang.mix.Project.addFolders
 * @see org.elixir_lang.mix.project.ProjectModuleSetupValidator
 */
class ReconfigureModuleSetupAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // Enable when at least one module has a mix.exs in its content root,
        // or any module has an active Elixir SDK configured.
        val hasElixirModule = ModuleManager.getInstance(project).modules.any { it.isElixirMixModule() }
        val hasElixirSdk = ElixirSdkLookup.resolveAll(project).isNotEmpty()

        e.presentation.isEnabledAndVisible = hasElixirModule || hasElixirSdk
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Umbrella sub-apps created by `mix new` are written by an external process, so VFS can
        // hold a stale child list that omits their mix.exs - which would make the scan below skip
        // them silently. Refresh first: this is deliberately outside the write action, where a
        // synchronous refresh is not permitted.
        for (module in ModuleManager.getInstance(project).modules) {
            for (mixContentRoot in module.mixContentRoots()) {
                MixProject.refreshUmbrellaSubApps(mixContentRoot.root, async = false)
            }
        }

        var modulesReconfigured = 0
        var foldersAdded = 0

        WriteCommandAction.runWriteCommandAction(project, "Reconfigure Elixir Modules", null, {
            // Collect all content entry URLs upfront so umbrella sub-app detection can skip
            // sub-apps that are already covered by their own content entry.
            val allContentEntryUrls: Set<String> = ModuleManager.getInstance(project).modules
                .flatMap { ModuleRootManager.getInstance(it).contentEntries.map { ce -> ce.url } }
                .toSet()

            for (module in ModuleManager.getInstance(project).modules) {
                if (!module.isElixirMixModule()) continue
                val model = ModuleRootManager.getInstance(module).modifiableModel

                var added = 0

                for (contentEntry in model.contentEntries) {
                    val root = contentEntry.file ?: continue
                    if (root.findChild("mix.exs") == null) continue  // Not a Mix app

                    added += addMissingFolderMarks(contentEntry, root)

                    // Also apply marks for umbrella sub-apps that are not already covered by
                    // their own content entry.
                    added += addMissingUmbrellaSubAppFolderMarks(contentEntry, root, allContentEntryUrls)
                }

                if (added > 0) {
                    foldersAdded += added
                    modulesReconfigured++
                }

                if (model.isChanged) {
                    model.commit()
                } else {
                    model.dispose()
                }
            }
        })

        showResultNotification(project, modulesReconfigured, foldersAdded)
    }

    /**
     * Additively applies missing source folder marks and exclusions from [CANONICAL_FOLDER_MARKS]
     * to [contentEntry] by URL (even if the backing directory does not yet exist on disk).
     * Returns the number of marks added.
     */
    private fun addMissingFolderMarks(contentEntry: ContentEntry, root: VirtualFile): Int =
        applyCanonicalMarksForBaseUrl(contentEntry, root.url)

    /**
     * Scans the `apps/` directory under [root] for umbrella sub-apps that are NOT already
     * covered by their own content entry (i.e. whose URL is absent from [allContentEntryUrls]),
     * and additively applies missing canonical folder marks for each such sub-app on [contentEntry].
     *
     * Returns the total number of marks added across all discovered sub-apps.
     */
    private fun addMissingUmbrellaSubAppFolderMarks(
        contentEntry: ContentEntry,
        root: VirtualFile,
        allContentEntryUrls: Set<String>,
    ): Int {
        val appsDir = root.findChild("apps") ?: return 0

        var added = 0

        for (subAppDir in appsDir.children) {
            if (!subAppDir.isDirectory) continue
            if (subAppDir.findChild("mix.exs") == null) continue

            // Skip sub-apps already covered by their own content entry.
            if (subAppDir.url in allContentEntryUrls) continue

            added += applyCanonicalMarksForBaseUrl(contentEntry, "${root.url}/apps/${subAppDir.name}")
        }

        return added
    }

    /**
     * Applies [CANONICAL_FOLDER_MARKS] to [contentEntry] for every canonical folder relative to
     * [baseUrl].  Existing correct marks are left untouched; incorrect source marks are replaced;
     * missing exclusions are added.  Returns the number of marks added or replaced.
     *
     * This is the single implementation shared by [addMissingFolderMarks] (top-level content root)
     * and [addMissingUmbrellaSubAppFolderMarks] (umbrella sub-apps).
     */
    private fun applyCanonicalMarksForBaseUrl(contentEntry: ContentEntry, baseUrl: String): Int {
        val existingSourceUrls = contentEntry.sourceFolders.associate {
            it.url to if (it.isTestSource) FolderMark.TEST_SOURCES else FolderMark.SOURCES
        }
        val existingExcludeUrls = contentEntry.excludeFolderUrls.toSet()

        var added = 0

        for (canonical in CANONICAL_FOLDER_MARKS) {
            val url = "$baseUrl/${canonical.relativePath}"

            when (canonical.folderMark) {
                FolderMark.SOURCES, FolderMark.TEST_SOURCES -> {
                    val existingMark = existingSourceUrls[url]
                    if (existingMark != canonical.folderMark) {
                        // Remove incorrect mark if present (e.g. Sources instead of Test Sources)
                        if (existingMark != null) {
                            contentEntry.sourceFolders
                                .find { it.url == url }
                                ?.let { contentEntry.removeSourceFolder(it) }
                        }
                        contentEntry.addSourceFolder(url, canonical.folderMark == FolderMark.TEST_SOURCES)
                        added++
                    }
                }

                FolderMark.EXCLUDED -> {
                    if (url !in existingExcludeUrls) {
                        contentEntry.addExcludeFolder(url)
                        added++
                    }
                }
            }
        }

        return added
    }

    private fun showResultNotification(project: Project, modulesReconfigured: Int, foldersAdded: Int) {
        val message = if (modulesReconfigured == 0) {
            "All modules are configured correctly."
        } else {
            val moduleWord = StringUtil.pluralize("module", modulesReconfigured)
            "Reconfigured $modulesReconfigured $moduleWord: $foldersAdded folder ${StringUtil.pluralize("mark", foldersAdded)} applied."
                .also { LOG.info("ReconfigureModuleSetup: $it") }
        }

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Elixir")
            .createNotification("Elixir Module setup", message, NotificationType.INFORMATION)
            .notify(project)
    }
}
