package org.elixir_lang.declaration

import junit.framework.TestCase
import junit.framework.TestSuite
import org.elixir_lang.settings.ElixirExperimentalSettings

/**
 * Each of [ExpanderFlagTest]'s tests leaves Run the expander at its default, so with it on by default the tests after it
 * in the same JVM still run with it on.
 */
class ExpanderFlagTestRestoresTheExpanderSettingTest : TestCase() {
    fun testEachTestLeavesTheExpanderSettingAtItsDefault() {
        val tests = TestSuite(ExpanderFlagTest::class.java).tests().toList().map { it as ExpanderFlagTest }
        val default = ElixirExperimentalSettings.State().enableExpander
        val left = tests.associate { test ->
            test.runBare()

            test.name to ElixirExperimentalSettings.instance.state.enableExpander
        }

        assertEquals(tests.associate { it.name to default }, left)
    }
}
