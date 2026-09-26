package org.elixir_lang.notification.setup_sdk

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectBundle
import com.intellij.openapi.roots.ui.configuration.SdkPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import org.elixir_lang.ElixirFileType
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.SdkSettingsOpener
import org.elixir_lang.sdk.elixir.SettingsPage
import org.elixir_lang.sdk.elixir.sdk
import org.elixir_lang.sdk.elixir.Type
import org.elixir_lang.tool_manager.ToolManagerSdkAnalyser
import org.elixir_lang.tool_manager.ToolManagerSdkCheckerService
import java.util.function.Function
import javax.swing.JComponent

internal class Provider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        // Quick check without additional read - file type check is thread-safe.
        if (file.fileType !is ElixirFileType) return null

        // collectNotificationData is always called under a platform-held read lock
        // (@RequiresReadLock, see EditorNotificationProvider), so PSI/model access here is safe.
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        if (psiFile.language !== ElixirLanguage) return null

        val module = ModuleUtilCore.findModuleForPsiElement(psiFile)

        val sdk = module?.let { ElixirSdkLookup.resolve(it).sdk } ?: ElixirSdkLookup.resolve(project).sdk
        if (sdk != null) return null

        // No SDK configured. Mise-based suggestion is handled by the status bar widget's
        // notification scan (ElixirEditorBasedSdkWidget), which runs off the read lock on
        // Dispatchers.IO and surfaces a balloon notification with a one-click configure action.
        // This panel remains as a persistent per-file indicator for manual setup, but is a candidate
        // for removal in the future if the Experimental Widget becomes stable.
        return when {
            module != null && ModuleType.get(module).id == ELIXIR_MODULE_TYPE_ID ->
                Function { createModulePanel(project, module) }
            module != null && ProcessOutput.isSmallIde ->
                Function { createSmallIDEFacetPanel(project, module) }
            module != null ->
                Function { createProjectPanel(project) }
            else -> null
        }
    }
}

/**
 * Opens where a module is given its SDK in the running IDE via [SdkSettingsOpener]: the Elixir settings page in
 * small IDEs (RubyMine, etc.) or the Project Structure dialog in full IDEs.
 *
 * [showModuleSettings] previously called [com.intellij.openapi.roots.ui.configuration.ProjectSettingsService.openModuleSettings],
 * which is a no-op in small IDEs (their `ProjectSettingsService` reports `canOpenModuleSettings() == false`),
 * so the "Setup Elixir Module SDK" link silently did nothing in RubyMine and the other small IDEs.
 */
private fun openSdkSettings(project: Project) {
    val dataContext = SimpleDataContext.getProjectContext(project)
    val event = AnActionEvent.createEvent(dataContext, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
    SdkSettingsOpener.getInstance().open(event, SettingsPage.MODULE_SDKS)
}

fun showFacetSettings(project: Project) {
    openSdkSettings(project)
}

fun showModuleSettings(
    project: Project,
    @Suppress("UNUSED_PARAMETER") module: Module,
) {
    openSdkSettings(project)
}

private fun showProjectSettings(project: Project) {
    SdkPopupFactory.newBuilder()
        .withProject(project)
        .withSdkType(Type.instance)
        .updateProjectSdkFromSelection()
        .buildPopup()
        .showInFocusCenter()
}

private fun createSmallIDEFacetPanel(
    project: Project,
    module: Module,
): EditorNotificationPanel =
    EditorNotificationPanel().apply {
        text = "Elixir Facet SDK is not defined"
        // Added before the "Setup" label so it renders to its LEFT. The links panel is right-anchored
        // (BorderLayout.EAST) and packs left->right by add order, so the last-added label stays pinned
        // to the right edge. Adding the (delayed) tool-manager label last would shift "Setup" left
        // ~10s after startup when the mise scan completes; adding it first keeps "Setup" fixed.
        addConfigureFromToolManagerLabel(project, module)
        @Suppress("DialogTitleCapitalization")
        createActionLabel("Setup Elixir Facet SDK") {
            showFacetSettings(project)
        }
    }

private fun createModulePanel(
    project: Project,
    module: Module,
): EditorNotificationPanel =
    EditorNotificationPanel().apply {
        text = "Elixir Module SDK is not defined"
        // Added before the "Setup" label so it renders to its LEFT and "Setup" stays pinned to the
        // right edge - see createSmallIDEFacetPanel for why (avoids the ~10s post-startup jump).
        addConfigureFromToolManagerLabel(project, module)
        @Suppress("DialogTitleCapitalization")
        createActionLabel("Setup Elixir Module SDK") {
            showModuleSettings(project, module)
        }
    }

/**
 * Adds a "Configure from &lt;tool manager&gt;" action label to this panel when the tool manager
 * (e.g. mise) has an installed Elixir version available for [module]. No-op when the tool-manager
 * subsystem is unavailable, disabled, or has no assignment for the module.
 *
 * Reads the cached (immutable) analysis snapshot, which is safe under the platform read lock in
 * which `collectNotificationData` runs. When the first scan has not completed yet the label is
 * simply absent; [ToolManagerSdkAnalyser] re-renders banners via `updateAllNotifications()` once
 * analysis completes. Clicking configures the SDK via [ToolManagerSdkCheckerService.configureSdks]
 * (which on small IDEs assigns the Facet SDK).
 */
private fun EditorNotificationPanel.addConfigureFromToolManagerLabel(project: Project, module: Module) {
    val analyser = ToolManagerSdkAnalyser.getInstanceIfRegistered(project) ?: return
    val assignments = analyser.latestAnalysisResult
        ?.tmAssignments
        ?.filterKeys { it == module.name }
        ?.filterValues { it.elixir?.installed == true }
        .orEmpty()
    if (assignments.isEmpty()) return

    val toolName = assignments.values.first().toolManagerName
    @Suppress("DialogTitleCapitalization")
    createActionLabel("Configure from $toolName") {
        ToolManagerSdkCheckerService.getInstance(project).configureSdks(assignments)
    }
}

private fun createProjectPanel(project: Project): EditorNotificationPanel =
    EditorNotificationPanel().apply {
        text = "Elixir SDK is not defined"
        createActionLabel(ProjectBundle.message("project.sdk.setup")) {
            showProjectSettings(project)
        }
    }
