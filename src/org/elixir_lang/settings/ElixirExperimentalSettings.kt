package org.elixir_lang.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.messages.Topic

interface ElixirExperimentalSettingsListener {
    fun settingsChanged(oldState: ElixirExperimentalSettings.State, newState: ElixirExperimentalSettings.State)
}

private val LOG = logger<ElixirExperimentalSettings>()

@State(
    name = "org.elixir_lang.settings.ElixirExperimentalSettings",
    storages = [Storage("elixir.experimental.xml")]
)
@Service
class ElixirExperimentalSettings : PersistentStateComponent<ElixirExperimentalSettings.State> {
    data class State(
        var enableHtmlInjection: Boolean = false,
//        var enableDeleteSdkSmallIDE: Boolean = false,
        var enableStatusBarWidget : Boolean = false,
        var enableLiteralSigilInjection: Boolean = false,
        var enableMixDepsCheck: Boolean = true,
        var enableExpander: Boolean = false
    )

    private var elixirSettingsState = State()

    override fun getState(): State = elixirSettingsState

    private var initialized = false

    override fun loadState(state: State) {
        LOG.debug("Loading ElixirExperimentalSettings state: $state")
        val old = elixirSettingsState
        elixirSettingsState = state

        // The platform loads the stored state before `initializeComponent`, when nothing listens yet. A later load is a
        // settings import or a file reload, which the Settings page's `apply` does not publish.
        if (initialized && old != state) {
            ApplicationManager.getApplication().messageBus.syncPublisher(SETTINGS_CHANGED_TOPIC).settingsChanged(old, state.copy())
        }
    }

    override fun initializeComponent() {
        initialized = true
    }

    companion object {
        @JvmField
        val SETTINGS_CHANGED_TOPIC: Topic<ElixirExperimentalSettingsListener> = Topic.create(
            "ElixirExperimentalSettings.settingsChanged",
            ElixirExperimentalSettingsListener::class.java
        )
        
        val instance: ElixirExperimentalSettings
            get() = ApplicationManager
                .getApplication()
                .getService(ElixirExperimentalSettings::class.java)
    }
}


