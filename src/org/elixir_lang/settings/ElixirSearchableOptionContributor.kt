package org.elixir_lang.settings

import com.intellij.ide.ui.search.SearchableOptionContributor
import com.intellij.ide.ui.search.SearchableOptionProcessor

/**
 * Keeps Elixir settings discoverable in search without requiring Elixir-prefixed labels.
 */
internal class ElixirSearchableOptionContributor : SearchableOptionContributor() {
    override fun processOptions(processor: SearchableOptionProcessor) {
        addAliases(
            processor = processor,
            configurableId = SettingsPageId.ELIXIR.id,
            configurableDisplayName = "Elixir",
            hit = "Elixir",
            text = "elixir language framework settings"
        )

        addAliases(
            processor = processor,
            configurableId = SettingsPageId.CREDO.id,
            configurableDisplayName = "Credo",
            hit = "Credo",
            text = "elixir credo lint linter"
        )

        addAliases(
            processor = processor,
            configurableId = SettingsPageId.DIALYZER.id,
            configurableDisplayName = "Dialyzer",
            hit = "Dialyzer",
            text = "elixir dialyzer typespec static analysis"
        )

        addAliases(
            processor = processor,
            configurableId = SettingsPageId.EXPERIMENTAL.id,
            configurableDisplayName = "Experimental Settings",
            hit = "Experimental Settings",
            text = "elixir experimental settings liveview heex sigil injection mix deps status bar widget"
        )

        addAliases(
            processor = processor,
            configurableId = SettingsPageId.ELIXIR_SDKS.id,
            configurableDisplayName = "SDKs",
            hit = "SDKs",
            text = "elixir sdk interpreter"
        )

        addAliases(
            processor = processor,
            configurableId = SettingsPageId.ERLANG_SDKS.id,
            configurableDisplayName = "Internal Erlang SDKs",
            hit = "Internal Erlang SDKs",
            text = "elixir erlang sdk otp"
        )

        addAliases(
            processor = processor,
            configurableId = SettingsPageId.TOOL_MANAGERS.id,
            configurableDisplayName = "Tool Managers",
            hit = "Tool Managers",
            text = "elixir tool manager mise asdf sdk version automatic configure experimental environment variables env path"
        )
    }

    private fun addAliases(
        processor: SearchableOptionProcessor,
        configurableId: String,
        configurableDisplayName: String,
        hit: String,
        text: String
    ) {
        processor.addOptions(text, null, hit, configurableId, configurableDisplayName, true)
    }
}
