package org.elixir_lang.declaration

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import org.elixir_lang.settings.ElixirExperimentalSettings

/** Turns the Experimental setting that opens the expander's path on until [disposable] is disposed, which restores it. */
internal fun enableExpander(disposable: Disposable) {
    val state = ElixirExperimentalSettings.instance.state
    val original = state.enableExpander
    state.enableExpander = true
    Disposer.register(disposable) { state.enableExpander = original }
}
