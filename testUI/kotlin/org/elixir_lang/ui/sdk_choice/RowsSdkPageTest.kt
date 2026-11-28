package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.ui.components.elements.DialogUiComponent
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import org.elixir_lang.jps.shared.ErlangSdkTypeId.ERLANG_SDK_TYPE_ID
import org.elixir_lang.ui.framework.core.SDK_FIELD
import org.elixir_lang.ui.framework.core.chooser
import org.elixir_lang.ui.framework.core.choosePath
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.clickDetectedRow
import org.elixir_lang.ui.framework.core.clickSdkFieldRow
import org.elixir_lang.ui.framework.core.fieldText
import org.elixir_lang.ui.framework.core.inProjectStructureSdks
import org.elixir_lang.ui.framework.core.isSdk
import org.elixir_lang.ui.framework.core.pickInField
import org.elixir_lang.ui.framework.core.sdksAt
import org.elixir_lang.ui.framework.core.waitUntilReady
import org.elixir_lang.ui.sdk_choice.RowsTable.BROKEN
import org.elixir_lang.ui.sdk_choice.RowsTable.ERLANG
import org.elixir_lang.ui.sdk_choice.RowsTable.GONE
import org.elixir_lang.ui.sdk_choice.RowsTable.INSTALL
import org.elixir_lang.ui.sdk_choice.RowsTable.OLD
import org.elixir_lang.ui.sdk_settings.CONFIGURE_SDK
import org.elixir_lang.ui.sdk_settings.ERLANG_NOT_CHOSEN
import org.elixir_lang.ui.sdk_settings.ERROR_OUTLINE
import org.elixir_lang.ui.sdk_settings.Homes
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.SdkPage
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.browseHomeFromDisk
import org.elixir_lang.ui.sdk_settings.checkNoErlangStep
import org.elixir_lang.ui.sdk_settings.checkOneSdkAt
import org.elixir_lang.ui.sdk_settings.checkPaired
import org.elixir_lang.ui.sdk_settings.dialogShown
import org.elixir_lang.ui.sdk_settings.homeField
import org.elixir_lang.ui.sdk_settings.isErlangRow
import org.elixir_lang.ui.sdk_settings.jdkXml
import org.elixir_lang.ui.sdk_settings.launchIdea
import org.elixir_lang.ui.sdk_settings.messageShown
import org.elixir_lang.ui.sdk_settings.nameField
import org.elixir_lang.ui.sdk_settings.otpVersionFile
import org.elixir_lang.ui.sdk_settings.outline
import org.elixir_lang.ui.sdk_settings.refusalBox
import org.elixir_lang.ui.sdk_settings.stored
import org.elixir_lang.ui.sdk_settings.tableNames
import org.elixir_lang.ui.sdk_settings.writeJdkTable
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

/**
 * On IntelliJ IDEA a row's Detected or from-disk pick opens the SDK dialog at the new SDK's page over the chooser, with no
 * Erlang dialog: the page preselects the matching Erlang SDK, or outlines its field and refuses OK, showing why and
 * staying open, until one is chosen. The dialog's Cancel takes the SDK out; its OK saves it, the row holds it and every
 * row lists it, and the chooser is as it was. A broken row's Edit SDK... opens its SDK's page, whose OK clears the row's
 * refusal. Project Structure's own Detected add is unchanged.
 */
class RowsSdkPageTest {
    private val rows = Rows()

