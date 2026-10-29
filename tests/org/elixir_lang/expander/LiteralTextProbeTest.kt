package org.elixir_lang.expander

import org.elixir_lang.psi.ElixirFile

/**
 * A variable the harness probes is delivered the number of times Elixir delivers it, wherever it sits in the text of a
 * string, charlist, sigil or heredoc that interpolates it: the harness puts no probe in a literal's text, where Elixir
 * would never run it, but only around code.
 *
 * Each case is a guard that interpolates, a call `to_string` makes invalid, which both stop at: the probes before it
 * are compared.
 */
class LiteralTextProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness, accounting = true) {
        createPsiFile(getTestName(false), it) as ElixirFile
    }

    fun testAString() = assertProbesMatchElixir("\"#{z}a\" == \"1a\"")

    fun testAStringEndingInTheInterpolation() = assertProbesMatchElixir("\"a#{z}\" == \"a1\"")

    fun testAStringInterpolatingTwice() = assertProbesMatchElixir("\"#{z}#{z}\" == \"11\"")

    fun testACharlist() = assertProbesMatchElixir("'#{z}a' == '1a'")

    fun testALowercaseSigil() = assertProbesMatchElixir("[~s(#{z}a)] == [\"1a\"]")

    fun testACharlistSigil() = assertProbesMatchElixir("[~c(#{z}a)] == [~c(1a)]")

    fun testAHeredoc() = assertProbesMatchElixir("\"\"\"\n  #{z}a\n  \"\"\" == \"1a\\n\"")

    private fun assertProbesMatchElixir(guard: String) {
        val body = "def f() do\n  fn z when $guard -> z end\nend"

        printed { probes.assertMatchesElixir(mapOf(body to probes.expand(body))) }
    }
}
