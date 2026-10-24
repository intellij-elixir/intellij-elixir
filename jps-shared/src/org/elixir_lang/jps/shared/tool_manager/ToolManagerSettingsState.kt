package org.elixir_lang.jps.shared.tool_manager

/**
 * The persisted tool manager settings, read by the IDE and by the JPS build process from the
 * `ElixirToolManagerSettings` component in the user's `.idea/workspace.xml`.
 *
 * Fields must be `var` for the reflection-based XML serialiser.
 */
class ToolManagerSettingsState {
    var enabledManagers: MutableSet<String> = mutableSetOf()

    /** Managers whose environment is applied to the processes the plugin starts. Only honoured while also enabled. */
    var environmentManagers: MutableSet<String> = mutableSetOf()

    fun appliesEnvironment(name: String): Boolean = name in enabledManagers && name in environmentManagers

    fun copy(): ToolManagerSettingsState = ToolManagerSettingsState().also {
        it.enabledManagers.addAll(enabledManagers)
        it.environmentManagers.addAll(environmentManagers)
    }

    companion object {
        const val FILE_NAME = "workspace.xml"

        /** Where versions before the settings moved to the workspace saved them, shared with the project. */
        const val SHARED_FILE_NAME = "elixir.xml"
        const val COMPONENT_NAME = "ElixirToolManagerSettings"
    }
}
