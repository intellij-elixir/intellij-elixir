package org.elixir_lang.declaration

import com.intellij.openapi.util.Disposer
import org.elixir_lang.expander.ExpanderSource
import org.elixir_lang.junit.LightTestCase
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource
import org.elixir_lang.settings.ElixirExperimentalSettings

class ExpanderFlagTest : LightTestCase() {
    private var savedProperty: String? = null
    private var savedSetting = false

    override fun setUp() {
        super.setUp()
        savedProperty = System.getProperty(SOURCE_PROPERTY)
        savedSetting = ElixirExperimentalSettings.instance.state.enableExpander
        System.clearProperty(SOURCE_PROPERTY)
    }

    override fun tearDown() {
        try {
            savedProperty?.let { System.setProperty(SOURCE_PROPERTY, it) } ?: System.clearProperty(SOURCE_PROPERTY)
            ElixirExperimentalSettings.instance.state.enableExpander = savedSetting
        } finally {
            super.tearDown()
        }
    }

    fun testTheSettingIsOffByDefault() {
        assertFalse(ElixirExperimentalSettings.State().enableExpander)
    }

    fun testTheSettingOnMovesNoFeature() {
        ElixirExperimentalSettings.instance.state.enableExpander = false
        assertFalse(expanderFlagOn())

        enableExpander(testRootDisposable)

        assertTrue(expanderFlagOn())
        assertEquals(Feature.entries.associateWith { LegacyWalkSource }, Feature.entries.associateWith(::sourceFor))
    }

    fun testThePropertyIsTheWholeTableWithTheSettingOff() {
        ElixirExperimentalSettings.instance.state.enableExpander = false
        System.setProperty(SOURCE_PROPERTY, EXPANDER)

        assertFalse(expanderFlagOn())
        assertEquals(Feature.entries.associateWith { ExpanderSource }, Feature.entries.associateWith(::sourceFor))
    }

    fun testEitherSwitchOpensThePath() {
        val state = ElixirExperimentalSettings.instance.state

        state.enableExpander = false
        assertFalse(expanderPathOn())

        state.enableExpander = true
        assertTrue(expanderPathOn())

        state.enableExpander = false
        System.setProperty(SOURCE_PROPERTY, EXPANDER)
        assertTrue(expanderPathOn())

        state.enableExpander = true
        assertTrue(expanderPathOn())
    }

    fun testTheHelperRestoresTheSetting() {
        val state = ElixirExperimentalSettings.instance.state

        for (original in listOf(false, true)) {
            state.enableExpander = original
            val disposable = Disposer.newDisposable()

            enableExpander(disposable)
            assertTrue(state.enableExpander)

            Disposer.dispose(disposable)
            assertEquals(original, state.enableExpander)
        }
    }
}
