package org.elixir_lang.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

private val LOG = logger<ElixirExperimentalSettingsConfigurable>()

/** The Experimental Settings page under Elixir, in every IDE. */
internal class ElixirExperimentalSettingsConfigurable : Configurable, Configurable.NoScroll, Configurable.Beta {
    private var settingsPanel: DialogPanel? = null
    private val settings = ElixirExperimentalSettings.instance

    override fun getDisplayName(): String = "Experimental Settings"

    override fun createComponent(): JComponent {
        settingsPanel = panel {
            group("HEEx Injection in ~H Sigils Code Blocks") {
                row {
                    checkBox("Enable ~H Sigil HEEx language injection")
                        .comment("Provides HEEx (HTML + Elixir) syntax highlighting and code completion within <a href='https://hexdocs.pm/phoenix_live_view/1.0.3/Phoenix.Component.html#sigil_H/2'>~H Sigils code blocks</a>, when working with Phoenix Live View HEEx templates, otherwise you'll see this as a string.<br/><br/><a href='https://github.com/intellij-elixir/intellij-elixir#h-sigil-heex-language-injection-support'>Documentation for ~H Sigil HEEx Language Injection Support.</a>")
                        .bindSelected(
                            getter = { settings.state.enableHtmlInjection },
                            setter = { settings.state.enableHtmlInjection = it }
                        )
                }
            }
            group("Literal Sigil Injection") {
                row {
                    checkBox("Enable literal sigil injection (~S, ~W, etc.)")
                        .comment("Enables literal sigil injection, which allows the Elixir sigil ~S to work. WARNING: THIS IS CURRENTLY BUGGY AND CAUSING ISSUES WITH ~s")
                        .bindSelected(
                            getter = { settings.state.enableLiteralSigilInjection },
                            setter = { settings.state.enableLiteralSigilInjection = it }
                        )
                }
            }
            group("Status Bar Widget") {
                row {
                    checkBox("Enable Status Bar Widget showing if the Elixir SDK is correctly configured")
                        .comment("Adds a widget to the status bar that indicates whether the Elixir SDK is properly configured for the current project.")
                        .bindSelected(
                            getter = { settings.state.enableStatusBarWidget },
                            setter = { settings.state.enableStatusBarWidget = it }
                        )
                }
            }
            group("Mix Deps Checker") {
                row {
                    checkBox("Enable automatic Mix deps checking")
                        .comment("Checks dependency status on project open and when mix.exs/mix.lock changes. Requires an Elixir SDK to be configured.")
                        .bindSelected(
                            getter = { settings.state.enableMixDepsCheck },
                            setter = { settings.state.enableMixDepsCheck = it }
                        )
                }
            }
            group("Expander") {
                row {
                    checkBox("Run the expander")
                        .comment("Lets the plugin expand Elixir code as Elixir does. It answers config keys today. With the box off, keys written as modules, reads through an alias or a nested import, and settings in a subfolder that import_config reaches are not answered, and the unread config key inspection can warn on a key read that way.")
                        .bindSelected(
                            getter = { settings.state.enableExpander },
                            setter = { settings.state.enableExpander = it }
                        )
                }
            }
        }
        return settingsPanel!!
    }

    override fun isModified(): Boolean {
        return settingsPanel?.isModified() ?: false
    }

    override fun apply() {
        if (!isModified) {
            LOG.debug("No modifications detected, skipping apply")
            return
        }

        val oldState = settings.state.copy()
        LOG.debug("ElixirExperimentalSettingsConfigurable.apply() - oldState: $oldState")

        settingsPanel?.apply()

        val newState = settings.state.copy()
        LOG.debug("ElixirExperimentalSettingsConfigurable.apply() - newState: $newState")

        val messageBus = ApplicationManager.getApplication().messageBus
        messageBus.syncPublisher(ElixirExperimentalSettings.SETTINGS_CHANGED_TOPIC).settingsChanged(oldState, newState)
    }

    override fun reset() {
        settingsPanel?.reset()
    }

    override fun disposeUIResources() {
        settingsPanel = null
    }
}
