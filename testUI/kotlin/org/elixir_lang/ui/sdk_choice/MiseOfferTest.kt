package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.elements.JCheckBoxUi
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import org.elixir_lang.ui.framework.core.CHOOSER_TITLE
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.sdksAt
import org.elixir_lang.ui.framework.core.waitUntilReady
import org.elixir_lang.ui.sdk_settings.Home
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.cancelSettings
import org.elixir_lang.ui.sdk_settings.closeInternalErrors
import org.elixir_lang.ui.sdk_settings.failedErlangStepRow
import org.elixir_lang.ui.sdk_settings.launchIdea
import org.elixir_lang.ui.sdk_settings.launchPyCharm
import org.elixir_lang.ui.sdk_settings.openInThisWindow
import org.elixir_lang.ui.sdk_settings.stored
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
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
        val row = "IU"
        onIdeaWithThePins(row) { rows, install, erlang ->
            takeTheOffer(rows, row, install, erlang)
            val set = appears("the module's SDK", 30.seconds) { moduleSdk() in sdksAt(install) }
            rows.check(row, "the module's SDK '${moduleSdk()}' is at mise's install", set)
            pairedWithMisesErlang(rows, row, moduleSdk(), erlang)
            rows.check(row, "Tool Managers shows mise on", toolManagersShowMiseOn())
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `on PyCharm taking mise's offer names it in the facet and turns mise on`() {
        val row = "PY"
        onPyCharmWithThePins(row) { rows, install, erlang ->
            takeTheOffer(rows, row, install, erlang)
            val named = appears("the facet to name an SDK", 30.seconds) { facetSdkName() in sdksAt(install) }
            rows.check(row, "the facet names '${facetSdkName()}', at mise's install", named)
            pairedWithMisesErlang(rows, row, facetSdkName(), erlang)
            rows.check(row, "Tool Managers shows mise on", toolManagersShowMiseOn())
        }
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
                askTheChooser(rows, row, SOURCE_PATH)
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

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `on IntelliJ IDEA the second radio and a row give that row with the offer's boxes disabled`() {
        val row = "IU-another"
        onIdeaWithThePins(row) { rows, install, erlang -> takeTheOfferThenUseAnother(rows, row, install, erlang) }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `on PyCharm the second radio and a row give that row with the offer's boxes disabled`() {
        val row = "PY-another"
        onPyCharmWithThePins(row) { rows, install, erlang -> takeTheOfferThenUseAnother(rows, row, install, erlang) }
    }

    /** IntelliJ IDEA open on a module with no SDK, whose mise config pins a staged Elixir and Erlang: [body] is row [row]. */
    private fun onIdeaWithThePins(row: String, body: Driver.(rows: Rows, install: String, erlang: Home) -> Unit) {
        val rows = Rows()
        launchIdea("MiseOfferTest-$row", { withEnv("PATH", stubbedPath()) }) { homes ->
            val install = stageElixirInstall()
            val project = stageProject(NAME, moduleIml("ELIXIR_MODULE"), mapOf(SOURCE_PATH to SOURCE))
            pinWithMise(project, install, homes.erlang)
            open(project)
            runCatching { body(rows, install, homes.erlang) }.onFailure { failure -> failed(rows, row, failure) }
        }
        rows.assertAllHeld()
    }

    /** PyCharm open on a facet with no SDK, whose mise config pins a staged Elixir and Erlang: [body] is row [row]. */
    private fun onPyCharmWithThePins(row: String, body: Driver.(rows: Rows, install: String, erlang: Home) -> Unit) {
        val rows = Rows()
        launchPyCharm("MiseOfferTest-$row", { withEnv("PATH", stubbedPath()) }) { homes ->
            val install = stageElixirInstall()
            val project = stageProject(NAME, moduleIml("PYTHON_MODULE", "<configuration />"), mapOf(SOURCE_PATH to SOURCE))
            pinWithMise(project, install, homes.erlang)
            closeInternalErrors("setup")
            runCatching {
                openInThisWindow(project)
                body(rows, install, homes.erlang)
            }.onFailure { failure -> failed(rows, row, failure) }
        }
        rows.assertAllHeld()
    }

    /** The chooser offers mise's Elixir at [install] and its [erlang]; taken, then [then] with the box keeping the SDKs in step. */
    private fun Driver.withTheOfferTaken(rows: Rows, row: String, install: String, erlang: Home, then: UiComponent.(JCheckBoxUi) -> Unit) {
        askTheChooser(rows, row, SOURCE_PATH)
        ui.dialog(xQuery { byTitle(CHOOSER_TITLE) }) {
            waitUntilReady()
            val inStep = takeMisesOffer(rows, row, install, erlang)
            rows.check(row, "its box can be ticked once the offer is taken", appears("the box enabled", 30.seconds) { inStep.isEnabled() })
            then(inStep)
        }
    }

    /** The offer taken, with the tool manager's box ticked, and OK. */
    private fun Driver.takeTheOffer(rows: Rows, row: String, install: String, erlang: Home) =
        withTheOfferTaken(rows, row, install, erlang) { inStep ->
            inStep.check()
            clickButtonText("OK")
        }

    /** The offer taken, then "Use another SDK:" and the list's only row: that row picked, OK on and the offer's box disabled. */
    private fun Driver.takeTheOfferThenUseAnother(rows: Rows, row: String, install: String, erlang: Home) =
        withTheOfferTaken(rows, row, install, erlang) { inStep ->
            x(xQuery { byAccessibleName("Use another SDK:") }).click()
            rows.check(row, "the second radio disables the box", appears("the box disabled", 30.seconds) { !inStep.isEnabled() })
            val list = chooserList()
            list.clickItemAtIndex(0)
            rows.check(row, "the row is picked: ${list.selectedItems}", list.selectedItems.isNotEmpty())
            rows.check(row, "OK is on", x { byAccessibleName("OK") }.isEnabled())
            rows.check(row, "and the box stays disabled", !inStep.isEnabled())
            clickButtonText("Cancel")
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
