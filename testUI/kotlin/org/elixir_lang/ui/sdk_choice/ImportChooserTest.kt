package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.getNotifications
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.dialogs.newProjectDialog
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.ui.components.elements.jBlist
import com.intellij.driver.sdk.ui.components.idea.ProjectStructureUI
import com.intellij.driver.sdk.ui.ui
import org.elixir_lang.ui.framework.core.addElixirSdkFromDisk
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.projectStructure
import org.elixir_lang.ui.framework.core.sdksAt
import org.elixir_lang.ui.framework.core.selectItemByAccessibleName
import org.elixir_lang.ui.framework.core.selectItemFromList
import org.elixir_lang.ui.framework.core.waitUntilReady
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.failedErlangStepRow
import org.elixir_lang.ui.sdk_settings.launchIdea
import org.elixir_lang.ui.sdk_settings.openInThisWindow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Path
import javax.swing.JTree
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * IntelliJ IDEA's Mix import, from File > New > Project from Existing Sources and Module from Existing Sources and from
 * Project Structure, and Project Structure's New Module: each asks with the chooser, listing each Elixir SDK with its
 * Erlang SDK, selecting none and offering no "Set Default", and every module it makes gets the pick. Inside Project
 * Structure the module is made into the dialog's model, so Cancel drops it, and the tool manager boxes are disabled.
 */
