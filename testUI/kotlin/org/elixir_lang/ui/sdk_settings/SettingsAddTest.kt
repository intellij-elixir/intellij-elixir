package org.elixir_lang.ui.sdk_settings

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.UiText.Companion.asString
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.settings.SettingsDialogUiComponent
import com.intellij.driver.sdk.waitFor
import org.elixir_lang.ui.framework.core.SDK_FIELD
import org.elixir_lang.ui.framework.core.choosePath
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.clickSdkFieldRow
import org.elixir_lang.ui.framework.core.fieldText
import org.elixir_lang.ui.framework.core.openSdkField
import org.elixir_lang.ui.framework.core.pickInField
import org.elixir_lang.ui.framework.core.popupList
import org.elixir_lang.ui.framework.core.sdksAt
import org.elixir_lang.ui.sdk_choice.mixExs
import org.elixir_lang.ui.sdk_choice.moduleIml
import org.elixir_lang.ui.sdk_choice.open
import org.elixir_lang.ui.sdk_choice.stageProject
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * On PyCharm's Settings > Elixir > SDKs: an Elixir SDK added there asks for its Erlang SDK in the platform's SDK field on
 * its page, as Project Structure's does, with no Erlang step; Apply is refused until one is chosen; and Cancel takes out
 * the Erlang SDKs the field made, and the data a refused Apply gave a saved SDK. Each row that adds an SDK ends with it
 * working in an editor.
 */
class SettingsAddTest {
    private val rows = Rows()

    /** Detected Erlang installs of the OTP major before the Elixir install's, so that none is ever preselected. */
    @Test
    fun `adds on a project`() {
        launchPyCharm("SettingsAddTest-project") { homes ->
            val detected = (1..4).map { Homes.stageDetectedErlang("$it") }
            closeInternalErrors("setup")
            row(ASKS) { asksAndIsRefused(homes, detected) }
            row(CANCEL) { cancelLeavesNeither(homes, detected) }
            row(REPLACED) { madeAndReplaced(homes, detected) }
        }
        rows.assertAllHeld()
    }

    /** Erlang SDKs of the Elixir install's OTP registered, so that one is preselected. */
    @Test
    fun `adds with a matching registered Erlang`() {
        launchPyCharm("SettingsAddTest-matching") { homes ->
            closeInternalErrors("setup")
            val erlangB = addErlangOnPage(homes.erlang.b)
            row(PRESELECTED) { preselectedFromTheWelcomeFrame(homes, erlangB) }
            val erlangA = addErlangOnPage(homes.erlang.a)
            row(NAME) { nameFollowsThePick(homes, erlangA, erlangB) }
            row(INSTALL) { aDetectedInstallIsSavedOnce(homes) }
        }
        rows.assertAllHeld()
    }

    /** `Elixir NoData`, saved with no Erlang SDK, and a paired Elixir SDK beside it. */
    @Test
    fun `saved SDKs`() {
        launchPyCharm("SettingsAddTest-saved", configure = { seedElixirs(it, NO_DATA to "") }) { homes ->
            closeInternalErrors("setup")
            val erlang = addErlangOnPage(homes.erlang.b)
            val other = addElixirOnPage(homes.elixir.b, erlang).name
            row(DISCARDS) { cancelDiscardsARefusedEdit(other) }
            row(RED) { redWhenViewedRefusedOnceEdited(other) }
        }
        rows.assertAllHeld()
    }

    /**
     * Settings opened over an opened project: an add asks for an Erlang in the field, with no step, and Apply is refused;
     * then a Detected Erlang picked, and the one OK saves both, paired.
     */
    private fun Driver.asksAndIsRefused(homes: Homes, detected: List<String>) {
        openPyCharmProject("settings_add_asks")
        val erlangHome = detected.first()
        val before = tableNames()
        val elixir = inSettings(SdkPage.ELIXIR) {
            addFromDisk(ASKS, homes.elixir.a)
            checkAsks(ASKS)
            clickApply()
            checkRefused(ASKS)
            val unsaved = driver.tableNames()
            rows.check(ASKS, "nothing is saved: $unsaved", unsaved == before)
            pickDetected(erlangHome)
            checkAnswered(ASKS)
            nameField().text.also { clickButtonText("OK") }
        }
        val erlang = savedAt(ASKS, erlangHome)
        checkPaired(rows, ASKS, elixir, erlang)
        val table = tableNames()
        rows.check(ASKS, "the one OK saves both: $table", elixir in table && erlang in table)
        checkWorksInAnEditor(rows, ASKS, SOURCE, homes.elixir.a, elixir, erlangHome, erlang)
    }

