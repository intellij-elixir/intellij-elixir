package org.elixir_lang.tool_manager

/**
 * Resolved tool versions for one module content root, as reported by a specific tool manager.
 *
 * [toolManagerName] is the stable identifier of the tool manager that produced this result
 * (e.g. `"mise"`). It is used for display in notifications and for distinguishing results
 * when multiple tool managers are active.
 */
interface ToolManagerVersions {
    val toolManagerName: String
    val elixir: ToolEntry?
    val erlang: ToolEntry?

    /** Whether an Elixir SDK can be made from these pins: the Elixir pin is installed, and so is the Erlang one if any. */
    val isConfigurable: Boolean get() = elixir?.installed == true && erlang?.installed != false

    /** Whether [other] is the same tool manager's pins of the same Elixir and Erlang. */
    fun samePins(other: ToolManagerVersions): Boolean =
        toolManagerName == other.toolManagerName && elixir == other.elixir && erlang == other.erlang
}
