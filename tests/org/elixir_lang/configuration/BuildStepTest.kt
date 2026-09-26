package org.elixir_lang.configuration

import com.intellij.compiler.options.CompileStepBeforeRun
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import org.elixir_lang.PlatformTestCase

class BuildStepTest : PlatformTestCase() {
    /** Whether each type's new configurations start with the Build step. */
    private val startsWithBuildStep = mapOf(
        org.elixir_lang.distillery.configuration.Type::class.java to false,
        org.elixir_lang.elixir.configuration.Type::class.java to true,
        org.elixir_lang.espec.configuration.Type::class.java to false,
        org.elixir_lang.exunit.configuration.Type::class.java to false,
        org.elixir_lang.iex.configuration.Type::class.java to true,
        org.elixir_lang.iex.mix.configuration.Type::class.java to false,
        org.elixir_lang.mix.configuration.Type::class.java to false,
    )

    fun testNewConfigurationsStartWithTheBuildStepOnlyWhenNotRunByMix() {
        val types = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
            .filter { it.javaClass.name.startsWith("org.elixir_lang.") }
        val runManager = RunManager.getInstance(project)

        val actual = types.flatMap { type ->
            type.configurationFactories.map { factory ->
                val buildStep = runManager.getConfigurationTemplate(factory).configuration.beforeRunTasks
                    .any { it.providerId == CompileStepBeforeRun.ID && it.isEnabled }

                "${type.displayName} (${factory.id})" to buildStep
            }
        }.sortedBy { it.first }
        val expected = types.flatMap { type ->
            val buildStep = startsWithBuildStep[type.javaClass]

            type.configurationFactories.map { factory -> "${type.displayName} (${factory.id})" to buildStep }
        }.sortedBy { it.first }

        assertEquals(expected, actual)
        assertEquals("every Elixir configuration type is classified", startsWithBuildStep.keys, types.map { it.javaClass }.toSet())
    }
}
