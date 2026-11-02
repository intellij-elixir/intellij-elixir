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
import com.intellij.driver.sdk.waitFor
import org.elixir_lang.jps.shared.ElixirSdkTypeId.ELIXIR_SDK_TYPE_ID
import kotlin.time.Duration
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
fun Driver.openProjectAt(path: String, keepBuild: Boolean = false, ready: Duration = 30.seconds) = step("Open project at $path") {
    // Clean any existing IntelliJ configuration to force OpenProcessor flow
    // Without this, IntelliJ opens the existing project config and bypasses
    // OpenProcessor.openProjectAsync() and Builder.commit(), which means
    // directory marks (source folders, exclude folders) are never set up
    cleanIdeaFiles(path, keepBuild)

    welcomeScreen {
        openProjectButton.click()
    }

    ui.dialog(xQuery { byTitle("Open File or Project") }) {
        comboBox().enterTextWithFocus(path)
        clickButtonText("OK")
    }
    ideFrame {
        waitUntilReady(ready)
    }
}

/**
 * Adds a new SDK from disk using the Project Structure settings dialog.
 *
 * This method opens Project Structure, navigates to the SDKs section, and adds
 * a new SDK by selecting its type and specifying the installation path.
 *
 * @receiver WindowUiComponent The window component (typically welcomeScreen)
 * @param sdkType The type of SDK to add (e.g., "Add Erlang SDK", "Add Elixir SDK")
 * @param sdkPath The absolute file system path to the SDK installation directory
 * @param dialogTitle The expected title of the SDK home directory selection dialog
 * @throws Exception if the SDK cannot be added or UI components are not found
 */
fun WindowUiComponent.addSdkFromDisk(sdkType: String, sdkPath: String, dialogTitle: String) =
    step("Add SDK: $sdkType from $sdkPath") {
        // Not by its shortcut: under Xvfb the welcome screen doesn't answer one. Not `now`, as the dialog is modal.
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
            clickButtonText("Add New SDK")
        }

        driver.selectItemFromList(sdkType)

        dialog(xQuery { byTitle(dialogTitle) }) {
            comboBox().enterTextWithFocus(sdkPath)
            clickButtonText("OK")
        }

        projectStructure {
            clickButtonText("OK")
        }
        waitUntilReady()
    }

@Remote("com.intellij.openapi.projectRoots.ProjectJdkTable")
private interface SdkTable {
    fun getInstance(): SdkTable
    fun getAllJdks(): Array<TableSdk>
}

@Remote("com.intellij.openapi.projectRoots.Sdk")
private interface TableSdk {
    fun getSdkType(): TableSdkType
}

@Remote("com.intellij.openapi.projectRoots.SdkTypeId")
private interface TableSdkType {
    fun getName(): String
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
