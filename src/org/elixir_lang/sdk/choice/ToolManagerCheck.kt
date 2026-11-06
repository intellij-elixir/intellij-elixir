package org.elixir_lang.sdk.choice

import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import org.elixir_lang.tool_manager.ElixirToolManager
import org.elixir_lang.tool_manager.ToolEntry
import org.elixir_lang.tool_manager.ToolManagerResult
import org.elixir_lang.tool_manager.ToolManagerVersions
import org.elixir_lang.tool_manager.resolveFirst
import java.nio.file.Files
import java.nio.file.Path

/** What the tool managers, enabled or not, answer for the chooser's folders. */
sealed interface ToolManagerCheck {
    /** Every folder has these pins of [manager]'s, and an Elixir SDK can be made from them. */
    data class Offer(val manager: ElixirToolManager, val versions: ToolManagerVersions) : ToolManagerCheck {
        /** The Elixir pin, installed: an offer is made only for [ToolManagerVersions.isConfigurable] pins. */
        val elixir: ToolEntry = checkNotNull(versions.elixir) { "an offer pins an Elixir" }
    }

    /** A manager could not answer for a folder. [description] is its HTML, saying how to fix it. */
    data class Error(val description: String) : ToolManagerCheck

    companion object {
        /** Asks [toolManagers] about the nearest existing folder of each of [folders]; null when there is nothing to offer. */
        @RequiresBackgroundThread
        fun of(toolManagers: List<ElixirToolManager>, folders: List<Path>): ToolManagerCheck? {
            val results = resolveFirst(toolManagers, folders.mapNotNull(::nearestExisting).distinct()).values
            results.firstNotNullOfOrNull { it as? ToolManagerResult.Error }?.let { return Error(it.description) }
            val pins = results.map { (it as? ToolManagerResult.Success)?.versions ?: return null }
            val first = pins.firstOrNull()?.takeIf { it.isConfigurable } ?: return null
            if (pins.any { !it.samePins(first) }) return null
            return Offer(toolManagers.single { it.name == first.toolManagerName }, first)
        }

        /** A new project's folder does not exist yet; the one it goes in decides its pins. */
        private fun nearestExisting(folder: Path): Path? = generateSequence(folder) { it.parent }.firstOrNull(Files::isDirectory)
    }
}
