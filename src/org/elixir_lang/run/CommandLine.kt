package org.elixir_lang.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.util.system.OS
import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import org.elixir_lang.jps.shared.tool_manager.SdkEnvironment
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver
import org.elixir_lang.sdk.wsl.wslCompat
import org.elixir_lang.tool_manager.toolManagerEnvironment

@Throws(ExecutionException::class)
fun baseCommandLine(
    pty: Boolean,
    environment: Map<String, String>,
    workingDirectory: String?,
    project: Project?,
    sdkEnvironment: SdkEnvironment? = null,
): GeneralCommandLine {
    workingDirectory?.let { wslCompat.requireReachable(it, "Working directory") }

    return baseCommandLine(pty, toolManagerEnvironment(project), sdkEnvironment)
            .withCharset(Charsets.UTF_8)
            .withEnvironment(environment)
            .withWorkDirectory(workingDirectory)
}

private fun baseCommandLine(
    pty: Boolean,
    toolManagerEnvironment: ToolManagerEnvironmentResolver?,
    sdkEnvironment: SdkEnvironment?,
): GeneralCommandLine =
        if (pty) {
            WslAwarePtyCommandLine().apply {
                this.toolManagerEnvironment = toolManagerEnvironment
                this.sdkEnvironment = sdkEnvironment
                if (OS.CURRENT != OS.Windows) {
                    /* `tty_sl -c -e`'s `start_termcap` will fail if `TERM` is not set
                       (https://github.com/erlang/otp/blob/360b68d76d8c297d950616f088458b7c239be7ee/erts/emulator/drivers/unix/ttsl_drv.c#L1327-L1332) */
                    environment.putIfAbsent("TERM", "xterm-256color")
                }
            }
        } else {
            WslAwareCommandLine().apply {
                this.toolManagerEnvironment = toolManagerEnvironment
                this.sdkEnvironment = sdkEnvironment
            }
        }
