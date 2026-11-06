package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import org.elixir_lang.ui.framework.core.ERLANG_STEP_TITLE
import org.elixir_lang.ui.framework.core.addErlangSdkFromDisk
import org.elixir_lang.ui.framework.core.cancelErlangStep
import org.elixir_lang.ui.framework.core.ensureWelcomeScreenInForeground
import org.elixir_lang.ui.framework.core.pickInErlangStep
import org.elixir_lang.ui.framework.core.sdksAt
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.closeInternalErrors
import org.elixir_lang.ui.sdk_settings.dialogShown
import org.elixir_lang.ui.sdk_settings.failedErlangStepRow
import org.elixir_lang.ui.sdk_settings.launchIdea
import org.elixir_lang.ui.sdk_settings.openInThisWindow
import org.elixir_lang.ui.sdk_settings.stored
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * On IntelliJ IDEA, an Elixir module whose mise config pins Elixir and no Erlang, configured from the editor banner's
 * "Configure from mise": the Erlang step asks. Cancel registers nothing; a pick pairs the new SDK, which becomes the
 * module's.
 *
 * The nightly run has no mise, so a stub first on the IDE's PATH answers the three commands the plugin runs, with what
 * mise 2026.10.22 prints for this config and install. It is a shell script, so the row runs on Linux.
 */
class ToolManagerErlangAskTest {
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `configuring from mise with no Erlang pinned asks the Erlang step`() {
        val rows = Rows()
        launchIdea("ToolManagerErlangAskTest", { withEnv("PATH", stubbedPath()) }) { homes ->
            val install = stageElixirInstall()
            val project = stageProject(NAME, moduleIml("ELIXIR_MODULE"), mapOf(".tool-versions" to "elixir ${Path.of(install).fileName}", "lib/$NAME.ex" to SOURCE, ".idea/workspace.xml" to MISE_ENABLED))
            stubMise(project, "echo '{${misePin("elixir", Path.of(install).fileName, install, project)}}'", listOf("elixir"))
            ensureWelcomeScreenInForeground()
            closeInternalErrors("setup")
            lateinit var erlang: String
            welcomeScreen { erlang = addErlangSdkFromDisk(homes.erlang.a) }
            openInThisWindow(project)
            openFile("lib/$NAME.ex", waitForCodeAnalysis = false)
            cancelRegistersNothing(rows, install)
            aPickPairs(rows, install, erlang)
        }
        rows.assertAllHeld()
    }

    private fun Driver.cancelRegistersNothing(rows: Rows, install: String) {
        val row = "Cancel"
        runCatching {
            clickBannerLabel(LABEL)
            val asked = appears("the Erlang step", 60.seconds) { dialogShown(ERLANG_STEP_TITLE) }
            rows.check(row, "the Erlang step asks", asked)
            cancelErlangStep()
            val registered = sdksAt(install)
            rows.check(row, "no Elixir SDK is registered at the install: $registered", registered.isEmpty())
            val assigned = moduleSdk()
            rows.check(row, "the module has no SDK: '$assigned'", assigned == null)
        }.onFailure { failure -> failedErlangStepRow(rows, row, failure) {} }
    }

    private fun Driver.aPickPairs(rows: Rows, install: String, erlang: String) {
        val row = "pick"
        runCatching {
            clickBannerLabel(LABEL)
            pickInErlangStep(erlang)
            val registered = appears("the Elixir SDK at the install", 60.seconds) { sdksAt(install).isNotEmpty() }
            rows.check(row, "an Elixir SDK is registered at the install", registered)
            val sdk = sdksAt(install).single()
            val paired = stored(sdk).pairedErlang
            rows.check(row, "it is paired with the pick '$erlang': '$paired'", paired == erlang)
            val assigned = appears("the module's SDK", 30.seconds) { moduleSdk() == sdk }
            rows.check(row, "the module's SDK '${moduleSdk()}' is it, '$sdk'", assigned)
        }.onFailure { failure -> failedErlangStepRow(rows, row, failure) {} }
    }

    private companion object {
        const val NAME = "tool_manager_ask"
        const val LABEL = "Configure from mise"

        /** The tool managers are opt-in: mise turned on as Settings saves it. */
        val MISE_ENABLED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="ElixirToolManagerSettings">
                <option name="enabledManagers">
                  <set>
                    <option value="mise" />
                  </set>
                </option>
              </component>
            </project>
        """.trimIndent()

        const val SOURCE = "defmodule ToolManagerAsk do\nend"
    }
}
