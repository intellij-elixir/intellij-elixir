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
    HEEX_COMPONENTS;

    /** Whether the feature's rule takes a definition that may not be Elixir's; one that states no rule takes none. */
    val takesUncertain: Boolean
        get() = when (this) {
            // A place to go, a colour, a documentation entry or a parameter hint is a suggestion, so one is worth more than none.
            SYMBOL_REFERENCES,
            HIGHLIGHTING,
            DOCUMENTATION,
            PARAMETER_INFO -> true
            REFERENCES_INSPECTION,
            COMPLETION,
            FIND_USAGES_AND_RENAME,
            STRUCTURE_VIEW,
            HEEX_COMPONENTS -> false
        }

    /** Whether the feature reads a definition where it is written, so a `.beam`'s is read at its clauses ([CandidateSource.located]). */
    val readsLocation: Boolean
        get() = when (this) {
            SYMBOL_REFERENCES,
            DOCUMENTATION -> true
            PARAMETER_INFO,
            HIGHLIGHTING,
            REFERENCES_INSPECTION,
            COMPLETION,
            FIND_USAGES_AND_RENAME,
            STRUCTURE_VIEW,
            HEEX_COMPONENTS -> false
        }
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
        expanderFor(feature)
    } else {
        when (feature) {
            Feature.SYMBOL_REFERENCES,
            Feature.HIGHLIGHTING,
            Feature.DOCUMENTATION,
            Feature.PARAMETER_INFO,
            Feature.REFERENCES_INSPECTION -> if (expanderFlagOn()) expanderFor(feature) else LegacyWalkSource
            Feature.COMPLETION,
            Feature.FIND_USAGES_AND_RENAME,
            Feature.STRUCTURE_VIEW,
            Feature.HEEX_COMPONENTS -> LegacyWalkSource
        }
    }

/**
 * The expander's value that answers [feature]: navigation reads another module, and a `.beam`'s mirror, only where they
 * are held, so a gesture on the EDT runs no expansion of them; every other feature reads the computing value.
 */
private fun expanderFor(feature: Feature): CandidateSource =
    if (feature == Feature.SYMBOL_REFERENCES) ExpanderSource.Navigation else ExpanderSource
