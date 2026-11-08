package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import org.elixir_lang.ui.framework.core.CHOOSER_TITLE
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.ensureWelcomeScreenInForeground
import org.elixir_lang.ui.framework.core.sdksAt
import org.elixir_lang.ui.framework.core.waitUntilReady
import org.elixir_lang.ui.sdk_settings.Home
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.cancelSettings
import org.elixir_lang.ui.sdk_settings.closeInternalErrors
import org.elixir_lang.ui.sdk_settings.dialogShown
import org.elixir_lang.ui.sdk_settings.failedErlangStepRow
import org.elixir_lang.ui.sdk_settings.launchIdea
import org.elixir_lang.ui.sdk_settings.launchPyCharm
import org.elixir_lang.ui.sdk_settings.openInThisWindow
import org.elixir_lang.ui.sdk_settings.stored
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * A module with no Elixir SDK whose mise config pins an installed Elixir and Erlang, with the tool manager off: the
 * banner's "Choose Elixir SDK..." offers mise's pins. Taken with "Keep the SDKs in step with mise" ticked, they give the
 * module, or on PyCharm the facet, an Elixir SDK at mise's install paired with an Erlang SDK at mise's, and Tool Managers
 * shows mise on. A config mise won't read until it is trusted shows mise's message instead of the offer.
 *
 * As in [ToolManagerErlangAskTest], a stub first on the IDE's PATH answers as mise does. It is a shell script, so the
 * rows run on Linux.
 */
class MiseOfferTest {
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `on IntelliJ IDEA taking mise's offer sets the module's SDK and turns mise on`() {
        val rows = Rows()
        val row = "IU"
        launchIdea("MiseOfferTest-IU", { withEnv("PATH", stubbedPath()) }) { homes ->
            val install = stageElixirInstall()
            val project = stageProject(NAME, moduleIml("ELIXIR_MODULE"), mapOf(SOURCE_PATH to SOURCE))
            pinWithMise(project, install, homes.erlang)
            open(project)
            runCatching {
                takeTheOffer(rows, row, install, homes.erlang)
                val set = appears("the module's SDK", 30.seconds) { moduleSdk() in sdksAt(install) }
                rows.check(row, "the module's SDK '${moduleSdk()}' is at mise's install", set)
                pairedWithMisesErlang(rows, row, moduleSdk(), homes.erlang)
                rows.check(row, "Tool Managers shows mise on", toolManagersShowMiseOn())
            }.onFailure { failure -> failed(rows, row, failure) }
        }
        rows.assertAllHeld()
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `on PyCharm taking mise's offer names it in the facet and turns mise on`() {
        val rows = Rows()
        val row = "PY"
        launchPyCharm("MiseOfferTest-PY", { withEnv("PATH", stubbedPath()) }) { homes ->
            val install = stageElixirInstall()
            val project = stageProject(NAME, moduleIml("PYTHON_MODULE", "<configuration />"), mapOf(SOURCE_PATH to SOURCE))
            pinWithMise(project, install, homes.erlang)
            closeInternalErrors("setup")
            runCatching {
                openInThisWindow(project)
                takeTheOffer(rows, row, install, homes.erlang)
                val named = appears("the facet to name an SDK", 30.seconds) { facetSdkName() in sdksAt(install) }
                rows.check(row, "the facet names '${facetSdkName()}', at mise's install", named)
                pairedWithMisesErlang(rows, row, facetSdkName(), homes.erlang)
                rows.check(row, "Tool Managers shows mise on", toolManagersShowMiseOn())
            }.onFailure { failure -> failed(rows, row, failure) }
        }
        rows.assertAllHeld()
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `on IntelliJ IDEA an untrusted config shows mise's message instead of the offer`() {
        val rows = Rows()
        val row = "untrusted"
        launchIdea("MiseOfferTest-IU-untrusted", { withEnv("PATH", stubbedPath()) }) { _ ->
            val project = stageProject(NAME, moduleIml("ELIXIR_MODULE"), mapOf(SOURCE_PATH to SOURCE, "mise.toml" to "[env]\nMIX_ENV = \"dev\"\n"))
            val config = project.resolve("mise.toml")
            // What mise prints for a config with `[env]` it has not been told to trust.
            stubMise(project, """echo 'mise ERROR Config files in $config are not trusted.' >&2; exit 1""", TOOLS)
            open(project)
            runCatching {
                askTheChooser(rows, row)
                ui.dialog(xQuery { byTitle(CHOOSER_TITLE) }) {
                    waitUntilReady()
                    val message = x("//div[contains(@visible_text, 'is not trusted') or contains(@accessiblename, 'is not trusted')]")
                    rows.check(row, "the chooser says the config is not trusted", appears("the message", 30.seconds) { message.present() })
                    rows.check(row, "with a 'Check again' link", x(xQuery { byAccessibleName("Check again") }).present())
                    rows.check(row, "and no offer", !x("//div[starts-with(@accessiblename, \"Use mise's\")]").present())
                    clickButtonText("Cancel")
                }
            }.onFailure { failure -> failed(rows, row, failure) }
        }
        rows.assertAllHeld()
    }

    private fun Driver.open(project: Path) {
        ensureWelcomeScreenInForeground()
        closeInternalErrors("setup")
        openInThisWindow(project)
    }

    /** The banner's "Choose Elixir SDK..." clicked, with the source file open, and the chooser shown. */
    private fun Driver.askTheChooser(rows: Rows, row: String) {
        openFile(SOURCE_PATH, waitForCodeAnalysis = false)
        clickBannerLabel(CHOOSE_LABEL)
        rows.check(row, "the banner asks the chooser", appears("the chooser", 30.seconds) { dialogShown(CHOOSER_TITLE) })
    }

    /** The chooser offers mise's Elixir at [install] and its [erlang]; taken, with the tool manager's box ticked, and OK. */
    private fun Driver.takeTheOffer(rows: Rows, row: String, install: String, erlang: Home) {
        askTheChooser(rows, row)
        ui.dialog(xQuery { byTitle(CHOOSER_TITLE) }) {
            waitUntilReady()
            val inStep = takeMisesOffer(rows, row, install, erlang)
            rows.check(row, "its box can be ticked once the offer is taken", appears("the box enabled", 30.seconds) { inStep.isEnabled() })
            inStep.check()
            clickButtonText("OK")
        }
    }

    /** [elixir] is paired with an Erlang SDK at mise's [erlang] install. */
    private fun Driver.pairedWithMisesErlang(rows: Rows, row: String, elixir: String?, erlang: Home) {
        val paired = elixir?.let { stored(it).pairedErlang }
        rows.check(row, "'$elixir' is paired with '$paired', at mise's Erlang", paired != null && paired in sdksAt(erlang.b))
    }

    private fun Driver.failed(rows: Rows, row: String, failure: Throwable) =
        failedErlangStepRow(rows, row, failure) {
            cancelDialogs(CHOOSER_TITLE)
            cancelSettings()
        }

    private companion object {
        const val NAME = "mise_offer"
        const val SOURCE_PATH = "lib/$NAME.ex"
        const val SOURCE = "defmodule MiseOffer do\nend"
        val TOOLS = listOf("elixir", "erlang")
    }
}
