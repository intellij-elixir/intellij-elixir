package org.elixir_lang

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import java.io.FileNotFoundException
import org.elixir_lang.sdk.wsl.wslCompat

object Distillery {
    /**
     * Keep in-sync with `org.elixir_lang.jps.Builder.erlCommandLine`
     */
    fun commandLine(
        pty: Boolean,
        environment: Map<String, String>,
        workingDirectory: String?,
        exePath: String?,
        project: Project?,
    ): GeneralCommandLine {
        val commandLine = org.elixir_lang.run.baseCommandLine(pty, environment, workingDirectory, project)
        commandLine.exePath = exePath ?: throw FileNotFoundException("Distillery release CLI path is not set")
        wslCompat.requireReachable(exePath, "Distillery release CLI")

        return commandLine
    }
}
