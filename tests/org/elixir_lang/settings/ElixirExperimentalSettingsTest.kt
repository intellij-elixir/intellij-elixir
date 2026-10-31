package org.elixir_lang.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import org.elixir_lang.junit.LightTestCase

class ElixirExperimentalSettingsTest : LightTestCase() {
    private val published = mutableListOf<Pair<ElixirExperimentalSettings.State, ElixirExperimentalSettings.State>>()
    private var expanderBefore = false

    override fun setUp() {
        super.setUp()
        expanderBefore = ElixirExperimentalSettings.instance.state.enableExpander
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(
            ElixirExperimentalSettings.SETTINGS_CHANGED_TOPIC,
            object : ElixirExperimentalSettingsListener {
                override fun settingsChanged(
                    oldState: ElixirExperimentalSettings.State,
                    newState: ElixirExperimentalSettings.State,
                ) {
                    published += oldState to newState
                }
            },
        )
    }

    override fun tearDown() {
        try {
            ElixirExperimentalSettings.instance.state.enableExpander = expanderBefore
        } finally {
            super.tearDown()
        }
    }

    fun testTheFirstLoadPublishesNothing() {
        ElixirExperimentalSettings().loadState(ElixirExperimentalSettings.State(enableExpander = true))

        assertEmpty(published)
    }

    fun testALaterLoadPublishesAChange() {
        val settings = ElixirExperimentalSettings().apply { initializeComponent() }
        val loaded = ElixirExperimentalSettings.State(enableExpander = true)

        settings.loadState(loaded)

        assertEquals(listOf(ElixirExperimentalSettings.State() to loaded), published)
    }

    fun testALaterLoadOfTheSameStatePublishesNothing() {
        val settings = ElixirExperimentalSettings().apply { initializeComponent() }

        settings.loadState(ElixirExperimentalSettings.State())

        assertEmpty(published)
    }

    fun testTheCheckboxReachesTheSettingAndPublishes() {
        val settings = ElixirExperimentalSettings.instance
        settings.state.enableExpander = false
        val configurable = ElixirExperimentalSettingsConfigurable()

        try {
            val component = configurable.createComponent()
            configurable.reset()
            val checkBox = UIUtil.findComponentsOfType(component, JBCheckBox::class.java).single { it.text == "Run the expander" }

            assertFalse(checkBox.isSelected)

            checkBox.doClick()

            assertTrue(configurable.isModified)

            configurable.apply()

            assertTrue(settings.state.enableExpander)
            assertEquals(1, published.size)
            assertFalse(published.single().first.enableExpander)
            assertTrue(published.single().second.enableExpander)
        } finally {
            configurable.disposeUIResources()
        }
    }
}
