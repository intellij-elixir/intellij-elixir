package org.elixir_lang.jps.shared.tool_manager.mise

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import org.elixir_lang.jps.shared.tool_manager.ToolManagerCommandRunner
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironment
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver
import java.io.IOException
import java.nio.file.Path

/** Resolves the environment mise applies in a directory, from `mise env --json`. */
class MiseEnvironment(private val runner: ToolManagerCommandRunner) : ToolManagerEnvironmentResolver {
    /** Never `null`: outside any project mise still applies its global configuration. */
    override fun resolveEnvironment(workDir: Path, launchVariables: Map<String, String>): ToolManagerEnvironment {
        val output = try {
            runner.run(COMMAND, workDir, launchVariables)
        } catch (e: IOException) {
            return ToolManagerEnvironment.Error("mise could not be run, so its environment cannot be applied: ${e.message}")
        }

        return fromOutput(output.exitCode, output.stdout, output.stderr)
    }

    companion object {
        private val LOG = Logger.getInstance(MiseEnvironment::class.java)

        /** The tool manager's persistence key in [org.elixir_lang.jps.shared.tool_manager.ToolManagerSettingsState]. */
        const val NAME = "mise"

        val COMMAND: List<String> = listOf("mise", "env", "--json")

        fun fromOutput(exitCode: Int, stdout: String, stderr: String): ToolManagerEnvironment {
            if (exitCode != 0) {
                val message = untrustedConfigPath(stderr)?.let(::untrustedConfigMessage)
                    ?: "mise env exited with $exitCode, so its environment cannot be applied:\n${stderr.trim()}"
                return ToolManagerEnvironment.Error(message)
            }

            val variables = try {
                Gson().fromJson(stdout, JsonObject::class.java)
                    ?.entrySet()
                    ?.associate { (name, value) -> name to value.asString }
            } catch (e: RuntimeException) {
                LOG.debug("mise env --json output did not parse: ${e.message}")
                null
            }

            return variables?.let { ToolManagerEnvironment.Variables(it) }
                ?: ToolManagerEnvironment.Error(
                    "mise env --json did not print a JSON object of strings, so its environment cannot be applied:\n" +
                        stdout.trim().take(500)
                )
        }

        /** The config path from the error mise prints for a config that has not been trusted with `mise trust`. */
        fun untrustedConfigPath(stderr: String): String? =
            Regex("""Config files in (.+) are not trusted\.""").find(stderr)?.groupValues?.get(1)?.trim()

        private fun untrustedConfigMessage(configPath: String): String =
            "mise config file '$configPath' is not trusted, so its environment cannot be applied. " +
                "Run `mise trust` in the project directory."
    }
}
