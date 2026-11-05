package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.dialogs.NewProjectDialogUI
import com.intellij.driver.sdk.ui.components.common.dialogs.newProjectDialog
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.JComboBoxUiComponent
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.jBlist
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import com.intellij.driver.sdk.waitFor
import org.elixir_lang.ui.framework.core.ERLANG_STEP_TITLE
import org.elixir_lang.ui.framework.core.IdeTestContext
import org.elixir_lang.ui.framework.core.NoScreen
import org.elixir_lang.ui.framework.core.addErlangSdkFromDisk
import org.elixir_lang.ui.framework.core.cancelErlangStep
import org.elixir_lang.ui.framework.core.ensureWelcomeScreenInForeground
import org.elixir_lang.ui.framework.core.pickInErlangStep
import org.elixir_lang.ui.framework.core.selectItemFromList
import org.elixir_lang.ui.framework.core.sdksAt
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.closeInternalErrors
import org.elixir_lang.ui.sdk_settings.dialogShown
import org.elixir_lang.ui.sdk_settings.failedErlangStepRow
import org.elixir_lang.ui.sdk_settings.launchIdea
import org.elixir_lang.ui.sdk_settings.stored
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * In IntelliJ IDEA's New Project wizard, an Elixir install the Elixir SDK combo detects, picked: the Erlang step asks for
 * its Erlang. Cancel leaves the combo with no SDK; a pick pairs the new SDK, which the created project's table holds.
 */
class NewSdkErlangAskTest {
    @Test
    fun `a detected pick in the wizard asks the Erlang step`() {
        val rows = Rows()
        launchIdea("NewSdkErlangAskTest") { homes ->
            val install = stageElixirInstall()
            ensureWelcomeScreenInForeground()
            closeInternalErrors("setup")
            lateinit var erlang: String
            welcomeScreen { erlang = addErlangSdkFromDisk(homes.erlang.a) }
            invokeAction("NewProject", now = false)
            newProjectDialog {
                chooseProjectType("Elixir")
                cancelLeavesNoSdk(rows, install)
                aPickPairs(rows, install, erlang)
            }
        }
        rows.assertAllHeld()
    }

    private fun NewProjectDialogUI.cancelLeavesNoSdk(rows: Rows, install: String) {
        val row = "Cancel"
        runCatching {
            pickDetected(install)
            val asked = appears("the Erlang step", 30.seconds) { driver.dialogShown(ERLANG_STEP_TITLE) }
            rows.check(row, "the Erlang step asks", asked)
            driver.cancelErlangStep()
            val selected = sdkCombo().getSelectedItem()
            rows.check(row, "the combo shows no SDK: '$selected'", selected.contains("No SDK"))
        }.onFailure { failure -> failed(rows, row, failure) }
    }

    private fun NewProjectDialogUI.aPickPairs(rows: Rows, install: String, erlang: String) {
        val row = "pick"
        runCatching {
            pickDetected(install)
            driver.pickInErlangStep(erlang)
            val name = "wizard_ask"
            nameTextField.text = name
            createButton.click()
            // The wizard's validation adds the SDK to the table before `mix new` runs, so the project is waited for.
            val created = appears("the created project", 3.minutes) {
                driver.getOpenProjects().any { it.getName() == name && Path.of(it.getBasePath(), "mix.exs").exists() }
            }
            rows.check(row, "the created project opens with the mix.exs `mix new` wrote", created)
            val added = driver.sdksAt(install)
            rows.check(row, "the created project's Elixir SDK is in the table", added.isNotEmpty())
            val paired = driver.stored(added.single()).pairedErlang
            rows.check(row, "the new SDK is paired with the pick '$erlang': '$paired'", paired == erlang)
        }.onFailure { failure -> failed(rows, row, failure) }
    }

    /**
     * The combo's popup opened, and the entry it detected at [install] clicked once detection lists it. The entry shows
     * the home from `~`, shortened, so it is found by the home's last folders.
     */
    private fun NewProjectDialogUI.pickDetected(install: String) {
        val shown = "installs/elixir/${Path.of(install).fileName}"
        sdkCombo().click()
        val list = driver.ui.jBlist(xQuery { byClass("MyList") })
        waitFor("the detected install at $install", 60.seconds, errorMessage = { "the combo lists ${list.items}" }) {
            list.items.any { it.contains(shown) }
        }
        driver.selectItemFromList(shown, list)
    }

    private fun NewProjectDialogUI.sdkCombo(): JComboBoxUiComponent = comboBox { byClass("JdkComboBox") }

    private fun NewProjectDialogUI.failed(rows: Rows, row: String, failure: Throwable) =
        driver.failedErlangStepRow(rows, row, failure) { if (dialogShown("New Project")) cancelButton.click() }

    /**
     * A copy of the Elixir install where mise keeps installs under the private home, so the wizard's combo detects it;
     * its home. A copy, never a link: the guard empties the private home following links.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun stageElixirInstall(): String {
        val elixir = Path.of(IdeTestContext.elixirSdkPath).normalize()
        val app = elixir.resolve("lib/elixir/ebin/elixir.app").readText()
        val version = Regex("""\{vsn,"([^"]+)"}""").find(app)!!.groupValues[1]
        val installs = NoScreen.privateHome.resolve(".local/share/mise/installs/elixir").createDirectories()
        return elixir.copyToRecursively(installs.resolve(version), followLinks = true, overwrite = false).toString()
    }
}
