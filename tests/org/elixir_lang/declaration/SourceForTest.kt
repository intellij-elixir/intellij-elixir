package org.elixir_lang.declaration

import org.elixir_lang.expander.ExpanderSource
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceForTest {
    @Test
    fun testThePropertyPutsTheExpanderBehindEveryFeature() {
        val saved = System.getProperty(SOURCE_PROPERTY)

        try {
            System.clearProperty(SOURCE_PROPERTY)
            assertEquals(everyFeature(LegacyWalkSource), Feature.entries.associateWith(::sourceFor))

            System.setProperty(SOURCE_PROPERTY, EXPANDER)
            assertEquals(everyFeature(ExpanderSource), Feature.entries.associateWith(::sourceFor))
        } finally {
            if (saved == null) System.clearProperty(SOURCE_PROPERTY) else System.setProperty(SOURCE_PROPERTY, saved)
        }
    }

    private fun everyFeature(source: CandidateSource): Map<Feature, CandidateSource> =
        Feature.entries.associateWith { source }
}
