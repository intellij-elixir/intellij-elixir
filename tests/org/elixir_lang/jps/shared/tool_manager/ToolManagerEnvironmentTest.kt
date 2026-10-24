package org.elixir_lang.jps.shared.tool_manager

import junit.framework.TestCase
import org.elixir_lang.jps.shared.sdk.SdkPaths
import java.nio.file.Path

class ToolManagerEnvironmentTest : TestCase() {
    private val workDir = Path.of("/home/u/project")
    private val sdkEnvironment = SdkPaths.mixHomeEnvironment("/home/u/.local/share/mise/installs/elixir/1.18.4")

    fun testVariablesSetByTheLaunchWinOverTheToolManagersWhichWinOverInheritedOnes() {
        val environment = mutableMapOf("PATH" to "/usr/bin", "HOME" to "/home/u", "MIX_ENV" to "test")

        putToolManagerEnvironment(
            environment,
            variables = mapOf("PATH" to "/mise/bin:/usr/bin", "MIX_ENV" to "dev", "RUSTUP_TOOLCHAIN" to "1.94.1"),
            explicit = mapOf("MIX_ENV" to "test"),
        )

        assertEquals(
            mapOf(
                "PATH" to "/mise/bin:/usr/bin",
                "HOME" to "/home/u",
                "MIX_ENV" to "test",
                "RUSTUP_TOOLCHAIN" to "1.94.1",
            ),
            environment
        )
    }

    fun testWhereTheOsIgnoresCaseTheToolManagersPathReplacesTheInheritedOne() {
        val environment = mutableMapOf("Path" to "C:\\Windows", "MIX_ENV" to "test")

        putToolManagerEnvironment(
            environment,
            variables = mapOf("PATH" to "C:\\mise\\bin;C:\\Windows", "mix_env" to "dev"),
            explicit = mapOf("MIX_ENV" to "test"),
            ignoreCase = true,
        )

        assertEquals(mapOf("PATH" to "C:\\mise\\bin;C:\\Windows", "MIX_ENV" to "test"), environment)
    }

    fun testWhereTheOsIgnoresCaseTheManagersLastSpellingOfAMixHomeWins() {
        val environment = mutableMapOf<String, String>()

        putToolManagerEnvironment(
            environment,
            variables = linkedMapOf(
                "MIX_ARCHIVES" to "C:\\mise\\elixir\\.mix\\archives",
                "MIX_HOME" to "C:\\mise\\elixir\\.mix",
                "Mix_Archives" to "C:\\custom\\.mix\\archives",
                "mix_home" to "C:\\custom\\.mix",
            ),
            explicit = emptyMap(),
            sdkEnvironment = sdkEnvironment,
            ignoreCase = true,
        )

        assertEquals(mapOf("MIX_HOME" to "C:\\custom\\.mix", "MIX_ARCHIVES" to "C:\\custom\\.mix\\archives"), environment)
    }

    fun testWhereTheOsIgnoresCaseTheSdkSeesAManagersMixHomeInAnyCase() {
        val environment = mutableMapOf<String, String>()

        putToolManagerEnvironment(
            environment,
            variables = mapOf("mix_home" to "C:\\custom\\.mix"),
            explicit = emptyMap(),
            sdkEnvironment = sdkEnvironment,
            ignoreCase = true,
        )

        assertEquals(mapOf("MIX_HOME" to "C:\\custom\\.mix", "MIX_ARCHIVES" to "C:\\custom\\.mix\\archives"), environment)
    }

    fun testWhereTheOsIgnoresCaseTheSdkSeesALaunchsMixHomeInAnyCase() {
        val environment = mutableMapOf("mix_home" to "C:\\custom\\.mix")

        putToolManagerEnvironment(
            environment,
            variables = mapOf("MIX_HOME" to "C:\\mise\\elixir\\.mix", "MIX_ARCHIVES" to "C:\\mise\\elixir\\.mix\\archives"),
            explicit = mapOf("mix_home" to "C:\\custom\\.mix"),
            sdkEnvironment = sdkEnvironment,
            ignoreCase = true,
        )

        assertEquals(mapOf("MIX_HOME" to "C:\\custom\\.mix", "MIX_ARCHIVES" to "C:\\custom\\.mix\\archives"), environment)
    }

    fun testWhereTheOsKeepsCaseALowerCaseMixHomeIsASeparateVariable() {
        val environment = mutableMapOf<String, String>()

        putToolManagerEnvironment(
            environment,
            variables = mapOf("MIX_HOME" to "/mise/elixir/.mix", "mix_home" to "/custom/.mix"),
            explicit = emptyMap(),
            sdkEnvironment = sdkEnvironment,
            ignoreCase = false,
        )

        assertEquals(
            mapOf(
                "MIX_HOME" to "/mise/elixir/.mix",
                "MIX_ARCHIVES" to "/mise/elixir/.mix/archives",
                "mix_home" to "/custom/.mix",
            ),
            environment
        )
    }

    fun testAnEnabledManagerWithoutItsEnvironmentSwitchedOnIsNotRun() {
        val settings = settings(enabled = setOf("a"), environment = emptySet())

        assertNull(resolveToolManagerEnvironment(settings, mapOf("a" to failIfRun), workDir, emptyMap()))
    }

    fun testADisabledManagerIsNotRunEvenWithItsEnvironmentSwitchedOn() {
        val settings = settings(enabled = emptySet(), environment = setOf("a"))

        assertNull(resolveToolManagerEnvironment(settings, mapOf("a" to failIfRun), workDir, emptyMap()))
    }

    fun testAnEnabledManagerWithItsEnvironmentSwitchedOnSuppliesTheEnvironment() {
        val settings = settings(enabled = setOf("a"), environment = setOf("a"))
        var ranWith: Pair<Path, Map<String, String>>? = null
        val resolver = ToolManagerEnvironmentResolver { dir, launchVariables ->
            ranWith = dir to launchVariables
            ToolManagerEnvironment.Variables(mapOf("A" to "1"))
        }

        assertEquals(
            ToolManagerEnvironment.Variables(mapOf("A" to "1")),
            resolveToolManagerEnvironment(settings, mapOf("a" to resolver), workDir, mapOf("MISE_ENV" to "test"))
        )
        assertEquals(workDir to mapOf("MISE_ENV" to "test"), ranWith)
    }

    fun testTheFirstManagerThatAppliesInOrderSuppliesTheEnvironment() {
        val settings = settings(enabled = setOf("a", "b", "c"), environment = setOf("a", "b", "c"))
        val resolvers = linkedMapOf(
            "a" to ToolManagerEnvironmentResolver { _, _ -> null },
            "b" to ToolManagerEnvironmentResolver { _, _ -> ToolManagerEnvironment.Variables(mapOf("B" to "1")) },
            "c" to failIfRun,
        )

        assertEquals(
            ToolManagerEnvironment.Variables(mapOf("B" to "1")),
            resolveToolManagerEnvironment(settings, resolvers, workDir, emptyMap())
        )
    }

    private val failIfRun = ToolManagerEnvironmentResolver { _, _ -> fail("resolver should not run"); null }

    private fun settings(enabled: Set<String>, environment: Set<String>) =
        ToolManagerSettingsState().apply {
            enabledManagers.addAll(enabled)
            environmentManagers.addAll(environment)
        }
}
