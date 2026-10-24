package org.elixir_lang.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.trace
import org.elixir_lang.sdk.wsl.wslCompat
import org.elixir_lang.jps.shared.tool_manager.SdkEnvironment
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver
import org.elixir_lang.jps.shared.tool_manager.putToolManagerEnvironment
import org.elixir_lang.tool_manager.ignoresVariableCase
import org.elixir_lang.tool_manager.toolManagerVariables
import java.io.IOException

private val LOG = Logger.getInstance(WslAwareCommandLine::class.java)

/**
 * A GeneralCommandLine subclass that automatically applies WSL path conversion
 * right before process creation.
 *
 * This ensures ALL command line parameters, environment variables, and paths
 * are properly converted for WSL execution, regardless of when they were added
 * to the command line.
 *
 * ## Why Subclass?
 *
 * Previously, WSL conversion was applied at command line construction time via
 * wslCompatCommandLine { } wrappers. However, external tools and run configurations
 * often add parameters AFTER the factory method returns, causing those parameters
 * to bypass WSL conversion:
 *
 * ```kotlin
 * val commandLine = Mix.commandLine(...)  // WSL converted
 * commandLine.addParameters(newParams)     // NOT converted!
 * commandLine.createProcess()
 * ```
 *
 * By overriding the protected createProcess(ProcessBuilder) method, we apply conversion
 * at the last possible moment, ensuring ALL parameters are converted.
 *
 * This uses the same safe extension point that IntelliJ's PtyCommandLine uses.
 *
 * ## Usage
 *
 * Simply construct WslAwareCommandLine instead of GeneralCommandLine:
 * ```kotlin
 * val commandLine = WslAwareCommandLine()
 *     .withExePath(exePath)
 *     .withParameters(params)
 * // WSL conversion happens automatically in createProcess()
 * ```
 *
 * @see GeneralCommandLine
 * @see org.elixir_lang.sdk.wsl.WslCompatService.convertProcessBuilderArgumentsForWsl
 */
open class WslAwareCommandLine : GeneralCommandLine {
    constructor() : super()

    /** Resolves the tool manager environment added when the process starts. */
    var toolManagerEnvironment: ToolManagerEnvironmentResolver? = null

    /** The variables derived from the SDK, added after the tool manager's. */
    var sdkEnvironment: SdkEnvironment? = null

    private var resolvedToolManagerVariables: Map<String, String> = emptyMap()

    override fun setupEnvironment(environment: MutableMap<String, String>) {
        super.setupEnvironment(environment)
        putToolManagerEnvironment(
            environment, resolvedToolManagerVariables, this.environment, sdkEnvironment, ignoresVariableCase()
        )
    }

    @Throws(IOException::class)
    override fun createProcess(processBuilder: ProcessBuilder): Process {
        val resolved = toolManagerVariables(toolManagerEnvironment, workingDirectory, environment)
        if (resolved != resolvedToolManagerVariables) {
            resolvedToolManagerVariables = resolved
            // The builder's environment was set up before the variables were resolved.
            setupEnvironment(processBuilder.environment())
        }
        wslCompat.convertProcessBuilderArgumentsForWsl(processBuilder, this)
        LOG.trace  { formatCommandLineForLogging(processBuilder, "Command line") }
        return super.createProcess(processBuilder)
    }
}
