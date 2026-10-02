package org.elixir_lang.expander

import org.elixir_lang.NameArity
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
        val macros = Exports { if (it == "Elixir.$module") MACROS else legExports.of(it) }
        val bodies = CASES.map { it.first } + PREAMBLE_CASES.map { it.replace(M, module) }

        probes.assertMatchesElixirUpToMacro(
            bodies.associateWith { probes.expand(it, exports = macros) },
            "\ndefmodule $module do\n$PREAMBLE\nend\n",
        )
    }

    /** The statement [body]'s expansion stopped at, its dispatch, and the source of the call. */
    private fun site(body: String): String {
        val expansion = probes.expand(body)
        val opaque = expansion.outcome as? Expansion.Opaque ?: return "not opaque: ${expansion.outcome}"

        return "${expansion.starts.size} ${ExpanderTestCase.render(opaque.dispatch)} " +
            "`${opaque.at.meta.origin.substring(body)}`"
    }

    private companion object {
        val CASES = listOf(
            "a = 1\n_ = if a, do: 1\nb = 2" to "2 imported_macro Elixir.Kernel.if/2 `if a, do: 1`",
            "a = 1\n_ = if abs(a) > 0, do: 1\nb = 2" to "2 imported_macro Elixir.Kernel.if/2 `if abs(a) > 0, do: 1`",
            "a = 1\n_ = a |> abs()\nb = 2" to "2 imported_macro Elixir.Kernel.|>/2 `a |> abs()`",
            "a = 1\nrequire Integer\n_ = Integer.is_odd(abs(a))\nb = 2" to
                "3 remote_macro Elixir.Integer.is_odd/1 `Integer.is_odd(abs(a))`",
            "a = 1\ndef f do\n  raise \"x\"\nend\nb = 2" to
                "2 imported_macro Elixir.Kernel.def/2 `def f do\n  raise \"x\"\nend`",
            "a = 1\n_ = fn -> raise \"x\" end\nb = 2" to "2 imported_macro Elixir.Kernel.raise/1 `raise \"x\"`",
            "a = 1\nx = \"a\"\n\"#{x}\" = \"a\"" to "3 remote_macro Elixir.Kernel.to_string/1 `#{x}`",
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

        val MACROS = ModuleExports.Present(
            emptyList(),
            listOf(NameArity("ali", 0), NameArity("bind", 0), NameArity("imp", 0)),
            hasInfo = true,
        )
    }
}
