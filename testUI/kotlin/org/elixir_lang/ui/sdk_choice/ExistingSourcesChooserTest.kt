package org.elixir_lang.ui.sdk_choice

import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.failedErlangStepRow
import org.elixir_lang.ui.sdk_settings.launchIdea
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * IntelliJ IDEA's File > New > Project from Existing Sources, "Create project from existing sources", over a folder of
 * Elixir sources: the step the plugin's project structure detector adds asks with the chooser, listing each Elixir SDK
 * with its Erlang SDK and selecting none, and the pick becomes the module SDK of the project's module.
 */
class ExistingSourcesChooserTest {
    @Test
    fun `Create project from existing sources asks with the chooser`() {
        val rows = Rows()
        launchIdea("ExistingSourcesChooserTest") { homes ->
            val (erlang, elixir) = addSdksOnWelcomeScreen(homes.erlang.a, homes.elixir.a)
            val sources = stageSources(DETECTED, mapOf("lib/$DETECTED.ex" to "defmodule Detected do\nend"))
            runCatching {
                fromExistingSources("ImportProject", sources, IMPORT_PROJECT, "from existing sources")
                asksWithTheChooser(rows, ROW, IMPORT_PROJECT, elixir, erlang)
                waitForProject(sources)
                val set = appears("the module's SDK", 30.seconds) { moduleSdk() == elixir }
                rows.check(ROW, "the module's SDK '${moduleSdk()}' is the pick '$elixir'", set)
            }.onFailure { failure -> failedErlangStepRow(rows, ROW, failure) { cancelDialogs(IMPORT_PROJECT) } }
        }
        rows.assertAllHeld()
    }

    private companion object {
        const val ROW = "existing sources"
        const val DETECTED = "detected"
        const val IMPORT_PROJECT = "Import Project"
    }
}