    @Test
    fun `a Detected pick's page preselects its Erlang, its Cancel takes the SDK out and its OK saves it`() {
        launchIdea("RowsSdkPageTest-detected", configure = { RowsTable.seed(it) }) { _ ->
            val install = stageElixirInstall(INSTALL)
            val modules = hubModules()
            val frame = openStaged(stageModules(HUB, modules))
            fun sdkOf(module: StagedModule) = moduleSdk(module.name) to moduleInheritsSdk(module.name)
            val sdksBefore = modules.associateWith(::sdkOf)

            row(rows, CANCELLED) {
                rows.check(CANCELLED, "Choose Elixir SDK... shows the chooser", openChooser(frame))
                val owner = chooserOwner()
                val names = tableNames()
                val projectRow = chooser().run {
                    waitUntilReady()
                    pickInRow("beta", isSdk(OLD))
                    rowText(PROJECT_ROW)
                }
                rows.check(CANCELLED, "the Detected pick opens the SDK dialog", detectedInRow(PROJECT_ROW, INSTALL))
                checkNoErlangStep(rows, CANCELLED)
                sdkDialog().clickButtonText("Cancel")
                rows.check(CANCELLED, "the SDK dialog closes", sdkDialogCloses())
                checkNoSdkMade(rows, CANCELLED, install, names)
                chooser().apply {
                    val now = rowText(PROJECT_ROW)
                    rows.check(CANCELLED, "the Project SDK row is as before: '$now'", now == projectRow)
                    rows.check(CANCELLED, "beta keeps its unsaved '$OLD'", isSdk(OLD)(rowText("beta")))
                }
                checkChooserUnchanged(CANCELLED, owner)
            }

            row(rows, PAGE) {
                rows.check(PAGE, "the Detected pick opens the SDK dialog again", detectedInRow(PROJECT_ROW, INSTALL))
                checkNoErlangStep(rows, PAGE)
                sdkDialog().apply {
                    checkPreselects(rows, PAGE, ERLANG)
                    rows.check(PAGE, "the page doesn't ask for an Erlang SDK", !messageShown())
                    rows.check(PAGE, "the Name gains its Erlang: '${nameField().text}'", appears("the Name", 10.seconds) { "Erlang" in nameField().text })
                }
            }

            row(rows, SAVED) {
                val owner = chooserOwner()
                sdkDialog().clickButtonText("OK")
                rows.check(SAVED, "the SDK dialog closes", sdkDialogCloses())
                val name = checkOneSdkMade(rows, SAVED, install, ERLANG)
                chooser().apply {
                    rows.check(SAVED, "the Project SDK row holds '$name': '${rowText(PROJECT_ROW)}'", appears("the row", 10.seconds) { isSdk(name)(rowText(PROJECT_ROW)) })
                    rows.check(SAVED, "beta keeps its unsaved '$OLD'", isSdk(OLD)(rowText("beta")))
                    val alpha = rowItems("alpha")
                    rows.check(SAVED, "alpha's row lists '$name': $alpha", alpha.any(isSdk(name)))
                }
                checkChooserUnchanged(SAVED, owner)
            }

            row(rows, AFTER) {
                val made = sdksAt(install)
                chooser().clickButtonText("Cancel")
                rows.check(AFTER, "Cancel closes the chooser", chooserCloses())
                rows.check(AFTER, "the saved SDK stays: ${sdksAt(install)}", made.size == 1 && sdksAt(install) == made)
                rows.check(AFTER, "no project SDK: '${projectSdk()}'", projectSdk() == null)
                for (module in modules) rows.check(AFTER, "${module.name}'s SDK and inheritance are as they were", sdkOf(module) == sdksBefore.getValue(module))
            }
            checkNoIdeErrors(rows)
        }
        rows.assertAllHeld()
    }

    @Test
    fun `with no matching Erlang the page asks and OK is refused, staying open, until one is chosen`() {
        lateinit var other: String
        launchIdea("RowsSdkPageTest-unmatched", configure = {
            other = Homes.stageErlangOfAnotherMajor()
            writeJdkTable(it, listOf(jdkXml(OTHER_ERLANG, ERLANG_SDK_TYPE_ID, otpVersionFile(Path.of(other)).readText().trim(), other)))
        }) { _ ->
            val install = stageElixirInstall(INSTALL)
            val erlang = Homes.stageErlangInstall()
            val frame = openStaged(stageModules(HUB, hubModules()))

            row(rows, ASKS) {
                rows.check(ASKS, "Choose Elixir SDK... shows the chooser", openChooser(frame))
                chooser().waitUntilReady()
                rows.check(ASKS, "the Detected pick opens the SDK dialog", detectedInRow(PROJECT_ROW, INSTALL))
                checkNoErlangStep(rows, ASKS)
                sdkDialog().apply {
                    rows.check(ASKS, "the page says '$ERLANG_NOT_CHOSEN'", appears("the text", 20.seconds) { messageShown() })
                    rows.check(ASKS, "no Erlang SDK is chosen: '${fieldText()}'", OTHER_ERLANG !in fieldText())
                    rows.check(ASKS, "the field has the error outline", x(SDK_FIELD).outline() == ERROR_OUTLINE)
                }
            }

            row(rows, REFUSED) {
                sdkDialog().clickButtonText("OK")
                val box = refusalBox(10.seconds)
                rows.check(REFUSED, "OK is refused, saying why: '$box'", box?.contains(ERLANG_NOT_CHOSEN) == true)
                rows.check(REFUSED, "the SDK dialog stays open", dialogShown(CONFIGURE_SDK))
                val name = sdksAt(install).singleOrNull()
                rows.check(REFUSED, "the new SDK is still unpaired: ${name?.let { stored(it).pairedErlang }}", name != null && stored(name).pairedErlang == null)
            }

            row(rows, CHOSEN) {
                sdkDialog().apply {
                    clickSdkFieldRow(::isErlangRow)
                    rows.check(CHOSEN, "the field holds an Erlang SDK: '${fieldText()}'", appears("the pick", 20.seconds) { "rlang" in fieldText() })
                    clickButtonText("OK")
                }
                rows.check(CHOSEN, "the SDK dialog closes", sdkDialogCloses())
                val elixir = sdksAt(install).single()
                checkPaired(rows, CHOSEN, elixir, checkOneSdkAt(rows, CHOSEN, erlang))
                rows.check(CHOSEN, "the Project SDK row holds '$elixir'", isSdk(elixir)(chooser().rowText(PROJECT_ROW)))
                chooser().clickButtonText("Cancel")
                chooserCloses()
            }
            checkNoIdeErrors(rows)
        }
        rows.assertAllHeld()
    }