    /**
     * Each way the field makes an Erlang, then Cancel: the table is as it was and no page lists what it lacks. Then an Erlang
     * removed on Internal Erlang SDKs while it is open, and an Apply before Cancel, which keeps both.
     */
    private fun Driver.cancelLeavesNeither(homes: Homes, detected: List<String>) {
        val (_, second, third, fourth) = detected
        cancelled("a Detected row", homes.elixir.a) {
            pickDetected(second)
        }
        cancelled("from disk", homes.elixir.a) {
            // ASKS's Erlang is registered, so the field's first row is that SDK, not its from-disk row.
            clickSdkFieldRow { "from disk" in it }
            driver.choosePath(SdkPage.ERLANG.chooserTitle, second)
        }
        cancelled("after a refused Apply", homes.elixir.a) {
            clickApply()
            checkRefused(CANCEL)
            pickDetected(second)
        }
        cancelled("made, then replaced", homes.elixir.a) {
            pickDetected(second)
            Thread.sleep(SETTLE_MS)
            pickDetected(fourth)
        }
        cancelled("made, then removed on Internal Erlang SDKs", homes.elixir.a) { table ->
            pickDetected(third)
            Thread.sleep(SETTLE_MS)
            openSdkPage(SdkPage.ERLANG)
            val made = rowNames().single { it !in table }
            removeRow(made)
            val gone = appears("the removal", 10.seconds) { made !in rowNames() }
            rows.check(CANCEL, "'$made' is gone from Internal Erlang SDKs: ${rowNames()}", gone)
        }

        openPyCharmProject("settings_add_cancel")
        val elixir = inSettings(SdkPage.ELIXIR) {
            addFromDisk(CANCEL, homes.elixir.a)
            pickDetected(second)
            Thread.sleep(SETTLE_MS)
            val elixir = nameField().text
            clickApply()
            appears("the Apply", 10.seconds) { elixir in driver.tableNames() }
            clickButtonText("Cancel")
            elixir
        }
        val erlang = savedAt(CANCEL, second)
        val table = tableNames()
        rows.check(CANCEL, "Apply then Cancel keeps the Elixir SDK: $table", elixir in table)
        checkPaired(rows, CANCEL, elixir, erlang)
        checkWorksInAnEditor(rows, CANCEL, SOURCE, homes.elixir.a, elixir, second, erlang)
    }

    /**
     * Two Detected picks, then OK: both Erlangs are saved, the Elixir is paired with the second, and the first can be
     * removed on Internal Erlang SDKs.
     */
    private fun Driver.madeAndReplaced(homes: Homes, detected: List<String>) {
        openPyCharmProject("settings_add_replaced")
        val (_, _, firstHome, secondHome) = detected
        val elixir = inSettings(SdkPage.ELIXIR) {
            addFromDisk(REPLACED, homes.elixir.a)
            pickDetected(firstHome)
            Thread.sleep(SETTLE_MS)
            pickDetected(secondHome)
            Thread.sleep(SETTLE_MS)
            nameField().text.also { clickButtonText("OK") }
        }
        val replaced = savedAt(REPLACED, firstHome)
        val erlang = savedAt(REPLACED, secondHome)
        checkPaired(rows, REPLACED, elixir, erlang)
        inSettings(SdkPage.ERLANG) {
            removeRow(replaced)
            clickButtonText("OK")
        }
        val removed = appears("the removal", 10.seconds) { sdksAt(firstHome).isEmpty() }
        rows.check(REPLACED, "'-' on Internal Erlang SDKs removes '$replaced': ${sdksAt(firstHome)}", removed)
        checkWorksInAnEditor(rows, REPLACED, SOURCE, homes.elixir.a, elixir, secondHome, erlang)
    }

