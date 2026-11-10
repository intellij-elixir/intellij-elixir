package org.elixir_lang.declaration

import com.intellij.openapi.util.Disposer
import com.intellij.util.SystemProperties
import org.elixir_lang.expander.ExpanderSource
import org.elixir_lang.junit.LightTestCase
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource
import org.elixir_lang.settings.ElixirExperimentalSettings
import org.elixir_lang.settings.RUN_EXPANDER_PROPERTY

class ExpanderFlagTest : LightTestCase() {
    private var savedProperty: String? = null

    override fun setUp() {
        super.setUp()
        savedProperty = SystemProperties.setProperty(SOURCE_PROPERTY, null)
        disableExpander(testRootDisposable)
    }

    override fun tearDown() {
        try {
            SystemProperties.setProperty(SOURCE_PROPERTY, savedProperty)
        } finally {
            super.tearDown()
        }
    }

    fun testTheSettingIsOffByDefault() {
        assertFalse(defaultWithRunExpander(null))
    }

    fun testTheHarnessPropertyTurnsTheDefaultOn() {
        assertTrue(defaultWithRunExpander("true"))
    }

    fun testTheSettingOnMovesHighlightingOnly() {
        assertFalse(expanderFlagOn())
        assertEquals(Feature.entries.associateWith { LegacyWalkSource }, Feature.entries.associateWith(::sourceFor))

        enableExpander(testRootDisposable)

        assertTrue(expanderFlagOn())
        assertEquals(
            Feature.entries.associateWith { if (it == Feature.HIGHLIGHTING) ExpanderSource else LegacyWalkSource },
            Feature.entries.associateWith(::sourceFor),
        )
    }

    fun testThePropertyIsTheWholeTableWithTheSettingOff() {
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

    private fun defaultWithRunExpander(value: String?): Boolean {
        val saved = SystemProperties.setProperty(RUN_EXPANDER_PROPERTY, value)
        return try {
            ElixirExperimentalSettings.State().enableExpander
        } finally {
            SystemProperties.setProperty(RUN_EXPANDER_PROPERTY, saved)
        }
    }
}
