package org.elixir_lang.sdk.elixir

import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable
import com.intellij.ui.navigation.Place
import org.elixir_lang.settings.SettingsPageId

/** Which settings a notice sends the user to; IntelliJ IDEA has one Project Structure for both. */
enum class SettingsPage { MODULE_SDKS, SDKS }

interface SdkSettingsOpener {
    fun open(event: AnActionEvent, page: SettingsPage = SettingsPage.SDKS)

    fun targetName(): String

    companion object {
        fun getInstance(): SdkSettingsOpener =
            ApplicationManager.getApplication().getService(SdkSettingsOpener::class.java)
    }
}

internal class SettingsSdkSettingsOpener : SdkSettingsOpener {
    override fun open(event: AnActionEvent, page: SettingsPage) {
        val project = event.project ?: ProjectManager.getInstance().openProjects.firstOrNull()
        val id = when (page) {
            SettingsPage.MODULE_SDKS -> SettingsPageId.ELIXIR.id
            SettingsPage.SDKS -> SettingsPageId.ELIXIR_SDKS.id
        }
        // By id: a lookup by class builds every page ahead of it, and some, such as the IDE's data-sharing consents, do
        // slow work when built.
        ShowSettingsUtilImpl.showSettingsDialog(project, id, null)
    }

    override fun targetName(): String = "Settings"
}

internal class ProjectStructureSdkSettingsOpener : SdkSettingsOpener {
    override fun open(event: AnActionEvent, page: SettingsPage) {
        val project = event.project ?: ProjectManager.getInstance().defaultProject
        val config = ProjectStructureConfigurable.getInstance(project)
        // The default project has no Modules page.
        val place = if (page == SettingsPage.MODULE_SDKS && !project.isDefault) {
            config.createModulesPlace()
        } else {
            Place().putPath(ProjectStructureConfigurable.CATEGORY, config.jdkConfig)
        }
        ShowSettingsUtil.getInstance().editConfigurable(project, config) { config.navigateTo(place, true) }
    }

    override fun targetName(): String = "Project Structure"
}
