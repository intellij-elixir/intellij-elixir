package org.elixir_lang.ui.sdk_settings

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.invokeActionByShortcut
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.UiText.Companion.asString
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.WindowUiComponent
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.elements.tree
import com.intellij.driver.sdk.ui.components.idea.ProjectStructureUI
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitNotNull
import com.intellij.ide.starter.models.VMOptions
import org.elixir_lang.ui.code_intelligence.TextPane
import org.elixir_lang.ui.framework.core.ERLANG_STEP_TITLE
import org.elixir_lang.ui.framework.core.IdeTestContext
import org.elixir_lang.ui.framework.core.NoScreen
import org.elixir_lang.ui.framework.core.SDK_FIELD
import org.elixir_lang.ui.framework.core.addElixirSdkFromDisk
import org.elixir_lang.ui.framework.core.addErlangSdkFromDisk
import org.elixir_lang.ui.framework.core.choosePath
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.clickDetectedRow
import org.elixir_lang.ui.framework.core.clickProjectSdkFieldRow
import org.elixir_lang.ui.framework.core.clickSdkFieldFirstRow
import org.elixir_lang.ui.framework.core.clickSdkFieldRow
import org.elixir_lang.ui.framework.core.ensureWelcomeScreenInForeground
import org.elixir_lang.ui.framework.core.fieldText
import org.elixir_lang.ui.framework.core.inProjectStructureSdks
import org.elixir_lang.ui.framework.core.newElixirSdk
import org.elixir_lang.ui.framework.core.openElixirSdkFromDisk
import org.elixir_lang.ui.framework.core.openSdkField
import org.elixir_lang.ui.framework.core.pickInField
import org.elixir_lang.ui.framework.core.popupList
import org.elixir_lang.ui.framework.core.projectStructure
import org.elixir_lang.ui.framework.core.sdksAt
import org.elixir_lang.ui.framework.core.selectItemByAccessibleName
import org.elixir_lang.ui.framework.core.waitUntilReady
import org.elixir_lang.ui.sdk_choice.CHOOSE_LABEL
import org.elixir_lang.ui.sdk_choice.askTheChooser
import org.elixir_lang.ui.sdk_choice.bannerLabel
import org.elixir_lang.ui.sdk_choice.mixExs
import org.elixir_lang.ui.sdk_choice.moduleIml
import org.elixir_lang.ui.sdk_choice.open
import org.elixir_lang.ui.sdk_choice.pickInChooser
import org.elixir_lang.ui.sdk_choice.stageElixirInstall
import org.elixir_lang.ui.sdk_choice.stageProject
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Path
import javax.swing.JEditorPane
import javax.swing.JTree
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * On IntelliJ IDEA's Project Structure > SDKs, with a project open: a Detected Elixir install, or one added from disk, opens
 * its own page, which asks for an Erlang SDK in the platform's SDK field. Each way of giving it one ends with the SDK
 * working in an editor.
 */
class ProjectStructureDetectedTest {
    private val rows = Rows()

    @Test
    fun `a detected Elixir opens its page and takes a detected Erlang`() {
        val row = "a detected Erlang"
        journey(row, "detected", detectedErlang = true) { frame, installs, homes ->
            val (elixir, erlang) = frame.inProjectStructureSdks {
                val opened = addDetected(::isElixirRow)
                checkAsks(row, opened)
                val table = driver.sdksAt(installs.elixir)
                clickApply()
                checkRefused(row, opened)
                rows.check(row, "the SDK table is unchanged after the refusal", driver.sdksAt(installs.elixir) == table)
                val (erlang, elixir) = pickToAdd(row, homes.erlang.aVersion) { clickSdkFieldRow(::isErlangRow) }
                checkAnswered(row, elixir, erlang)
                clickButtonText("OK")
                elixir to erlang
            }
            checkSaved(row, installs.elixir, elixir, installs.erlang, erlang)
            checkWorksInAnEditor(row, installs.elixir, elixir, installs.erlang, erlang)
        }
    }

    @Test
    fun `a matching registered Erlang is preselected and names the SDK`() {
        val row = "a matching registered Erlang"
        journey(row, "preselected") { frame, installs, homes ->
            val (elixir, registered) = frame.takesThePreselection(row, homes) { addDetected(::isElixirRow) }
            checkSaved(row, installs.elixir, elixir, homes.erlang.b, registered)
            checkWorksInAnEditor(row, installs.elixir, elixir, homes.erlang.b, registered)
        }
    }

    @Test
    fun `a registered Erlang of another OTP is not preselected`() {
        val row = "a registered Erlang of another OTP"
        // Staged before the IDE starts: a folder made after it is unknown to its file system, and the chooser refuses it.
        lateinit var other: String
        journey(row, "another-otp", configure = { other = Homes.stageErlangOfAnotherMajor() }) { frame, _, _ ->
            frame.addErlangSdkFromDisk(other)
            frame.inProjectStructureSdks {
                val elixir = addDetected(::isElixirRow)
                appears("the text", 10.seconds) { messageShown() }
                checkAsks(row, elixir)
                rows.check(row, "the Name keeps the opening name: ${nameField().text}", nameField().text == elixir)
                clickButtonText("Cancel")
            }
        }
    }

