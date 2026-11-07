package org.elixir_lang.tool_manager

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import org.elixir_lang.tool_manager.mise.MiseToolManager

/**
 * Every built-in [ElixirToolManager], in priority order: when several are enabled, [ToolManagerSdkChecker] queries
 * them in this order and uses the first non-null result per content root.
 *
 * To add a new built-in tool manager, add it here. One that applies an environment to the processes the plugin
 * starts also goes in [org.elixir_lang.jps.shared.tool_manager.builtInToolManagerEnvironments], which the JPS build
 * reads too.
 *
 * A test replaces the service with one built from its own list. A light service's class must be final.
 */
@Service
class BuiltInToolManagers(val all: List<ElixirToolManager>) {
    constructor() : this(listOf(MiseToolManager()))

    companion object {
        fun getInstance(): BuiltInToolManagers = ApplicationManager.getApplication().service()
    }
}
