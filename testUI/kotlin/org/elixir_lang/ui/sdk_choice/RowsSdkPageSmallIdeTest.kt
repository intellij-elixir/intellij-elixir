package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.elements.DialogUiComponent
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import org.elixir_lang.ui.framework.core.chooser
import org.elixir_lang.ui.framework.core.choosePath
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.isSdk
import org.elixir_lang.ui.framework.core.pickInField
import org.elixir_lang.ui.framework.core.waitUntilReady
import org.elixir_lang.ui.sdk_choice.RowsTable.BROKEN
import org.elixir_lang.ui.sdk_choice.RowsTable.ERLANG
import org.elixir_lang.ui.sdk_choice.RowsTable.INSTALL
import org.elixir_lang.ui.sdk_choice.RowsTable.OLD
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.SdkPage
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.checkNoErlangStep
import org.elixir_lang.ui.sdk_settings.checkPaired
import org.elixir_lang.ui.sdk_settings.launchPyCharm
import org.elixir_lang.ui.sdk_settings.nameField
import org.elixir_lang.ui.sdk_settings.tableNames
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * On PyCharm a row's from-disk pick opens Settings' Elixir SDKs page alone over the chooser, at the new SDK, with no
 * Erlang dialog. The page's Cancel makes no SDK and leaves the row as it was; its OK, with the preselected Erlang SDK
 * untouched, saves the SDK paired, and the row holds it. A broken row's Edit SDK... opens the page at its SDK, whose OK
 * pairs it.
 */
class RowsSdkPageSmallIdeTest {
    private val rows = Rows()

    @Test
    fun `a row's from-disk pick and a broken row's Edit SDK open the Elixir SDKs page, whose OK saves the SDK`() {
        val modules = facetHubModules()
        launchPyCharm("RowsSdkPageSmallIdeTest", configure = { RowsTable.seed(it) }) { _ ->
            val install = stageElixirInstall(INSTALL)
            val frame = openStaged(stageModules(HUB, modules))

            row(rows, CANCELLED) {
                rows.check(CANCELLED, "Choose Elixir SDK... shows the chooser", openChooser(frame))
                val names = tableNames()
                chooser().apply {
                    waitUntilReady()
                    clickInRow("alpha", ::isFromDisk)
                }
                choosePath(SdkPage.ELIXIR.chooserTitle, install)
                checkNoErlangStep(rows, CANCELLED)
                rows.check(CANCELLED, "the Elixir SDKs page opens", pageOpens())
                page().clickButtonText("Cancel")
                rows.check(CANCELLED, "the page closes", pageCloses())
                checkNoSdkMade(rows, CANCELLED, install, names)
                val alpha = chooser().rowText("alpha")
                rows.check(CANCELLED, "alpha's row is as before: '$alpha'", isSdk(OLD)(alpha))
                checkChooserShows(rows, CANCELLED)
            }

            row(rows, SAVED) {
                chooser().clickInRow("alpha", ::isFromDisk)
                choosePath(SdkPage.ELIXIR.chooserTitle, install)
                checkNoErlangStep(rows, SAVED)
                rows.check(SAVED, "the Elixir SDKs page opens again", pageOpens())
                page().apply {
                    checkPreselects(rows, SAVED, ERLANG)
                    clickButtonText("OK")
                }
                rows.check(SAVED, "the page closes", pageCloses())
                val name = checkOneSdkMade(rows, SAVED, install, ERLANG)
                chooser().apply {
                    rows.check(SAVED, "alpha's row holds '$name': '${rowText("alpha")}'", appears("the row", 10.seconds) { isSdk(name)(rowText("alpha")) })
                }
                checkChooserShows(rows, SAVED)
                chooser().clickButtonText("Cancel")
                rows.check(SAVED, "Cancel closes the chooser", chooserCloses())
            }

            row(rows, EDIT) {
                rows.check(EDIT, "Choose Elixir SDK... shows the chooser", openChooser(frame))
                chooser().apply {
                    waitUntilReady()
                    rows.check(EDIT, "beta shows no Edit SDK... before its pick", !editLink("beta").shows())
                    editPicked(rows, EDIT, "beta", BROKEN)
                }
                rows.check(EDIT, "Edit SDK... opens the Elixir SDKs page", pageOpens())
                page().apply {
                    rows.check(EDIT, "at '$BROKEN''s page: '${nameField().text}'", appears("the broken SDK's page", 10.seconds) { nameField().text == BROKEN })
                    pickInField(ERLANG)
                    clickButtonText("OK")
                }
                rows.check(EDIT, "the page closes", pageCloses())
                checkPaired(rows, EDIT, BROKEN, ERLANG)
                checkEditedAndCancel(rows, EDIT, "beta", BROKEN)
            }
            checkNoIdeErrors(rows)
        }
        rows.assertAllHeld()
    }

    /** Settings' Elixir SDKs page, shown alone in its own dialog. */
    private fun Driver.page(): DialogUiComponent = ui.dialog(xQuery { byTitle(PAGE_TITLE) })

    private fun Driver.pageOpens(): Boolean = appears("the page", 30.seconds) { page().present() }

    private fun Driver.pageCloses(): Boolean = closes(page(), 20.seconds)

    private companion object {
        const val PAGE_TITLE = "SDKs"
        const val CANCELLED = "the page's Cancel"
        const val SAVED = "the page's OK"
        const val EDIT = "Edit SDK... on a broken row"
    }
}
