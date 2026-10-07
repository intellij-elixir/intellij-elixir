package org.elixir_lang.declaration

import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource

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

/** The source that answers [feature]. */
fun sourceFor(feature: Feature): CandidateSource =
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
