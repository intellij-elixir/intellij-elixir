package org.elixir_lang.declaration

import com.intellij.openapi.application.ApplicationManager
import org.elixir_lang.expander.ExpanderSource
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource
import org.elixir_lang.settings.ElixirExperimentalSettings

/** A feature that asks a [CandidateSource] for candidates. */
enum class Feature {
    SYMBOL_REFERENCES,
    DOCUMENTATION,
    PARAMETER_INFO,
    HIGHLIGHTING,
    REFERENCES_INSPECTION,
    COMPLETION,
    FIND_USAGES_AND_RENAME,
    STRUCTURE_VIEW,
    HEEX_COMPONENTS
}

/** Set to [EXPANDER] only by the test harness, to put [ExpanderSource] behind every feature. */
internal const val SOURCE_PROPERTY = "org.elixir_lang.declaration.source"

internal const val EXPANDER = "expander"

/** Whether the test harness has put [ExpanderSource] behind every feature. */
internal fun expanderSwitchOn(): Boolean = System.getProperty(SOURCE_PROPERTY) == EXPANDER

/** Whether the Experimental setting that lets the plugin run the expander is on; the one reader of it. Off with no application. */
internal fun expanderFlagOn(): Boolean =
    ApplicationManager.getApplication()?.let { ElixirExperimentalSettings.instance.state.enableExpander } ?: false

/** Whether the expander may run: the harness's switch or the Experimental setting. */
internal fun expanderPathOn(): Boolean = expanderSwitchOn() || expanderFlagOn()

/** The source that answers [feature]. */
fun sourceFor(feature: Feature): CandidateSource =
    if (expanderSwitchOn()) {
        ExpanderSource
    } else {
        when (feature) {
            Feature.SYMBOL_REFERENCES,
            Feature.DOCUMENTATION,
            Feature.PARAMETER_INFO,
            Feature.HIGHLIGHTING,
            Feature.REFERENCES_INSPECTION,
            Feature.COMPLETION,
            Feature.FIND_USAGES_AND_RENAME,
            Feature.STRUCTURE_VIEW,
            Feature.HEEX_COMPONENTS -> LegacyWalkSource
        }
    }
