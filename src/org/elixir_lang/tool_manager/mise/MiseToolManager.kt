package org.elixir_lang.tool_manager.mise

import com.intellij.openapi.util.text.StringUtil
import org.elixir_lang.jps.shared.tool_manager.mise.MiseEnvironment
import org.elixir_lang.mise.Mise
import org.elixir_lang.mise.MiseResult
import org.elixir_lang.tool_manager.ElixirToolManager
import org.elixir_lang.tool_manager.ToolManagerRefreshTrigger
import org.elixir_lang.tool_manager.ToolManagerResult
import java.nio.file.Path

/**
 * [ElixirToolManager] implementation backed by [mise](https://mise.jdx.dev/).
 *
 * Delegates to [Mise.resolveVersions] and maps the result to [ToolManagerResult]:
 * - `null`                        -> `null`        (mise unavailable for this root)
 * - [MiseResult.UntrustedConfig]  -> [ToolManagerResult.Error]
 * - [MiseResult.Success]          -> [ToolManagerResult.Success]
 *
 * All threading constraints of [Mise.resolveVersions] apply here - must not be called on
 * the EDT or under a read lock.
 */
class MiseToolManager : ElixirToolManager {
    override val name: String = NAME
    override val displayName: String = "mise"

    override fun resolveVersions(contentRoot: Path): ToolManagerResult? =
        when (val result = Mise.resolveVersions(contentRoot)) {
            null -> null
            is MiseResult.UntrustedConfig ->
                ToolManagerResult.Error(
                    toolManagerName = NAME,
                    description = untrustedConfigDescription(result.configFilePath),
                )
            is MiseResult.Success ->
                ToolManagerResult.Success(MiseToolManagerVersions(result.versions))
        }

    /**
     * Returns [MiseRefreshTrigger], which watches mise config files for changes and triggers
     * a re-scan whenever a config file is modified, deleted, or a new one is created.
     */
    override fun createRefreshTrigger(): ToolManagerRefreshTrigger = MiseRefreshTrigger

    companion object {
        /** Stable persistence key - do not rename after first release. */
        const val NAME = MiseEnvironment.NAME

        internal fun untrustedConfigDescription(configFilePath: String): String =
            "Config file '${StringUtil.escapeXmlEntities(configFilePath)}' is not trusted. " +
                "Run <code>mise trust</code> in the project directory, then reload."
    }
}
