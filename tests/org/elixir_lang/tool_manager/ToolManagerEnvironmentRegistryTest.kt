package org.elixir_lang.tool_manager

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.jps.shared.tool_manager.builtInToolManagerEnvironments
import org.elixir_lang.tool_manager.mise.MiseToolManager
import java.io.IOException

class ToolManagerEnvironmentRegistryTest : BasePlatformTestCase() {
    fun testEveryManagerThatAppliesAnEnvironmentIsABuiltInToolManager() {
        val names = BuiltInToolManagers().all.map { it.name }
        val environments = builtInToolManagerEnvironments { _, _, _ -> throw IOException() }.keys

        assertTrue("$environments are not all in $names", names.containsAll(environments))
    }

    fun testMiseOffersItsEnvironment() {
        assertTrue(providesEnvironment(MiseToolManager()))
    }
}
