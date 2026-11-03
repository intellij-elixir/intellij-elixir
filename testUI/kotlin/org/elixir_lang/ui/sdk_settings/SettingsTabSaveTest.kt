package org.elixir_lang.ui.sdk_settings

import org.elixir_lang.ui.framework.core.NoScreen
import org.elixir_lang.ui.framework.core.clickButtonText
import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories

/**
 * PyCharm with "Show settings window as an editor tab" on: an Elixir Model's Erlang SDK changed on the SDKs tab and
 * saved with Save Changes, which runs holding no lock where the modal dialog's Apply holds write-intent.
 */
class SettingsTabSaveTest {
    @Test
    fun `saving from a Settings tab keeps the new pairing`() {
        val rows = Rows()
        val row = "Settings tab"
        launchPyCharm("SettingsTabSaveTest") { homes ->
            closeInternalErrors("$row-start")
            runCatching {
                val erlangA = addErlangOnPage(homes.erlang.a)
                val elixir = addElixirOnPage(homes.elixir.a, erlangA)
                val erlangB = addErlangOnPage(homes.erlang.b)
                rows.check(row, "'$elixir' starts paired with '$erlangA'", stored(elixir).pairedErlang == erlangA)

                // A Settings tab opens in a project's editor area.
                val project = NoScreen.privateHome.resolve("projects/plain").createDirectories()
                openInThisWindow(project)
                val frame = frameOf("plain")
                settingsAsTab(frame)
                val tab = checkNotNull(settingsTab(frame, SdkPage.ELIXIR)) { "the Settings tree shows inside the project frame" }
                tab.pairWith(elixir, erlangB)
                val saved = errorsDuring("$row-saved") {
                    tab.clickButtonText(SAVE_CHANGES)
                    Thread.sleep(1_000)
                }
                rows.check(row, "the IDE reported no error on Save Changes: $saved", saved.isEmpty())
                val paired = stored(elixir).pairedErlang
                rows.check(row, "the stored pairing '$paired' names '$erlangB'", paired == erlangB)
            }.onFailure { failure ->
                rows.fail(row, failure)
                closeInternalErrors("$row-failed")
            }
        }
        rows.assertAllHeld()
    }

    private companion object {
        /** A Settings tab has "Revert All" and "Save Changes", and no Apply. */
        const val SAVE_CHANGES = "Save Changes"
    }
}
