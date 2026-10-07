package org.elixir_lang.expander

import org.elixir_lang.psi.ElixirFile
import java.util.UUID

/**
 * A call of a macro whose expansion isn't modelled stops the expansion of a case module body at that call, with the
 * dispatch Elixir resolves it to on the leg's Elixir, before any of the macro's arguments are expanded; up to there
 * the expander agrees with Elixir.
 */
class OpaqueProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    fun testEachCaseStopsAtItsMacro() {
        assertEquals(
            CASES.joinToString("\n") { (body, site) -> "${body.replace("\n", "; ")}: $site" },
            CASES.joinToString("\n") { (body, _) -> "${body.replace("\n", "; ")}: ${site(body)}" },
        )
    }

    /**
     * Each case's probes and dispatches up to its macro equal Elixir's, including macros that import, alias or bind a
     * variable, which a preamble module defines, so that what they change follows the macro.
     */
    fun testEachCaseMatchesElixirUpToItsMacro() {
        val module = "Opaque" + UUID.randomUUID().toString().replace("-", "")
        val bodies = CASES.map { it.first } + CAPTURE_CASES + PREAMBLE_CASES.map { it.replace(M, module) }

        val preamble = "\ndefmodule $module do\n$PREAMBLE\nend\n"

        probes.assertMatchesElixirUpToMacro(bodies.associateWith { probes.expand(it, preamble) })
    }

    /** The statement [body]'s expansion stopped at, its dispatch, and the source of the call. */
    private fun site(body: String): String {
        val expansion = probes.expand(body)
        val opaque = expansion.outcome as? Expansion.Opaque ?: return "not opaque: ${expansion.outcome}"

        return "${expansion.starts.size} ${ExpanderTestCase.render(opaque.dispatch)} " +
            "`${expansion.source(opaque.at)}`"
    }

    private companion object {
        val CASES = listOf(
            "a = 1\n_ = is_nil(a)\nb = 2" to "2 imported_macro Elixir.Kernel.is_nil/1 `is_nil(a)`",
            "a = 1\n_ = is_nil(abs(a) > 0)\nb = 2" to "2 imported_macro Elixir.Kernel.is_nil/1 `is_nil(abs(a) > 0)`",
            "a = 1\n_ = ~w(a b)\nb = 2" to "2 imported_macro Elixir.Kernel.sigil_w/2 `~w(a b)`",
            "a = 1\nrequire Integer\n_ = Integer.is_odd(abs(a))\nb = 2" to
                "3 remote_macro Elixir.Integer.is_odd/1 `Integer.is_odd(abs(a))`",
            "a = 1\n_ = fn -> is_nil(a) end\nb = 2" to "2 imported_macro Elixir.Kernel.is_nil/1 `is_nil(a)`",
            "a = 1\ndef f do\n  is_nil(1)\nend\nb = 2" to "3 imported_macro Elixir.Kernel.is_nil/1 `is_nil(1)`",
        )

        /** A capture of a macro stops at the macro its `fn` calls, whose position differs by leg. */
        val CAPTURE_CASES = listOf(
            "a = 1\n_ = &is_nil/1",
            "a = 1\n_ = &is_nil(&1)",
            "a = 1\n_ = &Kernel.is_nil/1",
            "a = 1\nrequire Integer\n_ = &Integer.is_odd/1",
        )

        /** Stands for the preamble module's name. */
        const val M = "@M@"

        val PREAMBLE_CASES = listOf(
            "a = 1\nrequire $M\n$M.imp()\nb = first([1])",
            "a = 1\nrequire $M\n$M.ali()\nb = SC",
            "a = 1\nrequire $M\n$M.bind()\nb = y",
        )

        val PREAMBLE =
            """
            defmacro imp, do: quote(do: import(List, only: [first: 1]))
            defmacro ali, do: quote(do: alias(String.Chars, as: SC))
            defmacro bind, do: quote(do: var!(y) = 1)
            """.trimIndent()
    }
}
