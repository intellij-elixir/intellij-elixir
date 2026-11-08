package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.dialogs.newProjectDialog
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.balloon
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import org.elixir_lang.ui.framework.core.ADD_NEW_SDK
import org.elixir_lang.ui.framework.core.CHOOSER_TITLE
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.ensureWelcomeScreenInForeground
import org.elixir_lang.ui.framework.core.projectStructure
import org.elixir_lang.ui.framework.core.waitUntilReady
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.closeInternalErrors
import org.elixir_lang.ui.sdk_settings.failedErlangStepRow
import org.elixir_lang.ui.sdk_settings.launchIdea
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * On IntelliJ IDEA Project Structure opens on the page asked for, with no category clicked: Manage SDKs... on SDKs, from
 * an open project's chooser and from the welcome screen's New Project wizard, and the status widget's notice about a
 * project with no SDK on Modules.
 */
class ManageSdksPageTest {
    @Test
    fun `the banner chooser's Manage SDKs opens on SDKs`() {
        val rows = Rows()
        val row = "banner"
        launchIdea("ManageSdksPageTest-banner") { _ ->
            openStagedProject()
            runCatching {
                askTheChooser(rows, row, SOURCE_PATH)
                ui.dialog(xQuery { byTitle(CHOOSER_TITLE) }) {
                    waitUntilReady()
                    clickButtonText(MANAGE_SDKS)
                }
                opensOn(rows, row, "SDKs") { x(ADD_NEW_SDK).present() }
                cancelDialogs(CHOOSER_TITLE)
            }.onFailure { failure -> failed(rows, row, failure) }
        }
        rows.assertAllHeld()
    }

    @Test
    fun `the widget's notice about no SDK opens on Modules`() {
        val rows = Rows()
        val row = "widget"
        launchIdea("ManageSdksPageTest-widget", configure = ::enableTheWidget) { _ ->
            openStagedProject()
            runCatching {
                ideFrame {
                    val notice = balloon(NOT_CONFIGURED)
                    rows.check(row, "the widget's notice shows", appears("the notice", 2.minutes) { notice.present() })
                    notice.x { byVisibleText("Open Project Structure") }.click()
                }
                opensOn(rows, row, "Modules") { hasText(DEPENDENCIES) }
            }.onFailure { failure -> failed(rows, row, failure) }
        }
        rows.assertAllHeld()
    }

    @Test
    fun `the welcome screen's New Project wizard's Manage SDKs opens on SDKs`() {
        val rows = Rows()
        val row = "wizard"
        launchIdea("ManageSdksPageTest-wizard") { _ ->
            ensureWelcomeScreenInForeground()
            closeInternalErrors("setup")
            welcomeScreen { createNewProjectButton.click() }
            newProjectDialog {
                chooseProjectType("Elixir")
                runCatching {
                    clickButtonText(MANAGE_SDKS)
                    driver.opensOn(rows, row, "SDKs") { x(ADD_NEW_SDK).present() }
                }.onFailure { failure -> driver.failed(rows, row, failure) }
                clickButtonText("Cancel")
            }
        }
        rows.assertAllHeld()
    }

    /** The status bar widget is off until its experimental setting is on. */
    private fun enableTheWidget(configDir: Path) {
        configDir.resolve("options/elixir.experimental.xml").createParentDirectories().writeText(
            """
            <application>
              <component name="org.elixir_lang.settings.ElixirExperimentalSettings">
                <option name="enableStatusBarWidget" value="true" />
              </component>
            </application>
            """.trimIndent(),
        )
    }

    private fun Driver.openStagedProject() = open(stageProject(NAME, moduleIml("ELIXIR_MODULE"), mapOf(SOURCE_PATH to SOURCE)))

    /** Project Structure is open on the page named [page], which alone [shows] what it looks for; then cancelled. */
    private fun Driver.opensOn(rows: Rows, row: String, page: String, shows: UiComponent.() -> Boolean) {
        ui.projectStructure {
            waitUntilReady()
            val shown = appears("the $page page", 15.seconds) { shows() }
            rows.check(row, "Project Structure opens on $page, here showing '${texts().take(300)}'", shown)
            clickButtonText("Cancel")
        }
    }

    private fun Driver.failed(rows: Rows, row: String, failure: Throwable) =
        failedErlangStepRow(rows, row, failure) { cancelDialogs("Project Structure", "Default Project Structure", CHOOSER_TITLE, "New Project") }

    private companion object {
        const val NAME = "manage_sdks_page"
        const val SOURCE_PATH = "lib/$NAME.ex"
        const val SOURCE = "defmodule ManageSdksPage do\nend"
        const val NOT_CONFIGURED = "Elixir SDK Not Configured"
        const val MANAGE_SDKS = "Manage SDKs..."
        const val DEPENDENCIES = "Dependencies"
    }
}
