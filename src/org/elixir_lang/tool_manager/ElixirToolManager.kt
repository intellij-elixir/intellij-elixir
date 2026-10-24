package org.elixir_lang.tool_manager

import java.nio.file.Path

/**
 * A tool manager that can resolve installed Elixir/Erlang versions for a project directory.
 *
 * Implementations (mise, asdf, ...) must:
 * - Return `null` from [resolveVersions] when the manager is not applicable for the given
 *   directory (not installed, no config file, transient subprocess failure, etc.).
 * - Return [ToolManagerResult.Error] when the manager is applicable but encountered an
 *   actionable error.  The implementation is responsible for providing a human-readable
 *   [ToolManagerResult.Error.description] that explains the problem and how to fix it, as HTML with anything it
 *   inserts, such as a config path, escaped.
 * - Return [ToolManagerResult.Success] when versions were successfully resolved.
 * - Be stateless with respect to the project; all context is provided through [resolveVersions].
 *
 * [name] is the **stable persistence key** written to the workspace file via [ToolManagerSettings].
 * Do not change it after the first release that ships this tool manager.
 */
interface ElixirToolManager {
    /** Stable persistence key. Used by [ToolManagerSettings] and as a fallback display label. */
    val name: String

    /** Human-readable label shown in the Settings UI (may be localised in the future). */
    val displayName: String

    /**
     * Resolves tool versions for [contentRoot] by querying the tool manager.
     *
     * **Threading**: must NOT be called on the EDT or under a read lock - implementations
     * may spawn subprocesses or perform file I/O. Call only from [kotlinx.coroutines.Dispatchers.IO].
     *
     * Returns `null` if this tool manager is not applicable for [contentRoot].
     * Returns [ToolManagerResult.Error] when applicable but an actionable error occurred.
     * Returns [ToolManagerResult.Success] when versions are successfully resolved.
     */
    fun resolveVersions(contentRoot: Path): ToolManagerResult?

    /**
     * Returns a [ToolManagerRefreshTrigger] that can detect when this tool manager's
     * configuration has changed and a re-scan should occur, or `null` if this manager
     * does not support automatic change detection.
     *
     * Called once per scan cycle to obtain a fresh trigger (so dynamically discovered watch
     * targets stay up to date).  The default implementation returns `null`.
     *
     * The returned trigger's [ToolManagerRefreshTrigger.install] method is called on a
     * background IO thread immediately after the scan completes.
     */
    fun createRefreshTrigger(): ToolManagerRefreshTrigger? = null
}
