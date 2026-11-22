package org.elixir_lang.ui.sdk_settings

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.WindowUiComponent
import com.intellij.driver.sdk.ui.components.elements.tree
import com.intellij.driver.sdk.ui.components.idea.ProjectStructureUI
import org.elixir_lang.ui.framework.core.ERLANG_STEP_TITLE
import org.elixir_lang.ui.framework.core.addElixirSdkFromDisk
import org.elixir_lang.ui.framework.core.addErlangSdkFromDisk
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.ensureWelcomeScreenInForeground
import org.elixir_lang.ui.framework.core.inProjectStructureSdks
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * On IntelliJ IDEA's Project Structure > SDKs, from the welcome screen with no project open: renaming the Erlang SDK an
 * Elixir SDK is paired with updates the pairing, and deleting it asks for another in the Erlang step once the dialog
 * closes, even with a second Erlang SDK at its home.
 */
class ProjectStructureErlangStepTest {
    @Test
    fun `the pairing follows a rename and the Erlang step asks after a delete`() {
        val rows = Rows()
        launchIdea("ProjectStructureErlangStepTest") { homes ->
            ensureWelcomeScreenInForeground()
            closeInternalErrors("setup")
            welcomeScreen {
                val erlangA = addErlangSdkFromDisk(homes.erlang.a)
                val erlangB = addErlangSdkFromDisk(homes.erlang.b)
                renameThePairedErlang(rows, homes, erlangB)
                deleteWithASecondAtItsHome(rows, homes, erlangA)
            }
        }
        rows.assertAllHeld()
    }

    /**
     * An Elixir SDK paired with [erlangB], renamed from the welcome screen with no project open: the pairing follows the
     * name at Apply, and a second Apply in the same dialog (for a rename of the Elixir SDK) keeps it.
     */
    private fun WindowUiComponent.renameThePairedErlang(rows: Rows, homes: Homes, erlangB: String) {
        val row = "rename the paired Erlang SDK"
        runCatching {
            val elixir = addElixirSdkFromDisk(homes.elixir.b, erlangB)
            val renamed = "$erlangB renamed"
            val elixirRenamed = "$elixir renamed"
            inProjectStructureSdks {
                rename(erlangB, renamed)
                clickApply()
                appears("the rename applied", 10.seconds) { driver.stored(elixir).pairedErlang == renamed }
                val applied = driver.stored(elixir).pairedErlang
                rows.check(row, "after Apply the stored pairing '$applied' is the new name '$renamed'", applied == renamed)
                rename(elixir, elixirRenamed)
                clickApply()
                appears("the Elixir SDK's rename applied", 10.seconds) { runCatching { driver.stored(elixirRenamed) }.isSuccess }
                clickButtonText("OK")
            }
            val paired = driver.stored(elixirRenamed).pairedErlang
            rows.check(row, "after a second Apply the stored pairing '$paired' is the new name '$renamed'", paired == renamed)
        }.onFailure { failure -> driver.failed(rows, row, failure) }
    }

    /** The SDK named [name] selected on the SDKs page and its name field set to [newName]. */
    private fun ProjectStructureUI.rename(name: String, newName: String) {
        tree().clickRow { it == name }
        nameField().text = newName
    }

    /**
     * An Elixir SDK paired with [erlangA], and a second Erlang SDK at its home: deleting [erlangA] asks once the dialog
     * closes, where the lookup by home would pair the second without a word, and the pick is stored.
     */
    private fun WindowUiComponent.deleteWithASecondAtItsHome(rows: Rows, homes: Homes, erlangA: String) {
        val row = "delete with a second Erlang SDK at its home"
        runCatching {
            val elixir = addElixirSdkFromDisk(homes.elixir.a, erlangA)
            val second = addErlangSdkFromDisk(homes.erlang.a)
            val askTitle = "$ERLANG_STEP_TITLE '$elixir'"
            inProjectStructureSdks {
                tree().clickRow { it == erlangA }
                x(LIST_REMOVE).click()
                applyRemovalAskingNothing(rows, row, erlangA, homes.erlang.a, askTitle)
            }
            driver.checkDeletionAsk(rows, row, elixir, askTitle, pick = second)
        }.onFailure { failure -> driver.failed(rows, row, failure) }
    }

    private fun Driver.failed(rows: Rows, row: String, failure: Throwable) =
        failedErlangStepRow(rows, row, failure) { closeProjectStructure() }
}
