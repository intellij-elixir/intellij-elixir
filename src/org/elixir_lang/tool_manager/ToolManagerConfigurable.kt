package org.elixir_lang.tool_manager

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Project-level settings page for Tool Managers (under Settings -> Elixir -> Tool Managers).
 *
 * Registered in the base `plugin.xml`, so available in every IDE including small IDEs
 * (RubyMine, etc.), letting those users opt in to a tool manager. SDK configuration is
 * small-IDE-safe (see [ToolManagerSdkChecker.configureSdks]).
 *
 * Renders one checkbox per registered tool manager so users can opt in to each experimental
 * manager individually.  The list is driven by [allBuiltInToolManagers] so new managers
 * registered there automatically appear here without any additional wiring.
 */
internal class ToolManagerConfigurable(private val project: Project) : Configurable {

    private var panel: DialogPanel? = null
    private val settings: ToolManagerSettings get() = ToolManagerSettings.getInstance(project)

    override fun getDisplayName(): String = "Tool Managers"

    override fun createComponent(): JComponent {
        panel = panel {
            group("Experimental Tool Manager Integrations") {
                row {
                    comment("Saved for you in this project, not shared with others who open it.")
                }
                for (manager in allBuiltInToolManagers) {
                    lateinit var enabled: Cell<JBCheckBox>
                    row {
                        enabled = checkBox(manager.displayName)
                            .comment(
                                "When enabled, ${manager.displayName} is queried for Elixir/Erlang " +
                                    "version information and the status-bar widget will compare it " +
                                    "against the configured SDK."
                            )
                            .bindSelected(
                                getter = { settings.isEnabled(manager) },
                                setter = { settings.setEnabled(manager, it) }
                            )
                    }
                    if (providesEnvironment(manager)) {
                        indent {
                            row {
                                checkBox("Apply ${manager.displayName} environment")
                                    .comment(
                                        "Adds the environment variables ${manager.displayName} sets for the " +
                                            "project directory to run configurations, builds, the formatter, " +
                                            "Credo, Dialyzer, Mix tasks and dependency checks."
                                    )
                                    .enabledIf(enabled.selected)
                                    .bindSelected(
                                        getter = { settings.isEnvironmentEnabled(manager) },
                                        setter = { settings.setEnvironmentEnabled(manager, it) }
                                    )
                            }
                        }
                    }
                }
            }
        }
        return panel!!
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
        // Notify listeners (e.g. ToolManagerSdkCheckerStartupActivity) so the status-bar widget
        // visibility is refreshed and any pending SDK configuration scan is triggered.
        project.messageBus.syncPublisher(ToolManagerSettings.SETTINGS_CHANGED_TOPIC)
            .toolManagerSettingsChanged()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
