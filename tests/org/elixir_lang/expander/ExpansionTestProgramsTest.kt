package org.elixir_lang.expander

import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.expander.differential.ProbeOutcomes
import org.elixir_lang.psi.ElixirFile
import java.io.File

/**
 * The programs Elixir's own `expansion_test.exs` expands, as `testData/.../expansion_test/generate.exs` extracts them
 * for the leg, through [ProbeOutcomes] with the hook on, so the expander's env, variables and definition tables are
 * compared with what the leg's Elixir computes compiling each one. `expansion_test.exs`'s own assertions are not used:
 * a program that fails to compile is compared as any erroring case is. The leg's outcomes are committed, and the
 * cases that match may only grow.
 */
class ExpansionTestProgramsTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness, definitions = true) { createPsiFile(getTestName(false), it) as ElixirFile }
    private val outcomes = ProbeOutcomes(probes)

    fun testElixirsExpansionTestPrograms() {
        val leg = LegManifest.environment("ELIXIR_VERSION")
        val cases = File(CASES)
            .listFiles { file -> file.name.endsWith(".ex") }
            .orEmpty()
            .sortedBy { it.name }
            .map { it.name to it.readLines() }
            .filter { (_, lines) -> leg in legs(lines) }
            .associate { (name, lines) -> name to lines.dropWhile { it.startsWith("#") }.joinToString("\n") }
        val expansions = cases.mapValues { (_, body) -> probes.expand(body, PLACEHOLDER, hook = ProbeHarness.Hook()) }
        outcomes.assertGolden("expansion_test", expansions, FLOOR, ExpansionTestProgramsTest::class)
    }

    /**
     * A `receive` without `after 0` waits when the module body runs it, and is refused there; in a definition's body,
     * which the case's compile does not run, it is a program like another.
     */
    fun testAReceiveWithoutAfterZeroIsRefusedOnlyWhereTheCompileRunsIt() {
        val receive = "receive do\n  x -> x\nend"

        probes.expand("def f do\n  receive do\n    x -> x\n  end\nend", PLACEHOLDER)
        probes.expand("defmodule M do\n  Kernel.defp g, do: (receive do\n    x -> x\n  end)\nend", PLACEHOLDER)

        listOf(receive, "defmodule M do\n  $receive\nend").forEach { body ->
            val failure = try {
                probes.expand(body, PLACEHOLDER)

                null
            } catch (failure: IllegalStateException) {
                failure
            }

            assertNotNull("refused: $body", failure)
        }
    }

    /** What a definition unquotes runs when the module compiles, so a `receive` there is refused. */
    fun testAReceiveInADefinitionsUnquoteIsRefused() {
        val receive = "receive do\n  x -> x\nend"

        listOf(
            "def f, do: unquote(($receive))",
            "def unquote(($receive))(x), do: x",
            "def f(x) when unquote(($receive)), do: x",
        ).forEach { body ->
            val failure = try {
                probes.expand(body, PLACEHOLDER)

                null
            } catch (failure: IllegalStateException) {
                failure
            }

            assertNotNull("refused: $body", failure)
        }
    }

    /** The versions on a case's `# legs:` line. */
    private fun legs(lines: List<String>): List<String> =
        lines.first { it.startsWith(LEGS) }.removePrefix(LEGS).trim().split(" ")

    private companion object {
        const val CASES = "testData/org/elixir_lang/expander/expansion_test/cases"

        /** The line of a case's header that names the legs it is on. */
        const val LEGS = "# legs:"

        /** Stands for the module Elixir compiles each case body in, whose hygiene counters are `{Module, N}`. */
        const val PLACEHOLDER = "Elixir.ExpansionTestCase"

        /** The lowest count of `match` rows over the legs; it may only grow. */
        const val FLOOR = 264
    }
}
