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
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.elements.tree
import com.intellij.driver.sdk.ui.components.idea.ProjectStructureUI
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.models.VMOptions
import org.elixir_lang.ui.code_intelligence.TextPane
import org.elixir_lang.ui.framework.core.IdeTestContext
import org.elixir_lang.ui.framework.core.NoScreen
import org.elixir_lang.ui.framework.core.SDK_FIELD
import org.elixir_lang.ui.framework.core.addErlangSdkFromDisk
import org.elixir_lang.ui.framework.core.choosePath
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.clickDetectedRow
import org.elixir_lang.ui.framework.core.clickProjectSdkFieldRow
import org.elixir_lang.ui.framework.core.clickSdkFieldFirstRow
import org.elixir_lang.ui.framework.core.clickSdkFieldRow
import org.elixir_lang.ui.framework.core.inProjectStructureSdks
import org.elixir_lang.ui.framework.core.openSdkField
import org.elixir_lang.ui.framework.core.popupList
import org.elixir_lang.ui.framework.core.projectStructure
import org.elixir_lang.ui.framework.core.sdksAt
import org.elixir_lang.ui.framework.core.selectItemByAccessibleName
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
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * On IntelliJ IDEA's Project Structure > SDKs, with a project open: a Detected Elixir install opens its own page, which asks
 * for an Erlang SDK in the platform's SDK field. Each way of giving it one ends with the SDK working in an editor.
 */
class ProjectStructureDetectedTest {
    private val rows = Rows()

    @Test
    fun `a detected Elixir opens its page and takes a detected Erlang`() {
        val row = "a detected Erlang"
        journey(row, "detected", detectedErlang = true) { frame, installs, _ ->
            val (elixir, erlang) = frame.inProjectStructureSdks {
                val elixir = addDetected(::isElixirRow)
                checkAsks(row, elixir)
                val table = driver.sdksAt(installs.elixir)
                clickApply()
                checkRefused(row, elixir)
                rows.check(row, "the SDK table is unchanged after the refusal", driver.sdksAt(installs.elixir) == table)
                val erlang = pickToAdd(row, elixir) { clickSdkFieldRow(::isErlangRow) }
                checkAnswered(row, elixir, erlang)
                clickButtonText("OK")
                elixir to erlang
            }
            checkSaved(row, installs.elixir, elixir, installs.erlang, erlang)
            checkWorksInAnEditor(row, elixir, erlang, installs.elixir, installs.erlang)
        }
    }

    @Test
    fun `a registered Erlang can be chosen`() {
        val row = "a registered Erlang"
        journey(row, "registered") { frame, installs, homes ->
            val registered = frame.addErlangSdkFromDisk(homes.erlang.b)
            val elixir = frame.inProjectStructureSdks {
                val elixir = addDetected(::isElixirRow)
                checkAsks(row, elixir)
                clickSdkFieldFirstRow()
                waitFor("the field to show the pick", 10.seconds) { registered in fieldText() }
                checkAnswered(row, elixir, registered)
                clickButtonText("OK")
                elixir
            }
            checkSaved(row, installs.elixir, elixir, homes.erlang.b, registered)
            checkWorksInAnEditor(row, elixir, registered, installs.elixir, homes.erlang.b)
        }
    }

    @Test
    fun `an Erlang added from disk can be chosen`() {
        val row = "an Erlang added from disk"
        journey(row, "from-disk") { frame, installs, homes ->
            val (elixir, erlang) = frame.inProjectStructureSdks {
                val elixir = addDetected(::isElixirRow)
                val erlang = pickToAdd(row, elixir) {
                    clickSdkFieldFirstRow()
                    driver.choosePath(SdkPage.ERLANG.chooserTitle, homes.erlang.b)
                }
                rows.check(row, "the message is gone", !messageShown())
                clickButtonText("OK")
                elixir to erlang
            }
            checkSaved(row, installs.elixir, elixir, homes.erlang.b, erlang)
            checkWorksInAnEditor(row, elixir, erlang, installs.elixir, homes.erlang.b)
        }
    }

