package org.elixir_lang.ui.sdk_choice

import com.intellij.driver.client.Driver
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.tree
import com.intellij.driver.sdk.ui.components.idea.ProjectStructureUI
import com.intellij.driver.sdk.ui.ui
import org.elixir_lang.ui.framework.core.addElixirSdkFromDisk
import org.elixir_lang.ui.framework.core.addErlangSdkFromDisk
import org.elixir_lang.ui.framework.core.choosePath
import org.elixir_lang.ui.framework.core.clickButtonText
import org.elixir_lang.ui.framework.core.ensureWelcomeScreenInForeground
import org.elixir_lang.ui.framework.core.inProjectStructureSdks
import org.elixir_lang.ui.framework.core.projectStructure
import org.elixir_lang.ui.framework.core.selectItemFromList
import org.elixir_lang.ui.sdk_settings.MessagePoolRef
import org.elixir_lang.ui.sdk_settings.Rows
import org.elixir_lang.ui.sdk_settings.SdkPage
import org.elixir_lang.ui.sdk_settings.appears
import org.elixir_lang.ui.sdk_settings.clickApply
import org.elixir_lang.ui.sdk_settings.closeInternalErrors
import org.elixir_lang.ui.sdk_settings.failedErlangStepRow
import org.elixir_lang.ui.sdk_settings.launchIdea
import org.elixir_lang.ui.sdk_settings.stored
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.JTextField
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
                    val elixir = addElixirSdkFromDisk(homes.elixir.a, erlang).name
                    inProjectStructureSdks {
                        val before = driver.errorTexts().size
                        browseHome(elixir, homes.elixir.b)
                        rows.check(row, "no dialog but Project Structure is open", !otherDialogShown())
                        clickApply()
                        appears("the home applied", 10.seconds) { driver.stored(elixir).home == homes.elixir.b }
                        rows.check(row, "no dialog but Project Structure is open after Apply", !otherDialogShown())
                        // IntelliJ IDEA's own SdkEditor.doSetHomePath commits its clone outside a write action, for
                        // every SDK type; only an error through the plugin's code is this row's.
                        val ours = driver.errorTexts().drop(before).filter { "org.elixir_lang." in it }
                        driver.closeInternalErrors("browsed")
                        rows.check(row, "the IDE reported no error through the plugin: $ours", ours.isEmpty())
                        clickButtonText("OK")
                    }
                    val stored = driver.stored(elixir)
                    rows.check(row, "home '${stored.home}' is B '${homes.elixir.b}'", stored.home?.let(Path::of) == Path.of(homes.elixir.b))
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

    /**
     * [sdk]'s home field's browse, which isn't editable, fired with Shift+Enter, the key `ComponentWithBrowseButton` binds.
     * It opens the SDK popup, whose add action opens the home chooser.
     */
    private fun ProjectStructureUI.browseHome(sdk: String, home: String) {
        tree().clickRow { it == sdk }
        val field = x("//div[@class='TextFieldWithBrowseButton']").x { byType(JTextField::class.java) }
        field.click()
        field.keyboard { hotKey(KeyEvent.VK_SHIFT, KeyEvent.VK_ENTER) }
        driver.selectItemFromList("from disk")
        driver.choosePath(SdkPage.ELIXIR.chooserTitle, home)
        Thread.sleep(1_000)
    }

    /** Each error the IDE has reported, with its whole stack. */
    private fun Driver.errorTexts(): List<String> =
        utility(MessagePoolRef::class).getInstance().getFatalErrors(true, true).map { it.getThrowableText() }

    private fun ProjectStructureUI.otherDialogShown(): Boolean =
        driver.ui.x("//div[@class='MyDialog' and not(contains(@title, 'Project Structure'))]").present()
}
