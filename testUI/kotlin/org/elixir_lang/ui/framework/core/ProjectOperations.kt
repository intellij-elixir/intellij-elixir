package org.elixir_lang.ui.framework.core

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.Module
import com.intellij.driver.sdk.getModules
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.step
import com.intellij.driver.sdk.ui.*
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.WindowUiComponent
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.jBlist
import com.intellij.driver.sdk.ui.components.idea.ProjectStructureUI
import com.intellij.driver.sdk.waitFor
import org.elixir_lang.jps.shared.ElixirSdkTypeId.ELIXIR_SDK_TYPE_ID
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Extensions for project and SDK operations.
 */

/**
 * Cleans IntelliJ IDEA project configuration files and Mix build artifacts to force a fresh import.
 *
 * This removes:
 * - .idea directory (contains project settings, workspace, etc.)
 * - quoter.iml (module file for the quoter project the tests open)
 * - deps directory (Mix dependencies)
 * - _build directory (Mix build artifacts)
 *
 * This ensures OpenProcessor.openProjectAsync() and Builder.commit() are called,
 * which properly sets up directory marks (source folders, exclude folders).
 *
 * @param projectPath The absolute file system path to the project directory
 * @param keepBuild Leaves `deps`, `_build` and `out`, for a project whose compiled beams are read
 */
private fun cleanIdeaFiles(projectPath: String, keepBuild: Boolean) {
    step("Clean existing IntelliJ configuration and Mix build files") {
        val projectDir = java.io.File(projectPath)
        // A module file left anywhere under the project stops the open at the "Module Files Found" dialog.
        val moduleFiles = if (keepBuild) {
            projectDir.walkTopDown().filter { it.extension == "iml" }.map { it.relativeTo(projectDir).path }.toList()
        } else {
            listOf("quoter.iml")
        }
        val pathsToClean = listOf(".idea") + moduleFiles + if (keepBuild) emptyList() else listOf("deps", "_build", "out")

        pathsToClean.forEach { path ->
            val file = java.io.File(projectDir, path)
            if (file.exists()) {
                println("Deleting $path at: ${file.absolutePath}")
                if (file.isDirectory) file.deleteRecursively() else file.delete()
                println("$path deleted successfully")
            } else {
                println("No existing $path found")
            }
        }
    }
}

/**
 * Opens an existing project at the specified path.
 *
 * This method uses the welcome screen to open a project by entering the path
 * in the file chooser dialog.
 *
 * IMPORTANT: Cleans any existing .idea folder and .iml files to force the OpenProcessor flow
 * and ensure proper project import with directory marks.
 *
 * @receiver Driver The UI test driver instance
 * @param path The absolute file system path to the project directory
 * @param keepBuild Leaves the project's compiled output in place
 * @param ready How long the project window may take to be ready
 * @throws Exception if the project cannot be opened or UI components are not found
 */
fun Driver.openProjectAt(path: String, keepBuild: Boolean = false, ready: Duration = INDEXING_TIMEOUT) = step("Open project at $path") {
    // Clean any existing IntelliJ configuration to force OpenProcessor flow
    // Without this, IntelliJ opens the existing project config and bypasses
    // OpenProcessor.openProjectAsync() and Builder.commit(), which means
    // directory marks (source folders, exclude folders) are never set up
    cleanIdeaFiles(path, keepBuild)

    welcomeScreen {
        openProjectButton.click()
    }

    choosePath("Open File or Project", path)
    ideFrame {
        waitUntilReady(ready)
    }
    // The open asks for the modules' Elixir SDK; the callers set it with assignElixirSdk.
    cancelChooser()
}

/** The chooser's title: the dialog that asks for modules' Elixir SDK. */
const val CHOOSER_TITLE = "Choose Elixir SDK"

/** Cancels the chooser, once it shows. */
private fun Driver.cancelChooser() = step("Cancel the chooser") {
    val chooser = ui.dialog(xQuery { byTitle(CHOOSER_TITLE) })
    waitFor("the chooser", 2.minutes) { chooser.present() }
    chooser.clickButtonText("Cancel")
}

/** Types [path] into the file chooser titled [title] and confirms it. */
fun Driver.choosePath(title: String, path: String) {
    ui.dialog(xQuery { byTitle(title) }) {
        comboBox().enterTextWithFocus(path)
        clickButtonText("OK")
    }
}

/** Adds an Erlang SDK at [sdkPath] with Project Structure > SDKs, and returns its name. */
fun WindowUiComponent.addErlangSdkFromDisk(sdkPath: String): String = addedAt(sdkPath) { newErlangSdk(sdkPath) }.first

/**
 * Adds an Elixir SDK at [sdkPath] with Project Structure > SDKs, picking the entry named [erlang] (an Erlang SDK's name,
 * or an install's home) in the Erlang step.
 */
fun WindowUiComponent.addElixirSdkFromDisk(sdkPath: String, erlang: String): AddedSdk =
    addedAt(sdkPath) { newElixirSdk(sdkPath, erlang) }.let { (name, shown) -> AddedSdk(name, shown) }

/** Project Structure > SDKs with [add] run, then its OK: the name of the SDK it added at [sdkPath], and what [add] returned. */
private fun <T> WindowUiComponent.addedAt(sdkPath: String, add: ProjectStructureUI.() -> T): Pair<String, T> {
    val before = driver.sdksAt(sdkPath)
    val added = mutableListOf<T>()
    inProjectStructureSdks {
        added += add()
        clickButtonText("OK")
    }
    waitUntilReady()
    return (driver.sdksAt(sdkPath) - before).single() to added.single()
}