    /**
     * From PyCharm's welcome, the frame it opens on its own Welcome project at launch: an add takes [erlangB], the
     * matching registered Erlang, and is named after it, never asking.
     */
    private fun Driver.preselectedFromTheWelcomeFrame(homes: Homes, erlangB: String) {
        val project = stagePyCharmProject("settings_add_preselected")
        rows.check(PRESELECTED, "Settings opens over PyCharm's welcome", frameOf(WELCOME).present())
        val elixir = inSettings(SdkPage.ELIXIR) {
            addFromDisk(PRESELECTED, homes.elixir.a)
            checkNeverAsked(rows, PRESELECTED)
            val preselected = appears("the preselection", 20.seconds) { erlangB in fieldText() }
            rows.check(PRESELECTED, "'$erlangB' is preselected: '${fieldText()}'", preselected)
            checkNeverAsked(rows, PRESELECTED)
            val elixir = nameField().text
            rows.check(PRESELECTED, "the Name follows it: '$elixir'", elixir.endsWith("(Erlang ${homes.erlang.bVersion})"))
            clickButtonText("OK")
            elixir
        }
        checkPaired(rows, PRESELECTED, elixir, erlangB)
        open(project)
        checkWorksInAnEditor(rows, PRESELECTED, SOURCE, homes.elixir.a, elixir, homes.erlang.b, erlangB)
    }

    /**
     * The field lists both registered Erlangs; the Name follows a pick after the preselection; a second add on the install,
     * picking the same, is numbered; and a typed Name stays through a later pick.
     */
    private fun Driver.nameFollowsThePick(homes: Homes, erlangA: String, erlangB: String) {
        openPyCharmProject("settings_add_name")
        val version = mapOf(erlangA to homes.erlang.aVersion, erlangB to homes.erlang.bVersion)
        val home = mapOf(erlangA to homes.erlang.a, erlangB to homes.erlang.b)
        val typed = "Elixir Typed On Settings"
        val (picked, kept) = inSettings(SdkPage.ELIXIR) {
            addFromDisk(NAME, homes.elixir.a)
            val listed = openSdkField { it.startsWith(erlangA) || it.startsWith(erlangB) }
            driver.popupList().keyboard { key(KeyEvent.VK_ESCAPE) }
            rows.check(NAME, "the field lists '$erlangA' and '$erlangB': $listed", listed.any { it.startsWith(erlangA) } && listed.any { it.startsWith(erlangB) })
            waitFor("the preselection", 20.seconds) { fieldText().let { erlangA in it || erlangB in it } }
            val (preselected, other) = if (erlangA in fieldText()) erlangA to erlangB else erlangB to erlangA
            pickInField(other)
            val named = appears("the Name to follow the pick", 10.seconds) { nameField().text.endsWith("(Erlang ${version[other]})") }
            rows.check(NAME, "the Name follows a pick of '$other': '${nameField().text}'", named)
            val first = nameField().text

            addFromDisk(NAME, homes.elixir.a)
            pickInField(other)
            val numbered = appears("the numbered Name", 10.seconds) { nameField().text == "$first (2)" }
            rows.check(NAME, "a second SDK on the install, on '$other', is '$first (2)': '${nameField().text}'", numbered)
            typeName(typed)
            pickInField(preselected)
            Thread.sleep(SETTLE_MS)
            val shown = nameField().text
            rows.check(NAME, "a typed Name stays through a pick: '$shown'", shown == typed)
            clickButtonText("OK")
            preselected to first
        }
        checkPaired(rows, NAME, typed, picked)
        val table = tableNames()
        rows.check(NAME, "the first add is saved as named: $table", kept in table)
        checkWorksInAnEditor(rows, NAME, SOURCE, homes.elixir.a, typed, home.getValue(picked), picked)
    }

