package org.elixir_lang.expander.differential

import org.elixir_lang.expander.ExpansionProbes
import org.elixir_lang.expander.ExpansionProbes.Comparison
import org.elixir_lang.expander.ProbeTestCase
import org.elixir_lang.psi.ElixirFile

/** The rows of a golden that no leg's oracle cases reach, as they read when a case diverges, stops or is unported. */
class ProbeOutcomesTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness, definitions = true) { createPsiFile(getTestName(false), it) as ElixirFile }
    private val outcomes = ProbeOutcomes(probes)

    fun testADiffMarksTheExpandersLinesMinusAndElixirsPlus() {
        assertEquals(
            listOf(
                "@@ -2,1 +1,0 @@",
                "-{token}.Case second",
                "@@ -3,0 +3,1 @@",
                "+fourth: {token}",
            ),
            outcomes.diff(Comparison(EXPANDER, ELIXIR))
        )
    }

    fun testARowThatMatchesHasNoDiff() {
        val row = outcomes.row(Comparison(ELIXIR, ELIXIR), "")

        assertEquals("match", row.status)
        assertEquals(emptyList<String>(), row.diff)
        assertTrue(row.matches)
    }

    fun testARowThatDivergesKeepsItsStopAndDiff() {
        val row = outcomes.row(Comparison(EXPANDER, ELIXIR), "stops at remote_macro ")

        assertEquals("stops at remote_macro diff", row.status)
        assertEquals(outcomes.diff(Comparison(EXPANDER, ELIXIR)), row.diff)
        assertFalse(row.matches)
    }

    fun testAnUnportedCaseNamesItsNode() {
        val rows = outcomes.of(mapOf("unported.ex" to probes.expand("alias Foo.Bar, warn: 1")))

        assertEquals(listOf("unported alias/2"), rows.values.map { it.status })
    }

    /** An error in a definition that isn't a statement of its module's body stops the module, and the case still has a row. */
    fun testACaseWhoseModuleStopsAtANodeHasARow() {
        val rows = outcomes.of(mapOf("stopped.ex" to probes.expand("case 1 do\n  _ -> def f, do: (x -> x)\nend")))

        assertEquals(listOf("stopped def/2"), rows.values.map { it.status })
    }

    fun testACaseThatStopsAtAMacroIsComparedUpToIt() {
        val rows = outcomes.of(mapOf("opaque.ex" to probes.expand("use Agent")))

        assertEquals(listOf("stops at 1 remote_macro Elixir.Agent.__using__/1 match"), rows.values.map { it.status })
    }

    fun testAGoldenHasOneLineForEachRowAndHunk() {
        val rows = mapOf(
            "b.ex" to outcomes.row(Comparison(EXPANDER, ELIXIR), ""),
            "a.ex" to ProbeOutcomes.Row("unported alias/2"),
            "c.ex" to ProbeOutcomes.Row("stops at remote_macro match"),
        ).toSortedMap()

        assertEquals(
            "a.ex\tunported alias/2\n" +
                "b.ex\tdiff\n" +
                "b.ex\t@@ -2,1 +1,0 @@\n" +
                "b.ex\t-{token}.Case second\n" +
                "b.ex\t@@ -3,0 +3,1 @@\n" +
                "b.ex\t+fourth: {token}\n" +
                "c.ex\tstops at remote_macro match\n",
            outcomes.golden(rows)
        )
    }

    private companion object {
        const val TOKEN = "ProbeCase0123456789abcdef0123456789abcdef"

        /** The expander's text, with the compile's token in a changed line. */
        const val EXPANDER = "$TOKEN.Case7 first\n$TOKEN.Case7 second\nthird\nfifth"

        /** Elixir's text, which drops `second` and adds `fourth`. */
        const val ELIXIR = "$TOKEN.Case7 first\nthird\nfourth: $TOKEN\nfifth"
    }
}