class ImportChooserTest {
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `Project from Existing Sources gives every app of an umbrella the pick`() {
        val rows = Rows()
        launchIdea("ImportChooserTest-project") { homes ->
            val (erlang, elixir) = addSdksOnWelcomeScreen(homes.erlang.a, homes.elixir.a)
            val hub = mixNew(homes, HUB, umbrella = true)
            runCatching {
                fromExistingSources("ImportProject", hub, IMPORT_PROJECT, EXTERNAL_MODEL, "Mix")
                asksWithTheChooser(rows, IMPORT_PROJECT, IMPORT_PROJECT, elixir, erlang)
                waitForProject(hub)
                for (app in listOf(HUB, "alpha", "beta")) {
                    val set = appears("$app's SDK", 30.seconds) { sdkOf(app) == elixir }
                    rows.check(IMPORT_PROJECT, "'$app''s SDK '${sdkOf(app)}' is the pick '$elixir'", set)
                }
            }.onFailure { failure -> failed(rows, IMPORT_PROJECT, failure) }
        }
        rows.assertAllHeld()
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `Module from Existing Sources runs the deps tasks on the pick`() {
        val rows = Rows()
        launchIdea("ImportChooserTest-module") { homes ->
            val (erlang, _) = addSdksOnWelcomeScreen(homes.erlang.a, homes.elixir.a)
            lateinit var elixir: String
            welcomeScreen { elixir = addElixirSdkFromDisk(homes.elixir.b, erlang).name }
            val plain = stagePlain()
            val other = mixNew(homes, OTHER)
            val mixExs = other.resolve("mix.exs")
            mixExs.writeText(RECORD_HOME + mixExs.readText())
            runCatching {
                openInThisWindow(plain)
                fromExistingSources("ImportModule", other, IMPORT_MODULE, EXTERNAL_MODEL, "Mix")
                asksWithTheChooser(rows, IMPORT_MODULE, IMPORT_MODULE, elixir, erlang)
                val set = appears("$OTHER's SDK", 2.minutes) { sdkOf(OTHER) == elixir }
                rows.check(IMPORT_MODULE, "'$OTHER''s SDK '${sdkOf(OTHER)}' is the pick '$elixir'", set)
                val ranWith = other.resolve(RAN_WITH).takeIf { it.exists() }?.readLines().orEmpty().map { Path.of(it).toRealPath() }
                val pickHome = Path.of(homes.elixir.b).toRealPath()
                rows.check(IMPORT_MODULE, "mix ran with the pick's home $pickHome only: $ranWith", ranWith.isNotEmpty() && ranWith.all { it == pickHome })
                val unpaired = notifications().filter { NOT_CONFIGURED in it }
                rows.check(IMPORT_MODULE, "no notification says an SDK has no Erlang SDK: $unpaired", unpaired.isEmpty())
            }.onFailure { failure -> failed(rows, IMPORT_MODULE, failure) }
        }
        rows.assertAllHeld()
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `Project Structure's Import Module takes mise's offer with the boxes disabled`() {
        val rows = Rows()
        launchIdea("ImportChooserTest-structure-import", { withEnv("PATH", stubbedPath()) }) { homes ->
            val install = stageElixirInstall()
            val plain = stagePlain()
            val hub = mixNew(homes, HUB, umbrella = true)
            pinWithMise(hub, install, homes.erlang)
            runCatching {
                openInThisWindow(plain)
                val before = toolManagersShowMiseOn()
                addInProjectStructure("Import Module")
                fromExistingSources(null, hub, IMPORT_MODULE, EXTERNAL_MODEL, "Mix")
                val asked = walkWizard(rows, STRUCTURE_IMPORT, IMPORT_MODULE) {
                    val inStep = takeMisesOffer(rows, STRUCTURE_IMPORT, install, homes.erlang)
                    rows.check(STRUCTURE_IMPORT, "its box shows once the offer is taken", appears("the box", 10.seconds) { inStep.present() })
                    rows.check(STRUCTURE_IMPORT, "its box stays disabled", !appears("the box enabled", 10.seconds) { inStep.isEnabled() })
                }
                rows.check(STRUCTURE_IMPORT, "the wizard asks with the chooser", asked)
                ui.projectStructure { clickButtonText("OK") }
                for (app in listOf(HUB, "alpha", "beta")) {
                    val set = appears("$app's SDK", 30.seconds) { sdkOf(app)?.let { it in sdksAt(install) } == true }
                    rows.check(STRUCTURE_IMPORT, "'$app''s SDK '${sdkOf(app)}' is at mise's install", set)
                }
                val after = toolManagersShowMiseOn()
                rows.check(STRUCTURE_IMPORT, "Tool Managers shows mise as it was, ${onOff(before)}: ${onOff(after)}", after == before)
            }.onFailure { failure -> failed(rows, STRUCTURE_IMPORT, failure) }
        }
        rows.assertAllHeld()
    }

    @Test
    fun `Project Structure's New Module is dropped on Cancel and kept with the pick on OK`() {
        val rows = Rows()
        launchIdea("ImportChooserTest-structure-new") { homes ->
            val (_, elixir) = addSdksOnWelcomeScreen(homes.erlang.a, homes.elixir.a)
            val plain = stagePlain()
            runCatching {
                openInThisWindow(plain)
                newModuleInProjectStructure(DROPPED, elixir)
                ui.projectStructure {
                    val listed = appears("'$DROPPED' in the module tree", 2.minutes) { DROPPED in moduleTree() }
                    rows.check(STRUCTURE_NEW, "Project Structure lists '$DROPPED' before Cancel: ${moduleTree()}", listed)
                    clickButtonText("Cancel")
                }
                val dropped = !appears("module '$DROPPED'", 10.seconds) { DROPPED in moduleNames() }
                rows.check(STRUCTURE_NEW, "Cancel leaves no module '$DROPPED': ${moduleNames()}", dropped)
                newModuleInProjectStructure(KEPT, elixir)
                ui.projectStructure { clickButtonText("OK") }
                val set = appears("$KEPT's SDK", 2.minutes) { sdkOf(KEPT) == elixir }
                rows.check(STRUCTURE_NEW, "OK leaves '$KEPT' with the pick '$elixir' as its SDK: '${sdkOf(KEPT)}'", set)
            }.onFailure { failure -> failed(rows, STRUCTURE_NEW, failure) }
        }
        rows.assertAllHeld()
    }

    /** Project Structure > Modules > + > New Module, Elixir, named [name], with [elixir] picked and Create; the dialog stays open. */
    private fun Driver.newModuleInProjectStructure(name: String, elixir: String) {
        addInProjectStructure("New Module")
        newProjectDialog {
            // Not `chooseProjectType`: once Elixir is the remembered type, the chooser's list shows beside the type list.
            jBlist("//div[@class='JBList' and not(ancestor::div[@class='SdkChoicePanel'])]").clickItem("Elixir")
            nameTextField.text = name
            chooserList().pickEntry(elixir)
            createButton.click()
        }
    }

    /** Project Structure opened on Modules, and its + > [item]. */
    private fun Driver.addInProjectStructure(item: String) {
        invokeAction("ShowProjectStructureSettings", now = false)
        ui.projectStructure {
            waitUntilReady()
            driver.selectItemByAccessibleName("Project structure categories", "Modules")
            xx("//div[@class='ActionButton' and (@accessiblename='Add' or @myicon='add.svg')]").list().first().click()
        }
        selectItemFromList(item)
    }

    /** The module names Project Structure's Modules page lists, uncommitted ones included. */
    private fun ProjectStructureUI.moduleTree(): List<String> =
        xx(JTreeUiComponent::class.java) { byType(JTree::class.java) }.list().flatMap { tree -> tree.collectExpandedPaths().map { it.path.last() } }

    private fun stagePlain(): Path = stageProject(PLAIN, moduleIml("ELIXIR_MODULE"), mapOf("lib/$PLAIN.ex" to "defmodule Plain do\nend"))

    /** The SDK of the module named [name], or null while there is no such module. */
    private fun Driver.sdkOf(name: String): String? = runCatching { moduleSdk(name) }.getOrNull()

    /** The content of every notification the IDE holds, the application's and each open project's. */
    private fun Driver.notifications(): List<String> =
        (listOf<Project?>(null) + getOpenProjects()).flatMap { getNotifications(it) }.map { it.getContent() }

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    private fun Driver.failed(rows: Rows, row: String, failure: Throwable) =
        failedErlangStepRow(rows, row, failure) { cancelDialogs(IMPORT_PROJECT, IMPORT_MODULE, "New Module", "Project Structure") }

    private companion object {
        const val HUB = "hub"
        const val OTHER = "other"
        const val PLAIN = "plain"
        const val DROPPED = "dropped"
        const val KEPT = "kept"
        const val IMPORT_PROJECT = "Import Project"
        const val IMPORT_MODULE = "Import Module"
        const val STRUCTURE_IMPORT = "Project Structure Import Module"
        const val STRUCTURE_NEW = "Project Structure New Module"
        const val EXTERNAL_MODEL = "from external model"
        const val NOT_CONFIGURED = "does not have an Erlang SDK configured"
        const val RAN_WITH = "mix-ran-with"

        /** Appends the home of the Elixir running Mix to [RAN_WITH] beside `mix.exs`, each time Mix loads the project. */
        const val RECORD_HOME =
            "File.write!(Path.join(__DIR__, \"$RAN_WITH\"), Path.expand(\"../..\", to_string(:code.lib_dir(:elixir))) <> \"\\n\", [:append])\n\n"
    }
}