    /** An install picked in the field: the new SDK is selected, and after Apply one Erlang SDK has the install's home. */
    private fun Driver.aDetectedInstallIsSavedOnce(homes: Homes) {
        val install = Homes.stageErlangInstall()
        openPyCharmProject("settings_add_install")
        val elixir = inSettings(SdkPage.ELIXIR) {
            val added = addFromDisk(INSTALL, homes.elixir.b)
            val selected = selectedRows()
            rows.check(INSTALL, "it is selected: $selected", selected == listOf(added))
            clickSdkFieldRow(::isErlangRow)
            Thread.sleep(SETTLE_MS)
            val elixir = nameField().text
            clickApply()
            appears("an SDK at the install's home", 10.seconds) { driver.sdksAt(install).isNotEmpty() }
            val saved = driver.sdksAt(install)
            rows.check(INSTALL, "after Apply one SDK has the install's home: $saved", saved.size == 1)
            clickButtonText("OK")
            elixir
        }
        val erlang = savedAt(INSTALL, install)
        checkPaired(rows, INSTALL, elixir, erlang)
        checkWorksInAnEditor(rows, INSTALL, SOURCE, homes.elixir.b, elixir, install, erlang)
    }

    /** `Elixir NoData`'s OTP box toggled, Apply refused, then Cancel: in the next Settings, renaming [other] applies. */
    private fun Driver.cancelDiscardsARefusedEdit(other: String) {
        inSettings(SdkPage.ELIXIR) {
            selectRow(NO_DATA)
            x("//div[@class='JBCheckBox' and @accessiblename='Suppress OTP version mismatch warning']").click()
            clickApply()
            checkRefused(DISCARDS)
            clickButtonText("Cancel")
        }
        val renamed = "$other renamed"
        inSettings(SdkPage.ELIXIR) {
            checkRenameApplies(DISCARDS, other, renamed)
            clickButtonText("Cancel")
        }
        val table = tableNames()
        rows.check(DISCARDS, "'$renamed' is saved: $table", renamed in table)
    }

    /** `Elixir NoData` viewed asks for an Erlang, and another SDK's rename applies; renamed itself, Apply is refused. */
    private fun Driver.redWhenViewedRefusedOnceEdited(other: String) {
        // DISCARDS, before this row in the same launch, renames [other] when it holds.
        val current = tableNames().single { it.startsWith(other) }
        inSettings(SdkPage.ELIXIR) {
            selectRow(NO_DATA)
            checkRed(rows, RED, NO_DATA)
            checkRenameApplies(RED, current, "$current again")
            selectRow(NO_DATA)
            typeName("$NO_DATA renamed")
            clickApply()
            checkRefused(RED)
            clickButtonText("Cancel")
        }
        val paired = stored(NO_DATA).pairedErlang
        rows.check(RED, "'$NO_DATA' is saved as it was: $paired", paired == null)
    }

    /**
     * In its own Settings, an Add at [elixirHome], then [gesture], given the table's names from before, makes an Erlang in
     * the field; then Cancel: the table is unchanged and no page lists what it lacks.
     */
    private fun Driver.cancelled(case: String, elixirHome: String, gesture: SettingsDialogUiComponent.(table: List<String>) -> Unit) {
        val table = tableNames()
        inSettings(SdkPage.ELIXIR) {
            addFromDisk(CANCEL, elixirHome)
            gesture(table)
            Thread.sleep(SETTLE_MS)
            clickButtonText("Cancel")
        }
        Thread.sleep(SETTLE_MS)
        val after = tableNames()
        rows.check(CANCEL, "$case: the table is unchanged: $after", after == table)
        inSettings(SdkPage.ELIXIR) {
            val elixir = rowNames()
            openSdkPage(SdkPage.ERLANG)
            val erlang = rowNames()
            rows.check(CANCEL, "$case: reopened, the pages list nothing the table lacks: $elixir, $erlang", (elixir + erlang).all { it in table })
            clickButtonText("Cancel")
        }
    }

    /** The page's Add at [home], through the folder chooser alone; the row it added. An Erlang step that opens fails [row]. */
    private fun SettingsDialogUiComponent.addFromDisk(row: String, home: String): String =
        addRow(SdkPage.ELIXIR, home, 30.seconds) { checkNoErlangStep(rows, row) }.first

    /** [sdk] renamed [name], and Apply saves it with no refusal. */
    private fun SettingsDialogUiComponent.checkRenameApplies(row: String, sdk: String, name: String) {
        selectRow(sdk)
        typeName(name)
        clickApply()
        val saved = appears("the rename", 10.seconds) { name in driver.tableNames() }
        val refusal = refusal()
        rows.check(row, "a rename of '$sdk' applies: saved=$saved, refusal=$refusal", saved && refusal == null)
    }

