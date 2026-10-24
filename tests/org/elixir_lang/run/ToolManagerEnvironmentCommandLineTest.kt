package org.elixir_lang.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.system.OS
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironment
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver
import org.elixir_lang.tool_manager.ignoresVariableCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException as FutureExecutionException
import java.util.concurrent.TimeUnit

class ToolManagerEnvironmentCommandLineTest : PlatformTestCase() {
    private lateinit var workDir: Path

    override fun setUp() {
        super.setUp()
        workDir = Files.createTempDirectory("tool-manager-environment").toRealPath()
    }

    override fun tearDown() {
        try {
            workDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testAProcessGetsTheToolManagersVariablesUnderTheLaunchsOwn() {
        assertGetsVariablesUnderTheLaunchsOwn(WslAwareCommandLine())
    }

    fun testAPtyProcessGetsTheToolManagersVariablesUnderTheLaunchsOwn() {
        assertGetsVariablesUnderTheLaunchsOwn(WslAwarePtyCommandLine())
    }

    fun testAProcessStartedAgainGetsOnlyTheToolManagersCurrentVariables() {
        var variables: Map<String, String>? = mapOf("TOOL_MANAGER_PROBE" to "from-tool-manager")
        val commandLine = printEnvironment(WslAwareCommandLine()).apply {
            toolManagerEnvironment = ToolManagerEnvironmentResolver { _, _ ->
                variables?.let { ToolManagerEnvironment.Variables(it) }
            }
        }
        output(commandLine)
        variables = null

        val lines = output(commandLine).lines().map { it.trim() }

        assertFalse(lines.joinToString("\n"), "TOOL_MANAGER_PROBE=from-tool-manager" in lines)
    }

    fun testTheToolManagerIsAskedAboutTheWorkingDirectoryWithTheLaunchsVariables() {
        var askedAbout: Pair<Path, Map<String, String>>? = null
        val commandLine = printEnvironment(WslAwareCommandLine()).apply {
            withEnvironment("MISE_ENV", "test")
            toolManagerEnvironment = ToolManagerEnvironmentResolver { dir, launchVariables ->
                askedAbout = dir to launchVariables
                null
            }
        }

        output(commandLine)

        assertEquals(workDir to mapOf("MISE_ENV" to "test"), askedAbout)
    }

    /** Run configurations start their process the way [WslSafeCommandLineState.startProcess] does. */
    fun testARunConfigurationsToolManagerRunsWithoutReadAccess() {
        val commandLine = printEnvironment(WslAwareCommandLine()).apply {
            toolManagerEnvironment = ToolManagerEnvironmentResolver { _, _ ->
                ThreadingAssertions.assertBackgroundThread()
                ThreadingAssertions.assertNoReadAccess()
                ToolManagerEnvironment.Variables(mapOf("TOOL_MANAGER_PROBE" to "from-tool-manager"))
            }
        }

        val output = runWithModalProgressBlocking(project, "Starting") {
            val process = commandLine.createProcess()
            process.inputStream.bufferedReader().readText().also { process.waitFor(30, TimeUnit.SECONDS) }
        }

        assertTrue(output, "TOOL_MANAGER_PROBE=from-tool-manager" in output.lines().map { it.trim() })
    }

    fun testVariableNamesIgnoreCaseOnlyForAProcessThatRunsOnWindows() {
        assertEquals(OS.CURRENT == OS.Windows, WslAwareCommandLine().withWorkingDirectory(workDir).ignoresVariableCase())
        assertFalse(
            WslAwareCommandLine().withWorkDirectory("\\\\wsl.localhost\\Ubuntu\\home\\u\\project").ignoresVariableCase()
        )
    }

    fun testAToolManagerThatCannotProduceItsEnvironmentStopsTheProcessStarting() {
        val commandLine = printEnvironment(WslAwareCommandLine()).apply {
            toolManagerEnvironment = ToolManagerEnvironmentResolver { _, _ ->
                ToolManagerEnvironment.Error("mise config file '/p/mise.toml' is not trusted")
            }
        }

        val failure = try {
            output(commandLine)
            null
        } catch (e: FutureExecutionException) {
            e.cause
        }

        assertTrue(failure.toString(), failure is ExecutionException)
        assertTrue(failure!!.message, failure.message!!.contains("mise config file '/p/mise.toml' is not trusted"))
    }

    private fun assertGetsVariablesUnderTheLaunchsOwn(commandLine: GeneralCommandLine) {
        printEnvironment(commandLine).withEnvironment("MIX_ENV", "test")
        (commandLine as? WslAwareCommandLine)?.toolManagerEnvironment = toolManager
        (commandLine as? WslAwarePtyCommandLine)?.toolManagerEnvironment = toolManager

        val lines = output(commandLine).lines().map { it.trim() }

        assertTrue(lines.joinToString("\n"), "TOOL_MANAGER_PROBE=from-tool-manager" in lines)
        assertTrue(lines.joinToString("\n"), "MIX_ENV=test" in lines)
    }

    private val toolManager = ToolManagerEnvironmentResolver { _, _ ->
        ToolManagerEnvironment.Variables(mapOf("TOOL_MANAGER_PROBE" to "from-tool-manager", "MIX_ENV" to "dev"))
    }

    private fun <T : GeneralCommandLine> printEnvironment(commandLine: T): T = commandLine.apply {
        if (OS.CURRENT == OS.Windows) {
            exePath = "cmd.exe"
            addParameters("/c", "set")
        } else {
            exePath = "env"
        }
        withWorkingDirectory(workDir)
    }

    /** Started off the EDT, as the plugin starts its processes. */
    private fun output(commandLine: GeneralCommandLine): String =
        AppExecutorUtil.getAppExecutorService().submit(Callable {
            val process = commandLine.createProcess()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)
            output
        }).get(60, TimeUnit.SECONDS)
}
