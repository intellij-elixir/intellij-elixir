package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.jBlist
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.waitUntilReady
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.dialogShown
import org.elixir_lang.ui.sdk_settings.failedErlangStepRow
import org.elixir_lang.ui.sdk_settings.launchIdea
import org.elixir_lang.ui.sdk_settings.launchPyCharm
import org.elixir_lang.ui.sdk_settings.openInThisWindow
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

/**
 * A module with no Elixir SDK, an `.ex` file of it open: the editor banner's "Choose Elixir SDK..." asks the chooser,
 * which lists the Elixir SDK with its Erlang SDK and enables OK only once it is picked. OK writes the pick where the IDE
 * reads it: on PyCharm the facet names it; on IntelliJ IDEA an Elixir module gets it as its own SDK, and a module of
 * another type, here one PyCharm set up, inherits it as the project SDK, with no SDK entry of its own in its `.iml`.
 */
class BannerChooserTest {
    @Test
    fun `on PyCharm the chooser names the pick in the facet`() {
        val rows = Rows()
        val row = "PyCharm"
        launchPyCharm("BannerChooserTest-PY") { homes ->
            val (erlang, elixir) = addSdksOnPage(homes.erlang.a, homes.elixir.a)
            val project = stageProject(NAME, moduleIml("PYTHON_MODULE", "<configuration />"), mapOf(SOURCE_PATH to SOURCE))
            runCatching {
                openInThisWindow(project)
                chooseFromBanner(rows, row, elixir, erlang)
                val named = appears("the facet to name the pick", 30.seconds) { facetSdkName() == elixir }
                rows.check(row, "the facet names '$elixir': '${facetSdkName()}'", named)
            }.onFailure { failure -> failed(rows, row, failure) }
        }
        rows.assertAllHeld()
    }

    @Test
    fun `on IntelliJ IDEA the chooser sets an Elixir module's own SDK`() {
        val rows = Rows()
        val row = "ELIXIR_MODULE"
        launchIdea("BannerChooserTest-IU-elixir") { homes ->
            val (erlang, elixir) = addSdksOnWelcomeScreen(homes.erlang.a, homes.elixir.a)
            val project = stageProject(NAME, moduleIml("ELIXIR_MODULE"), mapOf(SOURCE_PATH to SOURCE))
            runCatching {
                openInThisWindow(project)
                chooseFromBanner(rows, row, elixir, erlang)
                val set = appears("the module's SDK", 30.seconds) { moduleSdk() == elixir }
                rows.check(row, "the module's SDK '${moduleSdk()}' is '$elixir'", set)
                rows.check(row, "the module has its own SDK, not the project's", !moduleInheritsSdk())
                val saved = savedTo(project.resolve("$NAME.iml"), """<orderEntry type="jdk" jdkName="$elixir"""")
                rows.check(row, "the .iml names '$elixir' as the module's SDK", saved)
            }.onFailure { failure -> failed(rows, row, failure) }
        }
        rows.assertAllHeld()
    }

    @Test
    fun `on IntelliJ IDEA the chooser sets the project SDK for a module PyCharm set up`() {
        val rows = Rows()
        val row = "PYTHON_MODULE"
        launchIdea("BannerChooserTest-IU-python") { homes ->
            val (erlang, elixir) = addSdksOnWelcomeScreen(homes.erlang.a, homes.elixir.a)
            // As PyCharm leaves it: the facet names the SDK, which IntelliJ IDEA doesn't read, and there is no misc.xml.
            val iml = moduleIml("PYTHON_MODULE", """<configuration sdkName="$elixir" />""")
            val project = stageProject(NAME, iml, mapOf(SOURCE_PATH to SOURCE))
            runCatching {
                openInThisWindow(project)
                chooseFromBanner(rows, row, elixir, erlang)
                val set = appears("the module's SDK", 30.seconds) { moduleSdk() == elixir }
                rows.check(row, "the module's SDK '${moduleSdk()}' is '$elixir'", set)
                rows.check(row, "the module inherits it as the project SDK", moduleInheritsSdk())
                val saved = savedTo(project.resolve(".idea/misc.xml"), """project-jdk-name="$elixir"""")
                rows.check(row, "misc.xml names '$elixir' as the project SDK", saved)
                val entries = project.resolve("$NAME.iml").readText().lines().filter { "<orderEntry" in it }.map(String::trim)
                rows.check(row, "the .iml has no SDK entry of its own: $entries", entries.none { "type=\"jdk\"" in it })
            }.onFailure { failure -> failed(rows, row, failure) }
        }
        rows.assertAllHeld()
    }

    /**
     * The source file opened, and the banner's "Choose Elixir SDK..." clicked: the chooser lists [elixir] paired with
     * [erlang], with OK disabled until it is picked; then picked, and OK.
     */
    private fun Driver.chooseFromBanner(rows: Rows, row: String, elixir: String, erlang: String) {
        openFile(SOURCE_PATH, waitForCodeAnalysis = false)
        clickBannerLabel(CHOOSE)
        val asked = appears("the chooser", 30.seconds) { dialogShown(TITLE) }
        rows.check(row, "the banner asks the chooser", asked)
        ui.dialog(xQuery { byTitle(TITLE) }) {
            waitUntilReady()
            val list = jBlist()
            val entries = list.items
            val entry = entries.firstOrNull { it.startsWith("$elixir ") }
            rows.check(row, "the chooser lists '$elixir' paired with '$erlang': $entries", entry?.contains("Erlang SDK '$erlang'") == true)
            rows.check(row, "nothing is picked: ${list.selectedItems}", list.selectedItems.isEmpty())
            rows.check(row, "OK is disabled before a pick", !x { byAccessibleName("OK") }.isEnabled())
            list.clickItemAtIndex(entries.indexOf(checkNotNull(entry) { "no entry '$elixir' in the chooser: $entries" }))
            clickButtonText("OK")
        }
    }

    /** Whether [file], once the IDE has saved everything, holds [text]. */
    private fun Driver.savedTo(file: Path, text: String): Boolean {
        invokeAction("SaveAll", now = false)
        return appears("$file to hold $text", 30.seconds) { file.exists() && text in file.readText() }
    }

    private fun Driver.failed(rows: Rows, row: String, failure: Throwable) =
        failedErlangStepRow(rows, row, failure) { if (dialogShown(TITLE)) ui.x(xQuery { byTitle(TITLE) }).clickButtonText("Cancel") }

    private companion object {
        const val NAME = "banner_chooser"
        const val CHOOSE = "Choose Elixir SDK..."
        const val TITLE = "Choose Elixir SDK"
        const val SOURCE_PATH = "lib/$NAME.ex"
        const val SOURCE = "defmodule BannerChooser do\nend"
    }
}
