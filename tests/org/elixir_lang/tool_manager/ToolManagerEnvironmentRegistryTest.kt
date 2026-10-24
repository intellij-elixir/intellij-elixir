package org.elixir_lang.tool_manager

import junit.framework.TestCase
import org.elixir_lang.jps.shared.tool_manager.builtInToolManagerEnvironments
import org.elixir_lang.tool_manager.mise.MiseToolManager
import java.io.IOException

class ToolManagerEnvironmentRegistryTest : TestCase() {
    fun testEveryManagerThatAppliesAnEnvironmentIsABuiltInToolManager() {
        val names = allBuiltInToolManagers.map { it.name }
        val environments = builtInToolManagerEnvironments { _, _, _ -> throw IOException() }.keys

        assertTrue("$environments are not all in $names", names.containsAll(environments))
    }

    fun testMiseOffersItsEnvironment() {
        assertTrue(providesEnvironment(MiseToolManager()))
    }
}
