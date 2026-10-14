package org.elixir_lang.snapshot

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.junit.UnitTestCase
import org.elixir_lang.snapshot.CompilerFacts.HEADER
import org.elixir_lang.snapshot.CompilerFacts.INPUTS
import org.elixir_lang.snapshot.CompilerFacts.ROOT
import org.elixir_lang.snapshot.CompilerFacts.committed
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fails when what the leg's Elixir compiler says about the resolution snapshot's inputs differs from the facts
 * committed for its version, which `generate.exs` writes, or when any committed fact is malformed.
 */
class CompilerFactsTest : UnitTestCase() {
    fun testFactsMatchTheCompiler() {
        val elixirVersion = environment("ELIXIR_VERSION")
        val otpVersion = environment("ERLANG_VERSION")
        val generated = FileUtil.createTempDirectory("compiler-facts", null)

        try {
            generate(generated)
            assertEquals(
                "the Elixir that ran generate.exs is not the leg's",
                listOf(elixirVersion),
                generated.list().orEmpty().toList()
            )

            for (input in INPUTS.keys) {
                val actual = File(generated, "$elixirVersion/$input.facts").readText()
                LegManifest.assertMatchesFile(
                    ROOT,
                    elixirVersion,
                    "$input.facts",
                    actual,
                    LegManifest.regenerateCommand(CompilerFactsTest::class, elixirVersion, otpVersion)
                )
            }
        } finally {
            FileUtil.delete(generated)
        }
    }

    fun testEveryCommittedFactIsWellFormed() {
        val malformed = committed().flatMap { (version, facts) ->
            val lines = facts.readLines()
            val header = if (HEADER.find(lines.first())?.groupValues?.get(1) == version.name) {
                emptyList()
            } else {
                listOf("$facts:1: ${lines.first()}")
            }

            header + lines.drop(1).withIndex()
                .filter { (_, line) -> CompilerFacts.parse(line) == null }
                .map { (index, line) -> "$facts:${index + 2}: $line" }
        }

        assertTrue("Malformed facts:\n${malformed.joinToString("\n")}", malformed.isEmpty())
    }

    private fun generate(root: File) {
        val commandLine = GeneralCommandLine(CompilerFacts.elixir().path, "generate.exs", root.path)
            .withWorkingDirectory(File(ROOT).toPath())
        val output = CapturingProcessHandler(commandLine).runProcess(TimeUnit.MINUTES.toMillis(5).toInt())

        assertFalse("generate.exs timed out:\n${output.stdout}\n${output.stderr}", output.isTimeout)
        assertEquals("generate.exs failed:\n${output.stdout}\n${output.stderr}", 0, output.exitCode)
    }
}
