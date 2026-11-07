package org.elixir_lang.tool_manager

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironment
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver
import org.elixir_lang.jps.shared.tool_manager.ToolManagerSettingsState
import org.elixir_lang.sdk.choice.TOOL_MANAGER_PROBE
import org.elixir_lang.sdk.choice.replaceToolManagerEnvironments
import java.nio.file.Files
import java.nio.file.Path

/**
 * The environment the plugin's processes get from the tool managers: the project's settings, and the opt-ins a wizard's
 * chooser ticked but has not adopted yet, since a wizard starts `mix` before it adopts them.
 */
class ToolManagerProcessEnvironmentTest : PlatformTestCase() {
    private lateinit var saved: ToolManagerSettingsState
    private lateinit var workDir: Path
    private val askedAbout = mutableListOf<Path>()

    override fun setUp() {
        super.setUp()
        saved = settings().state.copy()
        workDir = Files.createTempDirectory("tool-manager-work-dir").toRealPath()
        replaceToolManagerEnvironments(
            testRootDisposable,
            mapOf(MANAGER to ToolManagerEnvironmentResolver { dir, _ ->
                askedAbout.add(dir)
                ToolManagerEnvironment.Variables(TOOL_MANAGER_PROBE)
            }),
        )
    }

    override fun tearDown() {
        try {
            settings().loadState(saved)
            workDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testAPendingOptInAppliesItsManagersEnvironmentBeforeAnyProjectHasIt() {
        assertEquals(TOOL_MANAGER_PROBE, variables(toolManagerEnvironment(null, ToolManagerOptIns(MANAGER, environment = true))))
    }

    fun testAPendingOptInWithoutItsEnvironmentAppliesNone() {
        assertEquals(emptyMap<String, String>(), variables(toolManagerEnvironment(null, ToolManagerOptIns(MANAGER, environment = false))))
    }

    fun testWithNothingPendingTheProjectsOwnSettingsApply() {
        settings().state.enabledManagers.add(MANAGER)
        settings().state.environmentManagers.add(MANAGER)

        assertEquals(TOOL_MANAGER_PROBE, variables(toolManagerEnvironment(project)))
    }

    fun testAPendingOptInAppliesOnTopOfTheProjectsSettings() {
        assertEquals(TOOL_MANAGER_PROBE, variables(toolManagerEnvironment(project, ToolManagerOptIns(MANAGER, environment = true))))
    }

    fun testAPendingOptInIsNotSaved() {
        variables(toolManagerEnvironment(project, ToolManagerOptIns(MANAGER, environment = true)))

        assertEquals(saved.enabledManagers, settings().state.enabledManagers)
        assertEquals(saved.environmentManagers, settings().state.environmentManagers)
    }

    fun testTheToolManagerIsAskedAboutTheFolderGivenInsteadOfTheWorkingDirectory() {
        val folder = Files.createTempDirectory(workDir, "location")

        variables(toolManagerEnvironment(null, ToolManagerOptIns(MANAGER, environment = true), folder))

        assertEquals(listOf(folder), askedAbout)
    }

    fun testWithNoFolderGivenTheToolManagerIsAskedAboutTheWorkingDirectory() {
        variables(toolManagerEnvironment(null, ToolManagerOptIns(MANAGER, environment = true)))

        assertEquals(listOf(workDir), askedAbout)
    }

    private fun settings(): ToolManagerSettings = ToolManagerSettings.getInstance(project)

    private fun variables(environment: ToolManagerEnvironmentResolver?): Map<String, String> =
        toolManagerVariables(environment, workDir, emptyMap())
}

private const val MANAGER = "fake"
