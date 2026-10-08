package org.elixir_lang.expander

import org.elixir_lang.psi.ElixirFile

/**
 * `use` compiled in a case module body on the leg's Elixir: it requires each module it names, in order, and stops at
 * the `__using__` macro of the first, with the env, the variables and the hygiene counters up to there equal to
 * Elixir's, and a `use` of something that is no module raises where the compiler raises.
 */
class UseProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness, accounting = true) {
        createPsiFile(getTestName(false), it) as ElixirFile
    }

    fun testEachCaseStopsAtTheUsingMacroOfItsFirstModule() {
        val stops = USING_CASES.map { body ->
            val expansion = probes.expand(body, PREAMBLE)
            val opaque = expansion.outcome as? Expansion.Opaque ?: return@map "${body.replace("\n", "; ")}: ${expansion.outcome}"

            "${body.replace("\n", "; ")}: ${opaque.dispatch.name}/${opaque.dispatch.arity} " +
                opaque.dispatch.receiver.replace(TOKENED, "{token}")
        }

        assertEquals(
            USING_CASES.joinToString("\n") {
                "${it.replace("\n", "; ")}: __using__/1 ${FIRST.getValue(it)}"
            },
            stops.joinToString("\n"),
        )
    }

    fun testEachCaseMatchesElixirUpToItsUsingMacro() {
        printed { probes.assertMatchesElixirUpToMacro(USING_CASES.associateWith { probes.expand(it, PREAMBLE) }) }
    }

    fun testRaises() {
        val expansions = RAISE_CASES.associateWith { probes.expand(it, PREAMBLE) }

        printed { probes.assertMatchesElixir(expansions) }
    }

    private companion object {
        /** A module's name with the compile's token, which `{token}` stands for in a body. */
        val TOKENED = Regex("""Elixir\.ProbeCase[0-9a-f]{32}""")

        val PREAMBLE =
            """
            defmodule {token}.U do
              defmacro __using__(opts) do
                quote do
                  def used, do: unquote(opts)
                end
              end
            end
            defmodule {token}.U.A do
              defmacro __using__(_opts), do: quote(do: def(used_a, do: 1))
            end
            defmodule {token}.U.B do
              defmacro __using__(_opts), do: quote(do: def(used_b, do: 1))
            end
            """.trimIndent()

        /** Each body and the module it stops at, with the token left out. */
        val FIRST = mapOf(
            "use {token}.U" to "{token}.U",
            "use {token}.U, opt: 1" to "{token}.U",
            "alias {token}.U\nuse U" to "{token}.U",
            "alias {token}.U, as: V\nuse V, 1" to "{token}.U",
            "use :\"Elixir.{token}.U\"" to "{token}.U",
            "use {token}.U.{A, B}" to "{token}.U.A",
            "alias {token}.U\nuse U.{A, B}" to "{token}.U.A",
            "use {token}.U.{A, B}, 1" to "{token}.U.A",
            "use {token}.U.{:A, :B}" to "{token}.U.A",
            "use {token}.U.{B, A}" to "{token}.U.B",
        )

        val USING_CASES = FIRST.keys.toList()

        val RAISE_CASES = listOf(
            "use 1",
            "x = 1\nuse x",
            "use \"a\"",
            "use {token}.U.{A, 1}",
            "use {token}.U.{A, x}",
            "use Nope",
            "use Nope.{A, B}",
        )
    }
}
