package org.elixir_lang.expander

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.util.system.OS
import org.elixir_lang.elixir_surface.LegManifest
import java.io.File
import java.util.concurrent.TimeUnit

/** `mix`, run by the leg's Elixir in a subprocess that sees only the environment it is given. */
internal object MixProcess {
    /**
     * `mix` with [arguments] in [project], with [environment] as its whole environment and [elixirArguments] given to
     * `elixir` ahead of `mix`. Fails unless it exits 0 within [timeoutMinutes].
     */
    fun run(
        project: File,
        environment: Map<String, String>,
        arguments: List<String>,
        elixirArguments: List<String> = emptyList(),
        timeoutMinutes: Long = 5,
    ): ProcessOutput {
        val bin = File(LegManifest.environment("ELIXIR_LANG_ELIXIR_PATH"), "bin")
        val elixir = File(bin, if (OS.CURRENT == OS.Windows) "elixir.bat" else "elixir")
        val commandLine = GeneralCommandLine(listOf(elixir.path) + elixirArguments + File(bin, "mix").path + arguments)
            .withWorkingDirectory(project.absoluteFile.toPath())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            .withEnvironment(environment)
        val result = CapturingProcessHandler(commandLine).runProcess(TimeUnit.MINUTES.toMillis(timeoutMinutes).toInt())

        check(!result.isTimeout) { "mix ${arguments.first()} timed out:\n${result.stdout}\n${result.stderr}" }
        check(result.exitCode == 0) { "mix ${arguments.first()} failed:\n${result.stdout}\n${result.stderr}" }

        return result
    }
}