    @Test
    fun `a from-disk pick opens its page, Edit SDK opens a broken row's, and Project Structure is unchanged`() {
        launchIdea("RowsSdkPageTest-from-disk", configure = { RowsTable.seed(it) }) { _ ->
            val install = stageElixirInstall(INSTALL)
            val second = stageElixirInstall(SECOND)
            val frame = openStaged(stageModules(HUB, hubModules()))

            row(rows, FROM_DISK) {
                rows.check(FROM_DISK, "Choose Elixir SDK... shows the chooser", openChooser(frame))
                val owner = chooserOwner()
                chooser().apply {
                    waitUntilReady()
                    pickInRow(PROJECT_ROW, isSdk(OLD))
                    val alpha = rowText("alpha")
                    clickInRow("alpha", ::isFromDisk)
                    rows.check(FROM_DISK, "the folder chooser opens", appears("the folder chooser", 30.seconds) { driver.dialogShown(SdkPage.ELIXIR.chooserTitle) })
                    driver.cancelDialogs(SdkPage.ELIXIR.chooserTitle)
                    rows.check(FROM_DISK, "its Cancel leaves alpha's row as it was: '${rowText("alpha")}'", appears("the row", 10.seconds) { rowText("alpha") == alpha })
                    rows.check(FROM_DISK, "and opens no SDK dialog", !appears("an SDK dialog", 5.seconds) { driver.dialogShown(CONFIGURE_SDK) })
                    clickInRow("alpha", ::isFromDisk)
                }
                choosePath(SdkPage.ELIXIR.chooserTitle, install)
                checkNoErlangStep(rows, FROM_DISK)
                rows.check(FROM_DISK, "the SDK dialog opens", sdkDialogOpens())
                sdkDialog().apply {
                    checkPreselects(rows, FROM_DISK, ERLANG)
                    clickButtonText("OK")
                }
                rows.check(FROM_DISK, "the SDK dialog closes", sdkDialogCloses())
                val name = checkOneSdkMade(rows, FROM_DISK, install, ERLANG)
                chooser().apply {
                    rows.check(FROM_DISK, "alpha's row holds '$name': '${rowText("alpha")}'", appears("the row", 10.seconds) { isSdk(name)(rowText("alpha")) })
                    rows.check(FROM_DISK, "the Project SDK row keeps its unsaved '$OLD'", isSdk(OLD)(rowText(PROJECT_ROW)))
                }
                checkChooserUnchanged(FROM_DISK, owner)
            }

            row(rows, EDIT) {
                chooser().apply {
                    rows.check(EDIT, "beta shows no Edit SDK... before its pick", !editLink("beta").shows())
                    editPicked(rows, EDIT, "beta", BROKEN)
                }
                val pairing = stored(BROKEN).pairedErlang
                rows.check(EDIT, "Edit SDK... opens the SDK dialog", sdkDialogOpens())
                sdkDialog().clickButtonText("Cancel")
                rows.check(EDIT, "its Cancel closes the SDK dialog", sdkDialogCloses())
                rows.check(EDIT, "and leaves '$BROKEN' in the table: ${tableNames()}", BROKEN in tableNames())
                rows.check(EDIT, "with its pairing as it was: '${stored(BROKEN).pairedErlang}'", stored(BROKEN).pairedErlang == pairing)
                chooser().apply {
                    val beta = rowText("beta")
                    rows.check(EDIT, "beta holds '$BROKEN': '$beta'", isSdk(BROKEN)(beta))
                    rows.check(EDIT, "still outlined", rowOutline("beta") == ERROR_OUTLINE)
                    editLink("beta").click()
                }
                rows.check(EDIT, "Edit SDK... opens the SDK dialog again", sdkDialogOpens())
                sdkDialog().apply {
                    rows.check(EDIT, "at '$BROKEN''s page: '${nameField().text}'", appears("the broken SDK's page", 10.seconds) { nameField().text == BROKEN })
                    pickInField(ERLANG)
                    clickButtonText("OK")
                }
                rows.check(EDIT, "the SDK dialog closes", sdkDialogCloses())
                checkPaired(rows, EDIT, BROKEN, ERLANG)
                checkEditedAndCancel(rows, EDIT, "beta", BROKEN)
            }

            row(rows, PROJECT_STRUCTURE) {
                frame.inProjectStructureSdks {
                    clickDetectedRow { "elixir/$SECOND" in it }
                    checkPreselects(rows, PROJECT_STRUCTURE, ERLANG)
                    driver.checkNoErlangStep(rows, PROJECT_STRUCTURE)
                    clickButtonText("Cancel")
                }
                rows.check(PROJECT_STRUCTURE, "Cancel leaves no SDK on the second install: ${sdksAt(second)}", sdksAt(second).isEmpty())
            }
            checkNoIdeErrors(rows)
        }
        rows.assertAllHeld()
    }