    @Test
    fun `a refusal with another page selected leaves the list where it is`() {
        val row = "a refusal with another page selected"
        journey(row, "refusal-elsewhere", detectedErlang = true) { frame, _, _ ->
            frame.inProjectStructureSdks {
                val erlang = addDetected(::isErlangRow)
                val elixir = addDetected(::isElixirRow)
                tree().clickRow { it == erlang }
                waitFor("the other page", 10.seconds) { selectedRow() == erlang }
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
        journey(row, "cancel", detectedErlang = true) { frame, installs, _ ->
            frame.inProjectStructureSdks {
                val elixir = addDetected(::isElixirRow)
                pickToAdd(row, elixir) { clickSdkFieldRow(::isErlangRow) }
                clickButtonText("Cancel")
            }
            rows.check(row, "no Elixir SDK at the install: ${sdksAt(installs.elixir)}", sdksAt(installs.elixir).isEmpty())
            rows.check(row, "no Erlang SDK at the install: ${sdksAt(installs.erlang)}", sdksAt(installs.erlang).isEmpty())
        }
    }

    @Test
    fun `a detected Elixir picked in the Project SDK field is refused until its page has an Erlang`() {
        val row = "the Project SDK field"
        journey(row, "project-field", detectedErlang = true) { frame, installs, _ ->
            frame.inProjectStructureSdks {
                driver.selectItemByAccessibleName("Project structure categories", "Project")
                clickProjectSdkFieldRow(::isElixirRow)
                clickButtonText("OK")
                val elixir = driver.sdksNamedInRefusal(row)
                driver.selectItemByAccessibleName("Project structure categories", "SDKs")
                openElixirPage(elixir)
                val erlang = pickToAdd(row, elixir) { clickSdkFieldRow(::isErlangRow) }
                clickButtonText("OK")
                driver.checkSaved(row, installs.elixir, elixir, installs.erlang, erlang)
            }
        }
    }

    @Test
    fun `an Elixir SDK already saved with no Erlang is left as it is`() {
        val row = "an Elixir SDK saved with no Erlang"
        journey(row, "saved-unpaired", configure = { seedUnpairedElixir(it, UNPAIRED) }) { frame, _, _ ->
            val renamed = "$UNPAIRED renamed"
            frame.inProjectStructureSdks {
                openElixirPage(UNPAIRED)
                rows.check(row, "no message", !messageShown())
                rows.check(row, "no outline: ${outline()}", outline() == null)
                nameField().text = renamed
                clickApply()
                rows.check(row, "Apply is not refused", driver.refusalBox() == null)
                clickButtonText("OK")
            }
            val paired = stored(renamed).pairedErlang
            rows.check(row, "it is saved under its new name with no pairing: '$paired'", paired == null)
        }
    }

    @Test
    fun `an Erlang added and then replaced is saved by OK`() {
        val row = "an Erlang added and then replaced"
        journey(row, "replaced", detectedErlang = true) { frame, installs, homes ->
            val registered = frame.addErlangSdkFromDisk(homes.erlang.b)
            val (elixir, replaced) = frame.inProjectStructureSdks {
                val elixir = addDetected(::isElixirRow)
                val replaced = pickToAdd(row, elixir) { clickSdkFieldRow(::isErlangRow) }
                clickSdkFieldRow { registered in it }
                waitFor("the field to show the replacement", 10.seconds) { registered in fieldText() }
                rows.check(row, "the replaced Erlang stays in the list: ${sdkRows()}", replaced in sdkRows())
                clickButtonText("OK")
                elixir to replaced
            }
            checkSaved(row, installs.elixir, elixir, homes.erlang.b, registered)
            val saved = sdksAt(installs.erlang)
            rows.check(row, "the replaced Erlang is in the table: $saved", saved == listOf(replaced))
        }
    }

    @Test
    fun `a typed name keeps the Elixir page selected`() {
        val row = "a typed name"
        journey(row, "typed-name", detectedErlang = true) { frame, _, _ ->
            frame.inProjectStructureSdks {
                val elixir = addDetected(::isElixirRow)
                val name = nameField()
                name.click()
                name.keyboard { typeText(" typed") }
                waitFor("the typed text", 10.seconds) { "typed" in name.text }
                val typed = name.text
                rowAddedBy { clickSdkFieldRow(::isErlangRow) }
                val onTheElixirPage = appears("the Elixir page", 10.seconds) { selectedRow() in setOf(elixir, typed) }
                Thread.sleep(SETTLE_MS)
                rows.check(row, "the Elixir page is selected: ${selectedRow()}", onTheElixirPage && selectedRow() in setOf(elixir, typed))
                rows.check(row, "the Name field keeps the typed text: ${name.text}", name.text == typed)
                clickButtonText("Cancel")
            }
        }
    }

    @Test
    fun `a pick stays when detection arrives after it`() {
        val row = "detection arriving after a pick"
        val slowPath = stageSlowKerl()
        journey(row, "detection-after", detectedErlang = true, vmOptions = { withEnv("PATH", slowPath) }) { frame, _, homes ->
            val registered = frame.addErlangSdkFromDisk(homes.erlang.b)
            val elixir = frame.inProjectStructureSdks {
                val elixir = addDetected(::isElixirRow)
                val atThePick = clickSdkFieldFirstRow()
                rows.check(row, "detection has not reported the Erlang at the pick: $atThePick", atThePick.none(::isErlangRow))
                waitFor("the field to show the pick", 10.seconds) { registered in fieldText() }
                // Closing the popup ends the field's listening for good, so this open only swaps the model.
                openSdkField { true }
                closeFieldPopup()
                rows.check(row, "reopening the field keeps the pick: ${fieldText()}", registered in fieldText())
                rows.check(row, "reopening the field brings no message back", !messageShown())
                clickButtonText("OK")
                elixir
            }
            frame.inProjectStructureSdks {
                openElixirPage(elixir)
                openSdkField(::isErlangRow)
                closeFieldPopup()
                rows.check(row, "detection arriving keeps the stored pairing: ${fieldText()}", registered in fieldText())
                rows.check(row, "detection arriving brings no message", !messageShown())
                clickButtonText("Cancel")
            }
        }
    }

    /** One launch with a project open and the Elixir install staged; a failure is [row]'s, with what it left open closed. */
    private fun journey(
        row: String,
        name: String,
        detectedErlang: Boolean = false,
        vmOptions: VMOptions.() -> Unit = {},
        configure: (Path) -> Unit = {},
        body: Driver.(IdeaFrameUI, Installs, Homes) -> Unit,
    ) {
        launchIdea("ProjectStructureDetectedTest-$name", vmOptions, configure) { homes ->
            runCatching {
                val installs = Installs(stageElixirInstall(), if (detectedErlang) Homes.stageErlangInstall() else "")
                open(stageProject(PROJECT, moduleIml("ELIXIR_MODULE"), mapOf("mix.exs" to mixExs(PROJECT), SOURCE to SOURCE_TEXT)))
                body(frameOf(PROJECT), installs, homes)
                val errors = ideErrors()
                rows.check(row, "no IDE error: $errors", errors.isEmpty())
            }.onFailure { failure ->
                runCatching { popupList().keyboard { key(KeyEvent.VK_ESCAPE) } }
                failedErlangStepRow(rows, row, failure) { closeProjectStructure() }
            }
        }
        rows.assertAllHeld()
    }

    private fun ProjectStructureUI.openElixirPage(name: String) {
        tree().clickRow { it == name }
        waitFor("the page of '$name'", 10.seconds) { selectedRow() == name && x(SDK_FIELD).present() }
    }

    private fun ProjectStructureUI.closeFieldPopup() {
        x(SDK_FIELD).keyboard { key(KeyEvent.VK_ESCAPE) }
        Thread.sleep(SETTLE_MS)
    }

    /** The name of the one row [gesture] adds to the list. */
    private fun ProjectStructureUI.rowAddedBy(gesture: () -> Unit): String {
        val before = sdkRows()
        gesture()
        waitFor("the new row", 30.seconds) { (sdkRows() - before).isNotEmpty() }
        return (sdkRows() - before).single()
    }

    /** The name the row [matches] accepts made in Add New SDK's popup. */
    private fun ProjectStructureUI.addDetected(matches: (String) -> Boolean): String = rowAddedBy { clickDetectedRow(matches) }

    /** [gesture] makes an Erlang Model through the field: the list gains it, and the Elixir page is selected again. Its name. */
    private fun ProjectStructureUI.pickToAdd(row: String, elixir: String, gesture: () -> Unit): String {
        val erlang = rowAddedBy(gesture)
        val selected = appears("the Elixir page selected", 10.seconds) { selectedRow() == elixir }
        rows.check(row, "the Elixir page is selected after the add: ${selectedRow()}", selected)
        return erlang
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

    private fun Driver.checkSaved(row: String, elixirHome: String, elixir: String, erlangHome: String, erlang: String) {
        val closed = appears("the dialog to close", 30.seconds) { !ui.projectStructure().present() }
        rows.check(row, "OK closes the dialog", closed)
        rows.check(row, "the Elixir SDK is in the table: ${sdksAt(elixirHome)}", elixir in sdksAt(elixirHome))
        rows.check(row, "the Erlang SDK is in the table: ${sdksAt(erlangHome)}", erlang in sdksAt(erlangHome))
        val paired = stored(elixir).pairedErlang
        rows.check(row, "'$elixir' is paired with '$erlang': '$paired'", paired == erlang)
    }

    /** The project given [elixir] through the chooser, then a standard-library call and `:lists` resolved in its showing editor. */
    private fun Driver.checkWorksInAnEditor(row: String, elixir: String, erlang: String, elixirHome: String, erlangHome: String) {
        askTheChooser(rows, row, SOURCE)
        pickInChooser(rows, row, elixir, erlang)
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

    private fun ProjectStructureUI.fieldText(): String = x(SDK_FIELD).getAllTexts().asString(" ")

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

    /** A registered Elixir Model named [name] at the A install, with no `additional` element, so it loads with null data. */
    private fun seedUnpairedElixir(configDir: Path, name: String) {
        val home = Path.of(IdeTestContext.elixirSdkPath).normalize()
        configDir.resolve("options").createDirectories().resolve("jdk.table.xml").writeText(
            """
            <application>
              <component name="ProjectJdkTable">
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
                </jdk>
              </component>
            </application>
            """.trimIndent(),
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
        const val UNPAIRED = "Elixir Unpaired"
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