    @Test
    fun `a changed preselection is saved and the Name follows it`() {
        val row = "a changed preselection"
        journey(row, "changed-preselection") { frame, installs, homes ->
            val preselected = frame.addErlangSdkFromDisk(homes.erlang.b)
            val registered = frame.addErlangSdkFromDisk(homes.erlang.a)
            val elixir = frame.inProjectStructureSdks {
                addDetected(::isElixirRow)
                checkAnswered(row, namedAfter(row, homes.erlang.bVersion), preselected)
                clickSdkFieldRow { registered in it }
                val elixir = namedAfter(row, homes.erlang.aVersion)
                checkAnswered(row, elixir, registered)
                clickButtonText("OK")
                elixir
            }
            checkSaved(row, installs.elixir, elixir, homes.erlang.a, registered)
            checkWorksInAnEditor(row, installs.elixir, elixir, homes.erlang.a, registered)
        }
    }

    @Test
    fun `an Erlang added from disk can be chosen`() {
        val row = "an Erlang added from disk"
        journey(row, "from-disk") { frame, installs, homes ->
            val (elixir, erlang) = frame.inProjectStructureSdks {
                addDetected(::isElixirRow)
                val (erlang, elixir) = addErlangInField(row, homes.erlang.b, homes.erlang.bVersion)
                rows.check(row, "the message is gone", !messageShown())
                clickButtonText("OK")
                elixir to erlang
            }
            checkSaved(row, installs.elixir, elixir, homes.erlang.b, erlang)
            checkWorksInAnEditor(row, installs.elixir, elixir, homes.erlang.b, erlang)
        }
    }

    @Test
    fun `a refusal with another page selected leaves the list where it is`() {
        val row = "a refusal with another page selected"
        journey(row, "refusal-elsewhere", detectedErlang = true) { frame, _, _ ->
            frame.inProjectStructureSdks {
                // The Elixir first, so that its page's first showing has no Erlang to preselect.
                val elixir = addDetected(::isElixirRow)
                val erlang = addDetected(::isErlangRow)
                openPage(erlang)
                clickApply()
                checkRefused(row, elixir)
                rows.check(row, "the list stays on '$erlang': ${selectedRow()}", selectedRow() == erlang)
                clickButtonText("Cancel")
            }
        }
    }

    @Test
    fun `Cancel keeps neither Model`() {
        val row = "Cancel"
        journey(row, "cancel", detectedErlang = true) { frame, installs, homes ->
            frame.inProjectStructureSdks {
                addDetected(::isElixirRow)
                pickToAdd(row, homes.erlang.aVersion) { clickSdkFieldRow(::isErlangRow) }
                clickButtonText("Cancel")
            }
            rows.check(row, "no Elixir SDK at the install: ${sdksAt(installs.elixir)}", sdksAt(installs.elixir).isEmpty())
            rows.check(row, "no Erlang SDK at the install: ${sdksAt(installs.erlang)}", sdksAt(installs.erlang).isEmpty())
        }
    }

    @Test
    fun `a detected Elixir picked in the Project SDK field is refused until its page has an Erlang`() {
        val row = "the Project SDK field"
        journey(row, "project-field", detectedErlang = true) { frame, installs, homes ->
            refusedUntilItsPageHasAnErlang(row, frame, installs, homes, installs.elixir) { clickProjectSdkFieldRow(::isElixirRow) }
        }
    }

    @Test
    fun `an Elixir SDK saved with a broken Erlang is red and refused only once edited`() {
        val row = "an Elixir SDK saved with a broken Erlang"
        journey(row, "saved-broken", configure = { seedElixirs(it, NO_DATA to "", NOT_FOUND to NOT_FOUND_DATA) }) { frame, _, homes ->
            val registered = frame.addErlangSdkFromDisk(homes.erlang.b)
            val erlang = "$registered renamed"
            val renamed = "$NO_DATA renamed"
            frame.inProjectStructureSdks {
                listOf(NO_DATA, NOT_FOUND).forEach { seed ->
                    openElixirPage(seed)
                    checkRed(row, seed)
                }
                openPage(registered)
                nameField().text = erlang
                clickApply()
                rows.check(row, "another SDK's rename is not refused", driver.refusalBox() == null)
                openElixirPage(NO_DATA)
                nameField().text = renamed
                clickApply()
                checkRefused(row, renamed)
                pickInField(erlang)
                clickApply()
                rows.check(row, "Apply after a pick is not refused", driver.refusalBox() == null)
                rows.check(row, "the saved SDK keeps its typed name: ${nameField().text}", nameField().text == renamed)
                clickButtonText("OK")
            }
            val paired = stored(renamed).pairedErlang
            rows.check(row, "'$renamed' is saved paired with '$erlang': '$paired'", paired == erlang)
            val unedited = stored(NOT_FOUND).pairedErlang
            rows.check(row, "'$NOT_FOUND' is saved as it was: '$unedited'", unedited == GONE)
        }
    }

