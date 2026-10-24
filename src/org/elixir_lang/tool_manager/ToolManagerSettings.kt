package org.elixir_lang.tool_manager

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import org.elixir_lang.jps.shared.tool_manager.ToolManagerSettingsState

/** Listener notified whenever a tool manager is enabled or disabled. */
fun interface ToolManagerSettingsListener {
    fun toolManagerSettingsChanged()
}

/**
 * Settings controlling which tool managers are active in a project, kept per user in the project's workspace file
 * rather than shared with the project: applying a manager's environment fails every launch where that manager is
 * missing. Settings saved by earlier versions in the shared `.idea/elixir.xml` are read from there and moved on save.
 *
 * Registered in the base `plugin.xml`, so available in every IDE including small IDEs
 * (RubyMine, etc.); SDK configuration is small-IDE-safe (see
 * [ToolManagerSdkChecker.configureSdks], which assigns the Facet SDK on small IDEs).
 * No `@Service` annotation is present so the platform does not auto-register this class
 * with default settings; it is registered explicitly with the desired storage.
 *
 * **Default behaviour**: all tool managers are **disabled** (opt-in model).
 * A tool manager must be explicitly enabled by the user before it is consulted
 * during the SDK notification scan.  This ensures that experimental managers
 * (such as mise) do not run without user consent.
 *
 * An empty [ToolManagerSettingsState.enabledManagers] set therefore means "no tool managers are active".
 */
@Suppress("LightServiceMigrationCode")
@State(
    name = ToolManagerSettingsState.COMPONENT_NAME,
    storages = [
        Storage(StoragePathMacros.WORKSPACE_FILE),
        Storage(value = ToolManagerSettingsState.SHARED_FILE_NAME, deprecated = true),
    ]
)
internal class ToolManagerSettings : PersistentStateComponent<ToolManagerSettingsState> {

    private var myState = ToolManagerSettingsState()

    override fun getState(): ToolManagerSettingsState = myState

    override fun loadState(state: ToolManagerSettingsState) {
        myState = state
    }

    /** Returns `true` when [manager] has been explicitly enabled by the user. */
    fun isEnabled(manager: ElixirToolManager): Boolean =
        manager.name in myState.enabledManagers

    fun setEnabled(manager: ElixirToolManager, enabled: Boolean) {
        if (enabled) myState.enabledManagers.add(manager.name)
        else myState.enabledManagers.remove(manager.name)
    }

    /** Whether the user asked for [manager]'s environment, which applies only while the manager is also enabled. */
    fun isEnvironmentEnabled(manager: ElixirToolManager): Boolean =
        manager.name in myState.environmentManagers

    fun setEnvironmentEnabled(manager: ElixirToolManager, enabled: Boolean) {
        if (enabled) myState.environmentManagers.add(manager.name)
        else myState.environmentManagers.remove(manager.name)
    }

    companion object {
        @JvmField
        val SETTINGS_CHANGED_TOPIC: Topic<ToolManagerSettingsListener> = Topic.create(
            "ToolManagerSettings.changed",
            ToolManagerSettingsListener::class.java
        )

        fun getInstance(project: Project): ToolManagerSettings = project.service()
    }
}
