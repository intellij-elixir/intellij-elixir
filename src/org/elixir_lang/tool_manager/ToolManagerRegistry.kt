package org.elixir_lang.tool_manager

import org.elixir_lang.tool_manager.mise.MiseToolManager

/**
 * Central list of all built-in [ElixirToolManager] implementations, in priority order.
 *
 * When multiple managers are enabled, [ToolManagerSdkChecker] queries them in this order
 * and uses the first non-null result per content root.
 *
 * To add a new built-in tool manager, add it here. One that applies an environment to the processes the plugin
 * starts also goes in [org.elixir_lang.jps.shared.tool_manager.builtInToolManagerEnvironments], which the JPS build
 * reads too.
 */
val allBuiltInToolManagers: List<ElixirToolManager> = listOf(
    MiseToolManager(),
)
