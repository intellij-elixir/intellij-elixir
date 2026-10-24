package org.elixir_lang.jps.shared.tool_manager.mise

import junit.framework.TestCase
import org.elixir_lang.jps.shared.tool_manager.ToolManagerCommandOutput
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironment
import java.io.IOException
import java.nio.file.Path

class MiseEnvironmentTest : TestCase() {
    fun testMiseEnvJsonIsTheEnvironment() {
        val stdout = """
            {
              "CARGO_HOME": "/home/u/.cargo",
              "PATH": "/home/u/.local/share/mise/installs/erlang/29.1.2/bin:/home/u/.cargo/bin:/usr/bin",
              "RUSTUP_TOOLCHAIN": "1.94.1",
              "MAIN_VAR": "MAIN ONE"
            }
        """.trimIndent()

        assertEquals(
            ToolManagerEnvironment.Variables(
                mapOf(
                    "CARGO_HOME" to "/home/u/.cargo",
                    "PATH" to "/home/u/.local/share/mise/installs/erlang/29.1.2/bin:/home/u/.cargo/bin:/usr/bin",
                    "RUSTUP_TOOLCHAIN" to "1.94.1",
                    "MAIN_VAR" to "MAIN ONE",
                )
            ),
            MiseEnvironment.fromOutput(0, stdout, "")
        )
    }

    fun testAnUntrustedConfigIsAnErrorNamingTheFileAndTheFix() {
        val stderr = """
            mise ERROR error parsing config file: /home/u/project/mise.toml
            mise ERROR Config files in /home/u/project/mise.toml are not trusted.
            Trust them with `mise trust`. See https://mise.jdx.dev/cli/trust.html for more information.
            mise ERROR Run with --verbose or MISE_VERBOSE=1 for more information
        """.trimIndent()

        val environment = MiseEnvironment.fromOutput(1, "", stderr)

        assertTrue(environment.toString(), environment is ToolManagerEnvironment.Error)
        val message = (environment as ToolManagerEnvironment.Error).message
        assertTrue(message, message.contains("/home/u/project/mise.toml"))
        assertTrue(message, message.contains("mise trust"))
    }

    fun testAnyOtherFailureIsAnErrorCarryingMisesOwnMessage() {
        val stderr = """
            mise::config::parse_error

              × Invalid TOML in config file: /home/u/project/mise.toml
        """.trimIndent()

        val environment = MiseEnvironment.fromOutput(1, "", stderr)

        assertTrue(environment.toString(), environment is ToolManagerEnvironment.Error)
        val message = (environment as ToolManagerEnvironment.Error).message
        assertTrue(message, message.contains("Invalid TOML in config file: /home/u/project/mise.toml"))
    }

    fun testOutputThatIsNotAnObjectOfStringsIsAnError() {
        for (stdout in listOf("not json", """{"PATH": ["/a", "/b"]}""", "")) {
            val environment = MiseEnvironment.fromOutput(0, stdout, "")

            assertTrue("$stdout gave $environment", environment is ToolManagerEnvironment.Error)
        }
    }

    fun testMiseEnvJsonRunsInTheWorkingDirectoryWithTheLaunchsVariables() {
        val workDir = Path.of("/home/u/project")
        var ran: Triple<List<String>, Path, Map<String, String>>? = null

        MiseEnvironment { command, dir, environment ->
            ran = Triple(command, dir, environment)
            ToolManagerCommandOutput(0, "{}", "")
        }.resolveEnvironment(workDir, mapOf("MISE_ENV" to "test"))

        assertEquals(Triple(listOf("mise", "env", "--json"), workDir, mapOf("MISE_ENV" to "test")), ran)
    }

    fun testMiseThatCannotBeRunIsAnErrorSayingWhy() {
        val environment = MiseEnvironment { _, _, _ -> throw IOException("Cannot run program \"mise\"") }
            .resolveEnvironment(Path.of("/home/u/project"), emptyMap())

        assertTrue(environment.toString(), environment is ToolManagerEnvironment.Error)
        val message = (environment as ToolManagerEnvironment.Error).message
        assertTrue(message, message.contains("Cannot run program \"mise\""))
    }
}
