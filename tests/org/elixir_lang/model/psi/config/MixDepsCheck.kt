package org.elixir_lang.model.psi.config

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import org.elixir_lang.settings.ElixirExperimentalSettings

/** Turns the background `mix deps` check off until [disposable] is disposed, so it never runs Mix on a fixture's `mix.exs`. */
internal fun disableMixDepsCheck(disposable: Disposable) {
    val state = ElixirExperimentalSettings.instance.state
    val original = state.enableMixDepsCheck
    state.enableMixDepsCheck = false
    Disposer.register(disposable) { state.enableMixDepsCheck = original }
}