    /** The Name field's text replaced by typing [text]. */
    private fun UiComponent.typeName(text: String) {
        val name = nameField()
        name.click()
        name.keyboard {
            hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_A)
            typeText(text)
        }
        waitFor("the typed name", 10.seconds) { name.text == text }
    }

    /** What the page shows while it asks: the platform's field, nothing picked, the text, the outline and its popup. */
    private fun UiComponent.checkAsks(row: String) {
        rows.check(row, "the page has the platform's SDK field", appears("the field", 10.seconds) { x(SDK_FIELD).present() })
        checkAsking(rows, row)
        rows.check(row, "the page shows no save hint", !showsSaveHint())
    }

    /** Once an Erlang is picked: none of the asking, and no popup. */
    private fun UiComponent.checkAnswered(row: String) {
        Thread.sleep(SETTLE_MS)
        checkNeverAsked(rows, row)
        checkNoPopup(rows, row)
    }

    /** Settings' banner names the refusal. */
    private fun UiComponent.checkRefused(row: String) {
        val refused = appears("the refusal", 10.seconds) { refusal() != null }
        rows.check(row, "Apply is refused in Settings' banner: ${refusal()}", refused)
    }

    /** Settings' banner line naming an SDK invalid for want of an Erlang, or null. */
    private fun UiComponent.refusal(): String? =
        getAllTexts().asString("\n").lines().firstOrNull { "is invalid" in it && ERLANG_NOT_CHOSEN in it }

    private fun UiComponent.showsSaveHint(): Boolean = getAllTexts().asString("\n").contains("After changing the Erlang SDK")

    /** [stagePyCharmProject]'s project [name], opened in this window. */
    private fun Driver.openPyCharmProject(name: String) = openInThisWindow(stagePyCharmProject(name))

    /** The one Erlang SDK saved at [home]; [row] fails when there isn't one. */
    private fun Driver.savedAt(row: String, home: String): String {
        val saved = sdksAt(home)
        rows.check(row, "one SDK is saved at $home: $saved", saved.size == 1)
        return saved.single()
    }

    /** [body] as [row], its failure recorded and what it left open closed; the IDE reports no error during it. */
    private fun Driver.row(row: String, body: Driver.() -> Unit) {
        val errors = errorsDuring(row) {
            runCatching { body() }.onFailure { failure ->
                runCatching { popupList().keyboard { key(KeyEvent.VK_ESCAPE) } }
                failedErlangStepRow(rows, row, failure) { cancelSettings() }
            }
        }
        rows.check(row, "no IDE error: $errors", errors.isEmpty())
    }

    /** The field's row picked for the Detected install staged at [home]. */
    private fun UiComponent.pickDetected(home: String) = clickSdkFieldRow { "installs/erlang/${Path.of(home).fileName}" in it }

    private companion object {
        const val ASKS = "from a project, an add asks no Erlang dialog and is refused until an Erlang is chosen"
        const val PRESELECTED = "a matching registered Erlang is preselected and names the SDK"
        const val NAME = "the Name follows a later pick, a second SDK on the install gets a number, a typed Name stays"
        const val CANCEL = "Cancel leaves neither SDK"
        const val REPLACED = "an Erlang made in the field and then replaced stays after OK"
        const val RED = "a saved Elixir SDK with no Erlang is red when viewed and refused only once edited"
        const val DISCARDS = "Cancel discards a refused edit"
        const val INSTALL = "an install picked in the field is saved once"

        /** The project PyCharm opens its welcome on. */
        const val WELCOME = "Welcome"

        const val SOURCE = "lib/settings_add.ex"
        val SOURCE_TEXT = resolvableSource("SettingsAdd")

        /** A one-module project PyCharm sets up, its module with an Elixir facet naming no SDK; its folder. */
        fun stagePyCharmProject(name: String): Path =
            stageProject(name, moduleIml("PYTHON_MODULE", "<configuration />"), mapOf("mix.exs" to mixExs(name), SOURCE to SOURCE_TEXT))
    }
}
