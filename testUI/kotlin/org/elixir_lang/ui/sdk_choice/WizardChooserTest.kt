package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.getModules
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.dialogs.NewProjectDialogUI
import com.intellij.driver.sdk.ui.components.common.dialogs.newProjectDialog
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import org.elixir_lang.ui.framework.core.choosePath
import org.elixir_lang.ui.framework.core.pickInErlangStep
import org.elixir_lang.ui.framework.core.sdksAt
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.SdkPage
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.failedErlangStepRow
import org.elixir_lang.ui.sdk_settings.launchIdea
import org.elixir_lang.ui.sdk_settings.openInThisWindow
import org.elixir_lang.ui.sdk_settings.stored
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * IntelliJ IDEA's New Project and New Module wizards, Elixir generator: the "Elixir SDK:" row is the chooser, listing each
 * Elixir SDK with its Erlang SDK and selecting none, and Create is refused until there is a pick. Add... selects the SDK
 * it makes, which New Project creates with. The pick becomes the SDK of the module the wizard makes: New Project's, or New
 * Module's own module, with the project's module left as it was.
 */
class WizardChooserTest {
    @Test
    fun `New Project asks with the chooser`() {
        val rows = Rows()
        launchIdea("WizardChooserTest-project") { homes ->
            val (erlang, elixir) = addSdksOnWelcomeScreen(homes.erlang.a, homes.elixir.a)
            var made: String? = null
            welcomeScreen { createNewProjectButton.click() }
            newProjectDialog {
                chooseProjectType("Elixir")
                nameTextField.text = PROJECT
                runCatching { refusedUntilPicked(rows, NEW_PROJECT, elixir, erlang) { project(PROJECT) != null } }
                    .onFailure { failure -> failedKeepingWizard(rows, NEW_PROJECT, failure) }
                made = runCatching { addSelectsWhatItMakes(rows, homes.elixir.b, erlang) }
                    .onFailure { failure -> failedKeepingWizard(rows, ADD, failure) }.getOrNull()
                runCatching { createButton.click() }.onFailure { failure -> failedKeepingWizard(rows, NEW_PROJECT, failure) }
            }
            runCatching {
                val created = appears("the created project", 3.minutes) {
                    project(PROJECT)?.let { Path.of(it.getBasePath(), "mix.exs").exists() } == true
                }
                rows.check(NEW_PROJECT, "the created project opens with the mix.exs `mix new` wrote", created)
                val set = appears("the module's SDK", 30.seconds) { made != null && moduleSdk() == made }
                rows.check(NEW_PROJECT, "the new module's SDK '${moduleSdk()}' is the SDK Add... made '$made'", set)
                val paired = made?.let { stored(it).pairedErlang }
                rows.check(NEW_PROJECT, "'$made' is paired with the pick '$erlang': '$paired'", paired == erlang)
            }.onFailure { failure -> failedClosingWizard(rows, NEW_PROJECT, failure) }
        }
        rows.assertAllHeld()
    }

    @Test
    fun `New Module makes its own module with the pick`() {
        val rows = Rows()
        launchIdea("WizardChooserTest-module") { homes ->
            val (erlang, elixir) = addSdksOnWelcomeScreen(homes.erlang.a, homes.elixir.a)
            val project = stageProject(PLAIN, moduleIml("ELIXIR_MODULE"), mapOf("lib/$PLAIN.ex" to "defmodule Plain do\nend"))
            val plainIml = project.resolve("$PLAIN.iml")
            val before = rootEntries(plainIml)
            runCatching {
                openInThisWindow(project)
                invokeAction("NewModule", now = false)
                newProjectDialog {
                    chooseProjectType("Elixir")
                    nameTextField.text = EXTRA
                    refusedUntilPicked(rows, NEW_MODULE, elixir, erlang) { EXTRA in moduleNames() }
                    pick(elixir)
                    createButton.click()
                }
                val made = appears("module '$EXTRA'", 3.minutes) { EXTRA in moduleNames() }
                rows.check(NEW_MODULE, "the project has module '$EXTRA' beside '$PLAIN': ${moduleNames()}", made && PLAIN in moduleNames())
                val set = appears("the module's SDK", 30.seconds) { moduleSdk(EXTRA) == elixir }
                rows.check(NEW_MODULE, "'$EXTRA''s SDK '${moduleSdk(EXTRA)}' is the pick '$elixir'", set)
                // The save writes both module files, so once the new one is there the project's is as the route left it.
                invokeAction("SaveAll", now = false)
                val iml = project.resolve("$EXTRA/$EXTRA.iml")
                rows.check(NEW_MODULE, "'$EXTRA''s module file is saved at $iml", appears("$iml", 30.seconds) { iml.exists() })
                val after = rootEntries(plainIml)
                rows.check(NEW_MODULE, "'$PLAIN''s .iml keeps its content and entries $before: $after", after == before)
            }.onFailure { failure -> failedClosingWizard(rows, NEW_MODULE, failure) }
        }
        rows.assertAllHeld()
    }

    /**
     * The chooser lists [elixir] paired with [erlang] and selects nothing, and Create, pressed, leaves the wizard open with
     * nothing [made].
     */
    private fun NewProjectDialogUI.refusedUntilPicked(rows: Rows, row: String, elixir: String, erlang: String, made: () -> Boolean) {
        rows.listsPairedWithNothingPicked(row, chooserList(), elixir, erlang)
        createButton.click()
        val refused = !appears("Create to go ahead", 10.seconds) { made() } && present()
        rows.check(row, "Create with no pick leaves the wizard open", refused)
    }

    /** Add... with the Elixir home [home], paired with [erlang]: the chooser selects the SDK it made, which is answered. */
    private fun NewProjectDialogUI.addSelectsWhatItMakes(rows: Rows, home: String, erlang: String): String {
        x("//div[@class='SdkChoicePanel']//div[@class='JButton' and @text='Add...']").click()
        driver.choosePath(SdkPage.ELIXIR.chooserTitle, home)
        driver.pickInErlangStep(erlang)
        val added = appears("the SDK Add... made", 30.seconds) { driver.sdksAt(home).isNotEmpty() }
        rows.check(ADD, "Add... put an SDK at $home in the table", added)
        val made = driver.sdksAt(home).single()
        val selected = appears("the chooser to select '$made'", 10.seconds) { chooserList().selectedItems.singleOrNull()?.startsWith("$made ") == true }
        rows.check(ADD, "the chooser selects '$made': ${chooserList().selectedItems}", selected)
        return made
    }

    private fun NewProjectDialogUI.pick(elixir: String) = chooserList().pickEntry(elixir)

    /** The content root and dependency lines of the module file [iml]. */
    private fun rootEntries(iml: Path): List<String> = imlLines(iml, "<content", "<orderEntry")

    private fun Driver.project(name: String) = getOpenProjects().firstOrNull { it.getName() == name }

    private fun Driver.moduleNames(): List<String> = getModules().map { it.getName() }

    private fun NewProjectDialogUI.failedKeepingWizard(rows: Rows, row: String, failure: Throwable) =
        driver.failedErlangStepRow(rows, row, failure) { cancelDialogs(SdkPage.ELIXIR.chooserTitle) }

    private fun Driver.failedClosingWizard(rows: Rows, row: String, failure: Throwable) =
        failedErlangStepRow(rows, row, failure) { cancelDialogs("New Project", "New Module") }

    private companion object {
        const val PROJECT = "wizard_chooser"
        const val PLAIN = "plain"
        const val EXTRA = "extra"
        const val NEW_PROJECT = "New Project"
        const val NEW_MODULE = "New Module"
        const val ADD = "Add..."
    }
}
