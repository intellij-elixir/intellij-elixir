package org.elixir_lang.ui.sdk_settings

import com.intellij.driver.client.Driver
import org.elixir_lang.ui.framework.core.ERLANG_STEP_TITLE
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.sdksAt
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * On PyCharm's Settings > Elixir pages, in modal Settings: adding an Elixir SDK asks for its Erlang in the Erlang step,
 * and deleting the Erlang SDK one is paired with asks for another once Settings closes.
 */
class SettingsErlangStepTest {
    @Test
    fun `the Erlang step asks on add and after a delete`() {
        val rows = Rows()
        launchPyCharm("SettingsErlangStepTest") { homes ->
            val install = Homes.stageErlangInstall()
            closeInternalErrors("setup")
            val erlangA = addErlangOnPage(homes.erlang.a)
            val erlangB = addErlangOnPage(homes.erlang.b)
            addWithTwoErlangSdks(rows, homes, erlangA, erlangB)
            addPickingAnInstall(rows, homes, install)
            deleteWithASecondAtItsHome(rows, homes, erlangA)
        }
        rows.assertAllHeld()
    }

    /** #4087 steps 2-4: both Erlang SDKs listed, nothing selected, Create disabled; the second picked is stored. */
    private fun Driver.addWithTwoErlangSdks(rows: Rows, homes: Homes, erlangA: String, erlangB: String) {
        val row = "add with two Erlang SDKs"
        runCatching {
            val added = addElixirOnPage(homes.elixir.a, erlangB)
            checkErlangStepAdd(rows, row, added, listed = listOf(erlangA, erlangB), picked = erlangB)
        }.onFailure { failure -> failedErlangStepRow(rows, row, failure, Driver::cancelSettings) }
    }

    /** An install picked: the new Elixir SDK is listed and selected, and after Apply its Erlang SDK is in the table once. */
    private fun Driver.addPickingAnInstall(rows: Rows, homes: Homes, install: String) {
        val row = "add picking an install"
        runCatching {
            inSettings(SdkPage.ELIXIR) {
                val added = addElixirRow(homes.elixir.b, install).name
                rows.check(row, "it is selected: ${selectedRows()}", selectedRows() == listOf(added))
                clickApply()
                appears("an SDK at the install's home", 10.seconds) { driver.sdksAt(install).isNotEmpty() }
                val atInstall = driver.sdksAt(install)
                rows.check(row, "after Apply one SDK has the install's home: $atInstall", atInstall.size == 1)
                clickButtonText("OK")
            }
        }.onFailure { failure -> failedErlangStepRow(rows, row, failure, Driver::cancelSettings) }
    }

    /**
     * An Elixir SDK paired with an Erlang SDK that shares [erlangA]'s home: deleting it and applying asks nothing while
     * Settings is open; once it closes, the ask lists [erlangA], where the lookup by home would pair it without a word,
     * and the pick is stored.
     */
    private fun Driver.deleteWithASecondAtItsHome(rows: Rows, homes: Homes, erlangA: String) {
        val row = "delete with a second Erlang SDK at its home"
        runCatching {
            val doomed = addErlangOnPage(homes.erlang.a)
            val elixir = addElixirOnPage(homes.elixir.b, doomed).name
            val askTitle = "$ERLANG_STEP_TITLE '$elixir'"
            inSettings(SdkPage.ERLANG) {
                removeRow(doomed)
                applyRemovalAskingNothing(rows, row, doomed, homes.erlang.a, askTitle)
            }
            checkDeletionAsk(rows, row, elixir, askTitle, pick = erlangA)
        }.onFailure { failure -> failedErlangStepRow(rows, row, failure, Driver::cancelSettings) }
    }
}
