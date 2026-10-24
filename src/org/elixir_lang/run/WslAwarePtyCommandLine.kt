package org.elixir_lang.run

import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.trace
import org.elixir_lang.sdk.wsl.wslCompat
import org.elixir_lang.jps.shared.tool_manager.SdkEnvironment
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver
import org.elixir_lang.jps.shared.tool_manager.putToolManagerEnvironment
import org.elixir_lang.tool_manager.ignoresVariableCase
import org.elixir_lang.tool_manager.toolManagerVariables
import java.io.IOException

private val LOG = Logger.getInstance(WslAwarePtyCommandLine::class.java)

/**
 * A PtyCommandLine subclass that automatically applies WSL path conversion
 * right before process creation.
 *
 * This is the PTY equivalent of WslAwareCommandLine, ensuring interactive
 * sessions (IEx, Distillery consoles) also get WSL path conversion.
 *
 * @see PtyCommandLine
 * @see WslAwareCommandLine
 * @see org.elixir_lang.sdk.wsl.WslCompatService.convertProcessBuilderArgumentsForWsl
 */
open class WslAwarePtyCommandLine : PtyCommandLine {
    constructor() : super()

    /** Resolves the tool manager environment added when the process starts. */
    var toolManagerEnvironment: ToolManagerEnvironmentResolver? = null

    /** The variables derived from the SDK, added after the tool manager's. */
    var sdkEnvironment: SdkEnvironment? = null

    private var resolvedToolManagerVariables: Map<String, String> = emptyMap()

    /** Also builds the environment a PTY process starts with, which ignores the [ProcessBuilder]'s. */
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
            // The builder's environment, used when PTY is unavailable, was set up before the variables were resolved.
            setupEnvironment(processBuilder.environment())
        }
        wslCompat.convertProcessBuilderArgumentsForWsl(processBuilder, this)
        LOG.trace { formatCommandLineForLogging(processBuilder, "PTY Command line") }
        return super.createProcess(processBuilder)
    }
}
