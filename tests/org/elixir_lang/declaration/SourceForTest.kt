package org.elixir_lang.declaration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.SystemProperties
import org.elixir_lang.expander.ExpanderSource
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource
import org.elixir_lang.settings.ElixirExperimentalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class SourceForTest {
    @Test
    fun testThePropertyPutsTheExpanderBehindEveryFeature() {
        val saved = SystemProperties.setProperty(SOURCE_PROPERTY, null)
        // An earlier test in this JVM may have left an application running, whose setting would move highlighting.
        val state = ApplicationManager.getApplication()?.let { ElixirExperimentalSettings.instance.state }
        val savedSetting = state?.enableExpander == true

        try {
            state?.enableExpander = false
            assertEquals(everyFeature(LegacyWalkSource), Feature.entries.associateWith(::sourceFor))

            System.setProperty(SOURCE_PROPERTY, EXPANDER)
            assertEquals(everyFeature(ExpanderSource), Feature.entries.associateWith(::sourceFor))
        } finally {
            SystemProperties.setProperty(SOURCE_PROPERTY, saved)
            state?.enableExpander = savedSetting
        }
    }

    @Test
    fun testTheFlagIsOffWithNoApplication() {
        assumeTrue("an application is running", ApplicationManager.getApplication() == null)

        assertFalse(expanderFlagOn())
    }

    private fun everyFeature(source: CandidateSource): Map<Feature, CandidateSource> =
        Feature.entries.associateWith { source }
}
