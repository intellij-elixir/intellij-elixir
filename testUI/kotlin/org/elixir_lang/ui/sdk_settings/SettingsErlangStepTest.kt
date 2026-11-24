package org.elixir_lang.ui.sdk_settings

import com.intellij.driver.client.Driver
import org.elixir_lang.ui.framework.core.ERLANG_STEP_TITLE
import org.junit.jupiter.api.Test

/**
 * On PyCharm's Settings > Elixir pages, in modal Settings: deleting the Erlang SDK an Elixir SDK is paired with asks
 * for another once Settings closes.
 */
class SettingsErlangStepTest {
    @Test
    fun `the Erlang step asks after a delete`() {
        val rows = Rows()
        launchPyCharm("SettingsErlangStepTest") { homes ->
            closeInternalErrors("setup")
            val erlangA = addErlangOnPage(homes.erlang.a)
            addErlangOnPage(homes.erlang.b)
            deleteWithASecondAtItsHome(rows, homes, erlangA)
        }
        rows.assertAllHeld()
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
            val elixir = addElixirOnPage(homes.elixir.b, doomed)
            val askTitle = "$ERLANG_STEP_TITLE '$elixir'"
            inSettings(SdkPage.ERLANG) {
                removeRow(doomed)
                applyRemovalAskingNothing(rows, row, doomed, homes.erlang.a, askTitle)
            }
            checkDeletionAsk(rows, row, elixir, askTitle, pick = erlangA)
        }.onFailure { failure -> failedErlangStepRow(rows, row, failure, Driver::cancelSettings) }
    }
}
