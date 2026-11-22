package org.elixir_lang.declaration

import com.intellij.openapi.Disposable
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.Disposer
import org.elixir_lang.expander.ExpanderSource
import org.elixir_lang.expander.LegSdk
import org.elixir_lang.settings.ElixirExperimentalSettings

/** The features that read the expander with the setting on. */
internal val EXPANDER_FEATURES = setOf(
    Feature.SYMBOL_REFERENCES,
    Feature.HIGHLIGHTING,
    Feature.DOCUMENTATION,
    Feature.PARAMETER_INFO,
    Feature.REFERENCES_INSPECTION,
)

/** The value of the expander that answers [feature]: navigation's reads only what is held, the rest the computing value. */
internal fun expanderValueFor(feature: Feature): CandidateSource =
    if (feature == Feature.SYMBOL_REFERENCES) ExpanderSource.Navigation else ExpanderSource

/** Turns the Experimental setting that opens the expander's path on until [disposable] is disposed, which restores it. */
internal fun enableExpander(disposable: Disposable) {
    setExpander(true, disposable)
}

/** Runs the expander with the leg's SDK as [module]'s, both until [disposable] is disposed, so the expansion answers rather than the name index. */
internal fun enableExpander(module: Module, disposable: Disposable) {
    LegSdk.heldUntil(module, disposable)
    enableExpander(disposable)
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
