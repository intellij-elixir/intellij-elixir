package org.elixir_lang.expander

import org.elixir_lang.psi.ElixirFile

/**
 * A call of a macro whose expansion isn't modelled stops the expansion of a case module body at that call, with the
 * dispatch Elixir resolves it to on the leg's Elixir, before any of the macro's arguments are expanded.
 */
class OpaqueProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    fun testEachCaseStopsAtItsMacro() {
        assertEquals(
            CASES.joinToString("\n") { (body, site) -> "${body.replace("\n", "; ")}: $site" },
            CASES.joinToString("\n") { (body, _) -> "${body.replace("\n", "; ")}: ${site(body)}" },
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
    }
}