/**
 * The names of the SDKs in the table whose home is [home]. By home, as IntelliJ IDEA registers a JDK it detects while a
 * test starts.
 */
fun Driver.sdksAt(home: String): List<String> = withReadAction {
    utility(SdkTable::class).getInstance().getAllJdks()
        .filter { sdk -> sdk.getHomePath()?.let(Path::of) == Path.of(home) }
        .map { it.getName() }
}

/**
 * Project Structure opened from this window on its SDKs page, with [body] inside; the body closes the dialog. Opened by
 * its action, not its shortcut: under Xvfb the welcome screen doesn't answer one. Not `now`, as the dialog is modal.
 */
fun WindowUiComponent.inProjectStructureSdks(body: ProjectStructureUI.() -> Unit) = step("Project Structure > SDKs") {
    driver.invokeAction("ShowProjectStructureSettings", now = false, component = component)
    projectStructure {
        waitUntilReady()
        if (NoScreen.onScreen) {
            // On a real display a click right after the dialog opens can leave its first page showing.
            waitFor("the SDKs page", 30.seconds, 1.seconds) {
                driver.selectItemByAccessibleName("Project structure categories", "SDKs")
                x("//div[@visible_text='Add New SDK' or @accessiblename='Add New SDK']").present()
            }
        } else {
            driver.selectItemByAccessibleName("Project structure categories", "SDKs")
        }
        body()
    }
}

/** Add New SDK > Erlang, at [sdkPath]. */
private fun ProjectStructureUI.newErlangSdk(sdkPath: String) =
    newSdk("Add Erlang SDK for Elixir SDK from disk", sdkPath, "Select Home Directory for Erlang SDK for Elixir SDK")

/** Add New SDK > Elixir, at [sdkPath], with [erlang] picked in the Erlang step. */
private fun ProjectStructureUI.newElixirSdk(sdkPath: String, erlang: String): ErlangStepShown {
    newSdk("Add Elixir SDK from disk", sdkPath, "Select Home Directory for Elixir SDK")
    return driver.pickInErlangStep(erlang)
}

private fun ProjectStructureUI.newSdk(sdkType: String, sdkPath: String, dialogTitle: String) = step("Add SDK: $sdkType from $sdkPath") {
    clickButtonText("Add New SDK")
    driver.selectItemFromList(sdkType)
    driver.choosePath(dialogTitle, sdkPath)
}

@Remote("com.intellij.openapi.roots.ModuleRootModificationUtil")
private interface ModuleSdk {
    fun setModuleSdk(module: Module, sdk: TableSdk)
}

/**
 * Gives every module of the open project the one Elixir SDK in the table, as its module SDK: where a user sets it in
 * IntelliJ IDEA, and what `ElixirSdkLookup.resolve(module)` reads there. Opening a project leaves its modules with no
 * Elixir SDK; this is setup, not a gesture under test.
 */
fun Driver.assignElixirSdk() = step("Assign the Elixir SDK") {
    val sdk = utility(SdkTable::class).getInstance().getAllJdks().single { it.getSdkType().getName() == ELIXIR_SDK_TYPE_ID }
    getModules().forEach { utility(ModuleSdk::class).setModuleSdk(it, sdk) }
}

/** The Erlang step's title for an Elixir SDK add; the deletion ask's adds the name of the Elixir SDK it asks for. */
const val ERLANG_STEP_TITLE = "Erlang for Elixir SDK"

/** What the Erlang step showed before the pick: its entries, the selected ones, and whether its confirm button was enabled. */
class ErlangStepShown(val entries: List<String>, val selected: List<String>, val confirmEnabled: Boolean) {
    fun lists(name: String): Boolean = entryIndex(entries, name) >= 0
}

/** An Elixir SDK an add made, and what its Erlang step showed before the pick. */
class AddedSdk(val name: String, val shown: ErlangStepShown)

/**
 * In the Erlang step titled [title], picks the entry named [erlang] (an Erlang SDK's name, or an install's home) and
 * clicks [confirm]: "Create" for an Elixir SDK add, "OK" for the deletion ask.
 */
fun Driver.pickInErlangStep(erlang: String, title: String = ERLANG_STEP_TITLE, confirm: String = "Create"): ErlangStepShown =
    step("Erlang step '$title': pick $erlang") {
        lateinit var shown: ErlangStepShown
        ui.dialog(xQuery { byTitle(title) }) {
            waitUntilReady()
            val list = jBlist()
            val entries = list.items
            shown = ErlangStepShown(entries, list.selectedItems, x { byAccessibleName(confirm) }.isEnabled())
            val index = entryIndex(entries, erlang)
            check(index >= 0) { "no entry '$erlang' in the Erlang step: $entries" }
            list.clickItemAtIndex(index)
            clickButtonText(confirm)
        }
        shown
    }

/** Cancels an Erlang step a failed row left open. */
fun Driver.cancelErlangStep() {
    val step = ui.x("//div[starts-with(@title, '$ERLANG_STEP_TITLE')]")
    if (step.present()) step.clickButtonText("Cancel")
}

/** The index of the Erlang step's entry named [name]: its row starts with the name. */
private fun entryIndex(entries: List<String>, name: String): Int = entries.indexOfFirst { it == name || it.startsWith("$name ") }
