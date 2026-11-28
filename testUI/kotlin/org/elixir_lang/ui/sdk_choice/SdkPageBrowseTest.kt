package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.tree
import com.intellij.driver.sdk.ui.components.idea.ProjectStructureUI
import com.intellij.driver.sdk.ui.ui
import org.elixir_lang.ui.framework.core.addElixirSdkFromDisk
import org.elixir_lang.ui.framework.core.addErlangSdkFromDisk
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.ensureWelcomeScreenInForeground
import org.elixir_lang.ui.framework.core.inProjectStructureSdks
import org.elixir_lang.ui.framework.core.projectStructure
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.browseHomeFromDisk
import org.elixir_lang.ui.sdk_settings.clickApply
import org.elixir_lang.ui.sdk_settings.closeInternalErrors
import org.elixir_lang.ui.sdk_settings.errorsDuring
import org.elixir_lang.ui.sdk_settings.failedErlangStepRow
import org.elixir_lang.ui.sdk_settings.launchIdea
import org.elixir_lang.ui.sdk_settings.stored
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * On IntelliJ IDEA's Project Structure > SDKs, an Elixir SDK's home browsed to another Elixir install and applied: no
 * dialog asks, the home changes, and the SDK stays paired with its Erlang SDK.
 */
class SdkPageBrowseTest {
    @Test
    fun `browsing an Elixir SDK's home keeps its pairing and asks nothing`() {
        val rows = Rows()
        val row = "browse"
        launchIdea("SdkPageBrowseTest") { homes ->
            ensureWelcomeScreenInForeground()
            closeInternalErrors("setup")
            welcomeScreen {
                runCatching {
                    val erlang = addErlangSdkFromDisk(homes.erlang.a)
                    val elixir = addElixirSdkFromDisk(homes.elixir.a, erlang)
                    inProjectStructureSdks {
                        val ours = driver.errorsDuring("browsed") {
                            browseHome(elixir, homes.elixir.b)
                            rows.check(row, "no dialog but Project Structure is open", !otherDialogShown())
                            clickApply()
                            appears("the home applied", 10.seconds) { driver.stored(elixir).isAt(homes.elixir.b) }
                            rows.check(row, "no dialog but Project Structure is open after Apply", !otherDialogShown())
                        }
                        rows.check(row, "the IDE reported no error through the plugin: $ours", ours.isEmpty())
                        clickButtonText("OK")
                    }
                    val stored = driver.stored(elixir)
                    rows.check(row, "home '${stored.home}' is B '${homes.elixir.b}'", stored.isAt(homes.elixir.b))
                    rows.check(row, "the pairing '${stored.pairedErlang}' is still '$erlang'", stored.pairedErlang == erlang)
                }.onFailure { failure ->
                    driver.failedErlangStepRow(rows, row, failure) {
                        val projectStructure = ui.projectStructure()
                        if (projectStructure.present()) {
                            // An Apply relabels Cancel as Close.
                            val close = listOf("Cancel", "Close").firstOrNull { projectStructure.x { byVisibleText(it) }.present() }
                            if (close != null) projectStructure.clickButtonText(close)
                        }
                    }
                }
            }
        }
        rows.assertAllHeld()
    }

    /** [sdk]'s row selected, then [browseHomeFromDisk] to [home]. */
    private fun ProjectStructureUI.browseHome(sdk: String, home: String) {
        tree().clickRow { it == sdk }
        browseHomeFromDisk(home)
        Thread.sleep(1_000)
    }

    private fun ProjectStructureUI.otherDialogShown(): Boolean =
        driver.ui.x("//div[@class='MyDialog' and not(contains(@title, 'Project Structure'))]").present()
}