    @Test
    fun `a row whose SDK's Elixir home is gone is re-homed through Edit SDK`() {
        launchIdea("RowsSdkPageTest-gone", configure = { RowsTable.seed(it, gone = true) }) { _ ->
            val install = stageElixirInstall(INSTALL)
            val frame = openStaged(stageModules(HUB, hubModules()))

            row(rows, GONE_HOME) {
                rows.check(GONE_HOME, "Choose Elixir SDK... shows the chooser", openChooser(frame))
                chooser().apply {
                    waitUntilReady()
                    editPicked(rows, GONE_HOME, "beta", GONE)
                }
                rows.check(GONE_HOME, "Edit SDK... opens the SDK dialog", sdkDialogOpens())
                sdkDialog().apply {
                    rows.check(GONE_HOME, "at '$GONE''s page: '${nameField().text}'", appears("the page", 10.seconds) { nameField().text == GONE })
                    browseHomeFromDisk(install)
                    rows.check(GONE_HOME, "the home field shows the install", appears("the home", 10.seconds) { homeField().text == install })
                    clickButtonText("OK")
                }
                rows.check(GONE_HOME, "the SDK dialog closes", sdkDialogCloses())
                val saved = stored(GONE)
                rows.check(GONE_HOME, "'$GONE' is saved at the install: '${saved.home}'", saved.isAt(install))
                checkPaired(rows, GONE_HOME, GONE, ERLANG)
                checkEditedAndCancel(rows, GONE_HOME, "beta", GONE)
            }
            checkNoIdeErrors(rows)
        }
        rows.assertAllHeld()
    }

    /** The SDK dialog, `ProjectJdksEditor`, by its title. */
    private fun Driver.sdkDialog(): DialogUiComponent = ui.dialog(xQuery { byTitle(CONFIGURE_SDK) })

    private fun Driver.sdkDialogOpens(): Boolean = appears("the SDK dialog", 30.seconds) { dialogShown(CONFIGURE_SDK) }

    private fun Driver.sdkDialogCloses(): Boolean = closes(sdkDialog(), 20.seconds)

    /** [row]'s Detected item for the install in the folder [folder] picked; whether the SDK dialog then shows. */
    private fun Driver.detectedInRow(row: String, folder: String): Boolean {
        chooser().clickInRow(row) { it.endsWith("elixir/$folder") || "elixir/$folder " in it }
        return sdkDialogOpens()
    }

    /** The name of the window that owns the chooser. */
    private fun Driver.chooserOwner(): String? = cast(chooser().component, WindowRef::class).getOwner()?.getName()

    /** The chooser, after a dialog over it closed, is showing and enabled, with the owner [owner] it had before. */
    private fun Driver.checkChooserUnchanged(row: String, owner: String?) {
        checkChooserShows(rows, row)
        val now = chooserOwner()
        rows.check(row, "its owner is unchanged: '$now'", owner != null && now == owner)
    }

    @Remote("java.awt.Window")
    private interface WindowRef {
        fun getOwner(): WindowRef?
        fun getName(): String?
    }

    private companion object {
        const val SECOND = "1.20.4-second"
        const val OTHER_ERLANG = "Erlang Other"
        const val CANCELLED = "the SDK dialog's Cancel takes the SDK out"
        const val PAGE = "a Detected pick's page"
        const val SAVED = "the SDK dialog's OK"
        const val AFTER = "the chooser's Cancel afterwards"
        const val ASKS = "with no matching Erlang the page asks"
        const val REFUSED = "OK is refused, staying open"
        const val CHOSEN = "OK once an Erlang is chosen"
        const val FROM_DISK = "a from-disk pick"
        const val EDIT = "Edit SDK... on a broken row"
        const val GONE_HOME = "Edit SDK... on a gone Elixir home"
        const val PROJECT_STRUCTURE = "Project Structure unchanged"
    }
}
