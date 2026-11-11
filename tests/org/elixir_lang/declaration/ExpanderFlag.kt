package org.elixir_lang.declaration

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import org.elixir_lang.settings.ElixirExperimentalSettings

/** Turns the Experimental setting that opens the expander's path on until [disposable] is disposed, which restores it. */
internal fun enableExpander(disposable: Disposable) {
    setExpander(true, disposable)
}

/** Turns the Experimental setting off until [disposable] is disposed, for a test that pins what the plugin does with it off. */
internal fun disableExpander(disposable: Disposable) {
    setExpander(false, disposable)
}

private fun setExpander(on: Boolean, disposable: Disposable) {
    val state = ElixirExperimentalSettings.instance.state
    val original = state.enableExpander
    state.enableExpander = on
    Disposer.register(disposable) { state.enableExpander = original }
}
