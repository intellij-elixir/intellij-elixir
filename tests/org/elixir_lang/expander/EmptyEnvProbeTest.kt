package org.elixir_lang.expander

import org.elixir_lang.psi.ElixirFile

/** The env the expander gives the start of an empty module body is Elixir's, on the leg's Elixir. */
class EmptyEnvProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    fun testTheStartOfAnEmptyModuleBodyIsElixirs() {
        probes.assertMatchesElixir(mapOf("" to probes.expand("")))
    }
}