    @Test
    fun `an Erlang added and then replaced is saved by OK`() {
        val row = "an Erlang added and then replaced"
        journey(row, "replaced", detectedErlang = true) { frame, installs, homes ->
            val registered = frame.addErlangSdkFromDisk(homes.erlang.b)
            val (elixir, replaced) = frame.inProjectStructureSdks {
                addDetected(::isElixirRow)
                namedAfter(row, homes.erlang.bVersion)
                val (replaced, _) = pickToAdd(row, homes.erlang.aVersion) { clickSdkFieldRow(::isErlangRow) }
                pickInField(registered)
                val elixir = namedAfter(row, homes.erlang.bVersion)
                rows.check(row, "the replaced Erlang stays in the list: ${sdkRows()}", replaced in sdkRows())
                clickButtonText("OK")
                elixir to replaced
            }
            checkSaved(row, installs.elixir, elixir, homes.erlang.b, registered)
            val saved = sdksAt(installs.erlang)
            rows.check(row, "the replaced Erlang is in the table: $saved", saved == listOf(replaced))
            checkWorksInAnEditor(row, installs.elixir, elixir, homes.erlang.b, registered)
        }
    }

    @Test
    fun `a typed name keeps the Elixir page selected`() {
        val row = "a typed name"
        journey(row, "typed-name", detectedErlang = true) { frame, _, homes ->
            frame.addErlangSdkFromDisk(homes.erlang.b)
            frame.inProjectStructureSdks {
                addDetected(::isElixirRow)
                namedAfter(row, homes.erlang.bVersion)
                val name = nameField()
                name.click()
                name.keyboard {
                    hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_A)
                    key(KeyEvent.VK_BACK_SPACE)
                }
                waitFor("the cleared Name", 10.seconds) { name.text.isEmpty() }
                // Project Structure keeps the last name it accepted while the field is empty, so the page's own name stands.
                pickToAdd(row, homes.erlang.aVersion) { clickSdkFieldRow(::isErlangRow) }
                name.click()
                name.keyboard { typeText(" typed") }
                waitFor("the typed text", 10.seconds) { "typed" in name.text }
                val typed = name.text
                rowAddedBy(::isErlangName) {
                    clickSdkFieldRow { "Erlang SDK for Elixir SDK from disk" in it }
                    driver.choosePath(SdkPage.ERLANG.chooserTitle, homes.erlang.a)
                }
                val onTheElixirPage = appears("the Elixir page", 10.seconds) { selectedRow() == typed }
                Thread.sleep(SETTLE_MS)
                rows.check(row, "the Elixir page is selected: ${selectedRow()}", onTheElixirPage && selectedRow() == typed)
                rows.check(row, "the Name field keeps the typed text: ${name.text}", name.text == typed)
                clickButtonText("Cancel")
            }
        }
    }

    @Test
    fun `a pick stays when detection arrives after it`() {
        val row = "detection arriving after a pick"
        val slowPath = stageSlowKerl()
        journey(row, "detection-after", detectedErlang = true, vmOptions = { withEnv("PATH", slowPath) }) { frame, installs, homes ->
            val registered = frame.addErlangSdkFromDisk(homes.erlang.b)
            val elixir = frame.inProjectStructureSdks {
                addDetected(::isElixirRow)
                val atThePick = clickSdkFieldFirstRow()
                rows.check(row, "detection has not reported the Erlang at the pick: $atThePick", atThePick.none(::isErlangRow))
                waitFor("the field to show the pick", 10.seconds) { registered in fieldText() }
                val elixir = namedAfter(row, homes.erlang.bVersion)
                // Closing the popup ends the field's listening for good, so this open only swaps the model.
                openSdkField { true }
                closeFieldPopup()
                rows.check(row, "reopening the field keeps the pick: ${fieldText()}", registered in fieldText())
                rows.check(row, "reopening the field brings no message back", !messageShown())
                clickButtonText("OK")
                elixir
            }
            checkSaved(row, installs.elixir, elixir, homes.erlang.b, registered)
            frame.inProjectStructureSdks {
                openElixirPage(elixir)
                openSdkField(::isErlangRow)
                closeFieldPopup()
                rows.check(row, "detection arriving keeps the stored pairing: ${fieldText()}", registered in fieldText())
                rows.check(row, "detection arriving brings no message", !messageShown())
                clickButtonText("Cancel")
            }
            checkWorksInAnEditor(row, installs.elixir, elixir, homes.erlang.b, registered)
        }
    }

    @Test
    fun `from the welcome screen a detected Elixir takes the preselected Erlang`() {
        val row = "from the welcome screen, a preselected Erlang"
        val project = stagedProject()
        welcomeJourney(row, "welcome-preselected") { installs, homes ->
            val (elixir, registered) = welcomeScreen().takesThePreselection(row, homes) { addDetected(::isElixirRow) }
            checkSaved(row, installs.elixir, elixir, homes.erlang.b, registered)
            open(project)
            checkWorksInAnEditor(row, installs.elixir, elixir, homes.erlang.b, registered)
        }
    }

    @Test
    fun `an Elixir added from disk opens its page with no Erlang step`() {
        val row = "an Elixir added from disk"
        journey(row, "elixir-from-disk") { frame, _, homes ->
            val (elixir, registered) = frame.takesThePreselection(row, homes) { addFromDisk(row, homes.elixir.a) }
            checkSaved(row, homes.elixir.a, elixir, homes.erlang.b, registered)
            checkWorksInAnEditor(row, homes.elixir.a, elixir, homes.erlang.b, registered)
        }
    }

    @Test
    fun `an Elixir added from disk with no matching Erlang is red and refused`() {
        val row = "an Elixir added from disk with no matching Erlang"
        journey(row, "from-disk-unmatched") { frame, _, homes ->
            val (erlang, elixir) = frame.inProjectStructureSdks {
                val opened = addFromDisk(row, homes.elixir.a)
                checkAsks(row, opened)
                clickApply()
                checkRefused(row, opened)
                val added = addErlangInField(row, homes.erlang.b, homes.erlang.bVersion)
                clickButtonText("OK")
                added
            }
            checkSaved(row, homes.elixir.a, elixir, homes.erlang.b, erlang)
            checkWorksInAnEditor(row, homes.elixir.a, elixir, homes.erlang.b, erlang)
        }
    }

    @Test
    fun `a second Elixir added from disk on one install is named after its own Erlang`() {
        val row = "a second Elixir added from disk on one install"
        journey(row, "from-disk-second") { frame, _, homes ->
            val preselected = frame.addErlangSdkFromDisk(homes.erlang.b)
            val other = frame.addErlangSdkFromDisk(homes.erlang.a)
            val first = frame.addElixirSdkFromDisk(homes.elixir.a, preselected)
            val typed = frame.inProjectStructureSdks {
                addFromDisk(row, homes.elixir.a)
                pickInField(other)
                val named = namedAfter(row, homes.erlang.aVersion)
                rows.check(row, "it is not named as the first, '$first'", named != first)
                val name = "$named typed"
                nameField().text = name
                pickInField(preselected)
                rows.check(row, "a later pick keeps the typed Name: ${nameField().text}", nameField().text == name)
                clickButtonText("OK")
                name
            }
            checkSaved(row, homes.elixir.a, typed, homes.erlang.b, preselected)
            checkWorksInAnEditor(row, homes.elixir.a, typed, homes.erlang.b, preselected)
        }
    }

    @Test
    fun `Cancel of an add from disk keeps nothing`() {
        val row = "Cancel of an add from disk"
        journey(row, "from-disk-cancel") { frame, _, homes ->
            frame.inProjectStructureSdks {
                val before = sdkRows()
                openElixirSdkFromDisk()
                driver.ui.dialog(xQuery { byTitle(SdkPage.ELIXIR.chooserTitle) }) { clickButtonText("Cancel") }
                Thread.sleep(SETTLE_MS)
                rows.check(row, "the folder chooser's Cancel makes nothing: ${sdkRows()}", sdkRows() == before)
                rows.check(row, "and opens no dialog", driver.dialogCount() == 1)
                addFromDisk(row, homes.elixir.a)
                addErlangInField(row, homes.erlang.b, homes.erlang.bVersion)
                clickButtonText("Cancel")
            }
            checkClosed(row, "Cancel closes the dialog")
            rows.check(row, "no Elixir SDK at its home: ${sdksAt(homes.elixir.a)}", sdksAt(homes.elixir.a).isEmpty())
            rows.check(row, "no Erlang SDK at its home: ${sdksAt(homes.erlang.b)}", sdksAt(homes.erlang.b).isEmpty())
        }
    }

    @Test
    fun `an Elixir added from disk in the Project SDK field is refused until its page has an Erlang`() {
        val row = "an Elixir added from disk in the Project SDK field"
        journey(row, "from-disk-project-field", detectedErlang = true) { frame, installs, homes ->
            refusedUntilItsPageHasAnErlang(row, frame, installs, homes, homes.elixir.a) {
                addFromDiskInTheProjectSdkField(row, homes.elixir.a)
            }
        }
    }

    @Test
    fun `from the welcome screen an Elixir added from disk takes the preselected Erlang`() {
        val row = "from the welcome screen, an Elixir added from disk"
        val project = stagedProject()
        welcomeJourney(row, "welcome-from-disk") { _, homes ->
            val (elixir, registered) = welcomeScreen().takesThePreselection(row, homes) { addFromDisk(row, homes.elixir.a) }
            checkSaved(row, homes.elixir.a, elixir, homes.erlang.b, registered)
            open(project)
            checkWorksInAnEditor(row, homes.elixir.a, elixir, homes.erlang.b, registered)
        }
    }

    @Test
    fun `from the welcome screen a saved broken Elixir SDK is refused only once edited`() {
        val row = "from the welcome screen, a saved broken Elixir SDK"
        welcomeJourney(row, "welcome-saved-broken", configure = { seedElixirs(it, NO_DATA to "") }) { _, _ ->
            val renamed = "$NO_DATA renamed"
            welcomeScreen {
                inProjectStructureSdks {
                    openElixirPage(NO_DATA)
                    checkRed(row, NO_DATA)
                    clickButtonText("OK")
                }
                driver.checkClosed(row, "OK with it viewed and unedited closes the dialog")
                inProjectStructureSdks {
                    openElixirPage(NO_DATA)
                    nameField().text = renamed
                    clickApply()
                    checkRefused(row, renamed)
                    clickButtonText("Cancel")
                }
            }
            val pairing = stored(NO_DATA).pairedErlang
            rows.check(row, "it is saved as it was: '$pairing'", pairing == null)
        }
    }

    @Test
    fun `the Python facet's SDK dialog over Project Structure follows its rules`() {
        val row = "the Python facet's SDK dialog"
        val iml = moduleIml("ELIXIR_MODULE").replace(MODULE_ROOTS, "$PYTHON_FACET\n$MODULE_ROOTS")
        journey(row, "python-facet", iml = iml, plugins = listOf(PYTHON_PLUGIN), configure = { seedElixirs(it, NO_DATA to "") }) { frame, _, homes ->
            val registered = frame.addErlangSdkFromDisk(homes.erlang.b)
            val elixir = frame.inProjectStructureSdks {
                driver.selectItemByAccessibleName("Project structure categories", "Modules")
                moduleTree().clickRow { it == PYTHON }
                facetSdkDialog {
                    openElixirPage(NO_DATA)
                    checkRed(row, NO_DATA)
                    clickButtonText("OK")
                }
                rows.check(row, "OK with '$NO_DATA' viewed and unedited closes the SDK dialog", facetSdkDialogClosed())
                val elixir = facetSdkDialog {
                    rowAddedBy { newElixirSdk(homes.elixir.a) }
                    pickInField(registered)
                    val elixir = namedAfter(row, homes.erlang.bVersion)
                    clickButtonText("OK")
                    elixir
                }
                rows.check(row, "OK closes the SDK dialog", facetSdkDialogClosed())
                clickButtonText("OK")
                elixir
            }
            checkSaved(row, homes.elixir.a, elixir, homes.erlang.b, registered)
            checkWorksInAnEditor(row, homes.elixir.a, elixir, homes.erlang.b, registered)
        }
    }

    /** One launch with a project open and the Elixir install staged; a failure is [row]'s, with what it left open closed. */
    private fun journey(
        row: String,
        name: String,
        detectedErlang: Boolean = false,
        vmOptions: VMOptions.() -> Unit = {},
        configure: (Path) -> Unit = {},
        iml: String = moduleIml("ELIXIR_MODULE"),
        plugins: List<String> = emptyList(),
        body: Driver.(IdeaFrameUI, Installs, Homes) -> Unit,
    ) {
        val project = stagedProject(iml)
        launchJourney(row, name, detectedErlang, vmOptions, configure, plugins) { installs, homes ->
            open(project)
            body(frameOf(PROJECT), installs, homes)
        }
    }

    /** One launch on the welcome screen, with no project open and the Elixir install staged. */
    private fun welcomeJourney(row: String, name: String, configure: (Path) -> Unit = {}, body: Driver.(Installs, Homes) -> Unit) =
        launchJourney(row, name, configure = configure) { installs, homes ->
            ensureWelcomeScreenInForeground()
            body(installs, homes)
        }

    private fun launchJourney(
        row: String,
        name: String,
        detectedErlang: Boolean = false,
        vmOptions: VMOptions.() -> Unit = {},
        configure: (Path) -> Unit = {},
        plugins: List<String> = emptyList(),
        body: Driver.(Installs, Homes) -> Unit,
    ) {
        launchIdea("ProjectStructureDetectedTest-$name", vmOptions, configure, plugins) { homes ->
            runCatching {
                body(Installs(stageElixirInstall(), if (detectedErlang) Homes.stageErlangInstall() else ""), homes)
                val errors = ideErrors()
                rows.check(row, "no IDE error: $errors", errors.isEmpty())
            }.onFailure { failure ->
                runCatching { popupList().keyboard { key(KeyEvent.VK_ESCAPE) } }
                failedErlangStepRow(rows, row, failure) { closeProjectStructure() }
            }
        }
        rows.assertAllHeld()
    }

    /** Staged before the IDE starts: a folder made after it is unknown to its file system, and File > Open can miss it. */
    private fun stagedProject(iml: String = moduleIml("ELIXIR_MODULE")) =
        stageProject(PROJECT, iml, mapOf("mix.exs" to mixExs(PROJECT), SOURCE to SOURCE_TEXT))

    /** The Python facet page's SDK dialog, `ProjectJdksEditor`, opened over Project Structure by its browse button, with [body] inside. */
    private fun <T> ProjectStructureUI.facetSdkDialog(body: ProjectStructureUI.() -> T): T {
        x(PYTHON_SDK_BROWSE).click()
        val dialog = driver.ui.x(xQuery { byTitle(CONFIGURE_SDK) }, ProjectStructureUI::class.java)
        dialog.waitUntilReady()
        return dialog.body()
    }

    /** The Modules page's module tree, expanded: of its two trees, the one listing the [PYTHON] facet; the other is a content root's. */
    private fun ProjectStructureUI.moduleTree(): JTreeUiComponent =
        xx(JTreeUiComponent::class.java) { byType(JTree::class.java) }.list().map { it.expandAll() }
            .single { tree -> tree.collectExpandedPaths().any { it.path.last() == PYTHON } }

    private fun ProjectStructureUI.facetSdkDialogClosed(): Boolean =
        appears("the SDK dialog to close", 30.seconds) { !driver.dialogShown(CONFIGURE_SDK) }

    private fun ProjectStructureUI.openPage(name: String) {
        tree().clickRow { it == name }
        waitFor("the page of '$name'", 10.seconds) { selectedRow() == name }
    }

    private fun ProjectStructureUI.openElixirPage(name: String) {
        openPage(name)
        waitFor("the field on '$name'", 10.seconds) { x(SDK_FIELD).present() }
    }

    /**
     * Erlang B registered, then an Elixir added in Project Structure by [add]: it is never asked, takes B and is named after
     * it, then OK. The Elixir's name and B's.
     */
    private fun WindowUiComponent.takesThePreselection(row: String, homes: Homes, add: ProjectStructureUI.() -> Unit): Pair<String, String> {
        val registered = addErlangSdkFromDisk(homes.erlang.b)
        val elixir = inProjectStructureSdks {
            add()
            checkNeverAsked(row)
            val elixir = namedAfter(row, homes.erlang.bVersion)
            checkAnswered(row, elixir, registered)
            clickButtonText("OK")
            elixir
        }
        return elixir to registered
    }

    /**
     * [add] puts an Elixir at [elixirHome] in the Project page's SDK field: OK is refused naming it, until its page picks the
     * detected Erlang install; then OK saves the pair and it resolves in an editor.
     */
    private fun Driver.refusedUntilItsPageHasAnErlang(
        row: String,
        frame: IdeaFrameUI,
        installs: Installs,
        homes: Homes,
        elixirHome: String,
        add: ProjectStructureUI.() -> Unit,
    ) {
        val (erlang, elixir) = frame.inProjectStructureSdks {
            driver.selectItemByAccessibleName("Project structure categories", "Project")
            add()
            clickButtonText("OK")
            val opened = driver.sdksNamedInRefusal(row)
            driver.selectItemByAccessibleName("Project structure categories", "SDKs")
            openElixirPage(opened)
            val added = pickToAdd(row, homes.erlang.aVersion) { clickSdkFieldRow(::isErlangRow) }
            clickButtonText("OK")
            added
        }
        checkSaved(row, elixirHome, elixir, installs.erlang, erlang)
        // The project already has the Elixir SDK, so the banner never asks.
        checkResolvesInAnEditor(row, elixirHome, installs.erlang)
    }

    private fun ProjectStructureUI.closeFieldPopup() {
        x(SDK_FIELD).keyboard { key(KeyEvent.VK_ESCAPE) }
        Thread.sleep(SETTLE_MS)
    }

    /** The name of the one row [gesture] adds to the list that [matches] accepts. */
    private fun ProjectStructureUI.rowAddedBy(matches: (String) -> Boolean = { true }, gesture: () -> Unit): String {
        val before = sdkRows()
        gesture()
        waitFor("the new row", 30.seconds) { (sdkRows() - before).any(matches) }
        return (sdkRows() - before).single(matches)
    }

    /** The name the row [matches] accepts made in Add New SDK's popup, as the list first shows it. */
    private fun ProjectStructureUI.addDetected(matches: (String) -> Boolean): String = rowAddedBy { clickDetectedRow(matches) }

    /** The name of the row Add New SDK > Elixir from disk at [home] made, as the list first shows it. */
    private fun ProjectStructureUI.addFromDisk(row: String, home: String): String = rowAddedBy {
        newElixirSdk(home)
        checkNoErlangStep(row)
    }

    /** The Project page's SDK field's Add SDK > Elixir SDK... at [home]. */
    private fun ProjectStructureUI.addFromDiskInTheProjectSdkField(row: String, home: String) {
        clickProjectSdkFieldRow { it.startsWith("Add SDK") }
        val group = waitNotNull("the Add SDK group", 10.seconds) {
            driver.ui.xx("//div[@class='MyList']", JListUiComponent::class.java).list().firstOrNull { FROM_DISK_IN_GROUP in it.items }
        }
        group.clickItem(FROM_DISK_IN_GROUP)
        driver.choosePath(SdkPage.ELIXIR.chooserTitle, home)
        checkNoErlangStep(row)
    }

    /** An Erlang step that opens fails the row, whose failure cancels it. */
    private fun ProjectStructureUI.checkNoErlangStep(row: String) {
        val asked = appears("the Erlang step", 5.seconds) { driver.dialogShown(ERLANG_STEP_TITLE) }
        rows.check(row, "no Erlang step opens", !asked)
        check(!asked) { "the Erlang step opened" }
    }

    /**
     * [gesture] makes an Erlang Model through the field: the list gains it, and the Elixir page is selected again, named
     * after the Erlang at [version]. The Erlang's name and the Elixir's.
     */
    private fun ProjectStructureUI.pickToAdd(row: String, version: String, gesture: () -> Unit): Pair<String, String> =
        rowAddedBy(::isErlangName, gesture) to namedAfter(row, version)

    /** [pickToAdd] with an Erlang at [home] added from disk through the field's first row. */
    private fun ProjectStructureUI.addErlangInField(row: String, home: String, version: String): Pair<String, String> =
        pickToAdd(row, version) {
            clickSdkFieldFirstRow()
            driver.choosePath(SdkPage.ERLANG.chooserTitle, home)
        }

    /** The Elixir page stays selected and its Name comes to follow the Erlang at [version], in the field and the list; that name. */
    private fun ProjectStructureUI.namedAfter(row: String, version: String): String {
        val suffix = " (Erlang $version)"
        val named = appears("the Name to follow the Erlang", 20.seconds) { selectedRow()?.endsWith(suffix) == true }
        val name = selectedRow().orEmpty()
        rows.check(row, "the Elixir page is selected, named after Erlang $version: '$name'", named)
        rows.check(row, "the Name field shows it: ${nameField().text}", nameField().text == name)
        return name
    }

    /** What the page shows while it asks: nothing picked, the text, the outline and its popup, and no dialog over it. */
    private fun ProjectStructureUI.checkAsks(row: String, elixir: String) {
        rows.check(row, "the new Model's page is selected: ${selectedRow()}", selectedRow() == elixir)
        rows.check(row, "the field has nothing selected: ${fieldText()}", "rlang" !in fieldText())
        rows.check(row, "the text under the field is shown", messageShown())
        rows.check(row, "the field has the error outline: ${outline()}", outline() == ERROR_OUTLINE)
        x(SDK_FIELD).moveMouse()
        val popup = appears("the field's popup", 5.seconds) { driver.validatorPopup() }
        rows.check(row, "the popup on the field says it", popup)
        rows.check(row, "no dialog is over the page", driver.dialogCount() == 1)
    }

    /** None of the asking shows while the page preselects. */
    private fun ProjectStructureUI.checkNeverAsked(row: String) {
        rows.check(row, "the text under the field is not shown", !messageShown())
        rows.check(row, "the field has no outline: ${outline()}", outline() == null)
    }

    /** [name]'s page, viewed, shows the text and the error outline. */
    private fun ProjectStructureUI.checkRed(row: String, name: String) {
        rows.check(row, "'$name' shows the text", appears("the text", 10.seconds) { messageShown() })
        rows.check(row, "'$name' has the error outline: ${outline()}", outline() == ERROR_OUTLINE)
    }

    /** What the page shows once [erlang] is chosen: the list on [elixir], the pick in the field, and none of the asking. */
    private fun ProjectStructureUI.checkAnswered(row: String, elixir: String, erlang: String) {
        rows.check(row, "the Elixir page is still selected: ${selectedRow()}", selectedRow() == elixir)
        rows.check(row, "the field shows the pick '$erlang': ${fieldText()}", erlang in fieldText())
        rows.check(row, "the text under the field is gone", !messageShown())
        rows.check(row, "the outline is gone: ${outline()}", outline() == null)
        x(SDK_FIELD).moveMouse()
        Thread.sleep(SETTLE_MS)
        rows.check(row, "no popup on the field", !driver.validatorPopup())
    }

    private fun ProjectStructureUI.checkRefused(row: String, elixir: String) {
        val box = driver.refusalBox()
        rows.check(row, "refused, naming '$elixir': $box", box?.contains("SDK '$elixir' is invalid. $MESSAGE") == true)
    }

    private fun Driver.sdksNamedInRefusal(row: String): String {
        val box = refusalBox()
        rows.check(row, "OK is refused with the sentence: $box", box?.contains(MESSAGE) == true)
        return checkNotNull(box?.let { Regex("SDK '(.+?)' is invalid").find(it)?.groupValues?.get(1) }) { "no SDK named in: $box" }
    }

    private fun Driver.checkClosed(row: String, check: String) =
        rows.check(row, check, appears("the dialog to close", 30.seconds) { !ui.projectStructure().present() })

    private fun Driver.checkSaved(row: String, elixirHome: String, elixir: String, erlangHome: String, erlang: String) {
        checkClosed(row, "OK closes the dialog")
        rows.check(row, "the Elixir SDK is in the table: ${sdksAt(elixirHome)}", elixir in sdksAt(elixirHome))
        rows.check(row, "the Erlang SDK is in the table: ${sdksAt(erlangHome)}", erlang in sdksAt(erlangHome))
        val paired = stored(elixir).pairedErlang
        rows.check(row, "'$elixir' is paired with '$erlang': '$paired'", paired == erlang)
    }

    /** The project given [elixir] through the chooser, then a standard-library call and `:lists` resolved in its showing editor. */
    private fun Driver.checkWorksInAnEditor(row: String, elixirHome: String, elixir: String, erlangHome: String, erlang: String) {
        askTheChooser(rows, row, SOURCE)
        pickInChooser(rows, row, elixir, erlang)
        checkResolvesInAnEditor(row, elixirHome, erlangHome)
    }

    /** A standard-library call resolved into [elixirHome] and `:lists` into [erlangHome] in the project's showing editor. */
    private fun Driver.checkResolvesInAnEditor(row: String, elixirHome: String, erlangHome: String) {
        waitForIndicators(singleProject(), 5.minutes)
        openFile(SOURCE, waitForCodeAnalysis = false)
        rows.check(row, "the file's editor is showing", appears("the editor", 30.seconds) { editorPath().endsWith(SOURCE) })
        rows.check(row, "the banner no longer asks", !bannerLabel(CHOOSE_LABEL).present())
        checkResolves(row, "Enum.count", "Enum.".length, elixirHome)
        checkResolves(row, ":lists.reverse", ":lists.".length, erlangHome)
    }

    /** Go to Declaration on [marker], at [inset], opens a file under [home]. */
    private fun Driver.checkResolves(row: String, marker: String, inset: Int, home: String) {
        openFile(SOURCE, waitForCodeAnalysis = false)
        ideFrame {
            codeEditor().apply {
                click()
                moveCaretToOffset(text.indexOf(marker) + inset)
                invokeActionByShortcut("GotoDeclaration")
            }
        }
        val popup = ui.popup()
        val moved = appears("the declaration", 30.seconds) {
            // A function with a source and a decompiled target asks which; both are in the SDK, so the first is as good.
            if (popup.present()) {
                println("SDK settings row $row: '$marker' asks: ${popup.getAllTexts().asString(" ")}")
                popup.keyboard { key(KeyEvent.VK_ENTER) }
            }
            !editorPath().endsWith(SOURCE)
        }
        val target = editorPath()
        rows.check(row, "'$marker' resolves under $home: $target", moved && target.startsWith(home))
    }

    private fun Driver.editorPath(): String {
        var path = ""
        ideFrame { path = codeEditor().editor.getVirtualFile().getPath() }
        return path
    }

    /** The name of the SDK that the tree of the SDKs page has selected. */
    private fun ProjectStructureUI.selectedRow(): String? = tree().collectSelectedPaths().singleOrNull()?.path?.last()

    private fun ProjectStructureUI.sdkRows(): List<String> = tree().collectExpandedPaths().map { it.path.last() }

    private fun ProjectStructureUI.messageShown(): Boolean = x("//div[@class='JLabel' and @visible_text='$MESSAGE']").present()

    private fun ProjectStructureUI.outline(): String? =
        driver.cast(x(SDK_FIELD).component, ClientProperties::class).getClientProperty(OUTLINE)?.toString()

    /** Whether the validator's popup is up: the only editor pane showing the sentence. */
    private fun Driver.validatorPopup(): Boolean = ui.xx(xQuery { byType(JEditorPane::class.java) }).list().any { pane ->
        val document = cast(pane.component, TextPane::class).getDocument()
        document.getText(0, document.getLength()).trim() == MESSAGE
    }

    private fun Driver.dialogCount(): Int = ui.xx("//div[@class='MyDialog']").list().size

    /** The box Apply or OK refused with: its text, and it closed; null when none showed within the wait. */
    private fun Driver.refusalBox(): String? {
        val box = ui.x(xQuery { byTitle("Cannot Save Settings") })
        if (!appears("the box", 5.seconds) { box.present() }) return null
        // The box wraps its text, and each wrapped line is its own piece.
        val text = box.getAllTexts().asString(" ").replace(Regex("""\s+"""), " ")
        box.clickButtonText("OK")
        return text
    }

    /**
     * Registered Elixir Models at the A install, each a name and its `additional` element, empty for none, so that one
     * loads with null data.
     */
    private fun seedElixirs(configDir: Path, vararg seeds: Pair<String, String>) {
        val home = Path.of(IdeTestContext.elixirSdkPath).normalize()
        val jdks = seeds.joinToString("\n") { (name, additional) ->
            """
            <jdk version="2">
              <name value="$name" />
              <type value="Elixir SDK" />
              <version value="1.20.4" />
              <homePath value="$home" />
              <roots>
                <annotationsPath><root type="composite" /></annotationsPath>
                <classPath><root type="composite" /></classPath>
                <javadocPath><root type="composite" /></javadocPath>
                <sourcePath><root type="composite" /></sourcePath>
              </roots>
              $additional
            </jdk>
            """.trimIndent()
        }
        configDir.resolve("options").createDirectories().resolve("jdk.table.xml").writeText(
            "<application>\n<component name=\"ProjectJdkTable\">\n$jdks\n</component>\n</application>\n",
        )
    }

    /**
     * A `kerl` that answers late and lists nothing: the Erlang scan's one command, so detection reports Erlang homes only
     * after a pick. The `PATH` to give the IDE.
     */
    private fun stageSlowKerl(): String {
        val bin = NoScreen.privateHome.resolve("slow-kerl").createDirectories()
        bin.resolve("kerl").apply { writeText("#!/bin/sh\nsleep $DETECTION_DELAY_SECONDS\n") }.toFile().setExecutable(true)
        return "$bin${File.pathSeparator}${System.getenv("PATH")}"
    }

    private fun isElixirRow(row: String): Boolean = "installs/elixir" in row || ELIXIR_VERSION_ROW.containsMatchIn(row)

    private fun isErlangRow(row: String): Boolean = "installs/erlang" in row

    /** An Erlang SDK's name in the list, as the Erlang type suggests it; an Elixir's never holds this. */
    private fun isErlangName(name: String): Boolean = "Erlang for Elixir" in name

    private class Installs(val elixir: String, val erlang: String)

    private companion object {
        const val PROJECT = "detected_journey"
        const val SOURCE = "lib/detected_journey.ex"
        val SOURCE_TEXT = """
            defmodule DetectedJourney do
              def elixir_call, do: Enum.count([])
              def erlang_call, do: :lists.reverse([])
            end
        """.trimIndent()
        const val NO_DATA = "Elixir NoData"
        const val NOT_FOUND = "Elixir NotFound"
        const val GONE = "Erlang Gone"
        const val NOT_FOUND_DATA = """<additional erlang-sdk-name="$GONE" />"""
        const val PYTHON_PLUGIN = "PythonCore"
        const val PYTHON = "Python"
        const val PYTHON_FACET =
            """<component name="FacetManager"><facet type="$PYTHON" name="$PYTHON"><configuration sdkName="" /></facet></component>"""
        const val MODULE_ROOTS = """<component name="NewModuleRootManager">"""
        const val PYTHON_SDK_BROWSE = "//div[@class='PythonSdkComboBox']//div[@class='FixedSizeButton']"
        const val CONFIGURE_SDK = "Configure SDK"
        const val FROM_DISK_IN_GROUP = "Elixir SDK\u2026"
        const val MESSAGE = "An Erlang SDK must be chosen."
        const val OUTLINE = "JComponent.outline"
        const val ERROR_OUTLINE = "error"
        const val SETTLE_MS = 2_000L
        const val DETECTION_DELAY_SECONDS = 8
        val ELIXIR_VERSION_ROW = Regex("""^Elixir \d""")
    }
}

@Remote("javax.swing.JComponent")
private interface ClientProperties {
    fun getClientProperty(key: Any): Any?
}
