package org.elixir_lang.snapshot

import org.elixir_lang.junit.UnitTestCase

/** Fails when what the leg's Elixir compiler says about Elixir's own tests is not a well-formed set of facts. */
class CompilerCorpusFactsTest : UnitTestCase() {
    /** An input path the compiler names its files by otherwise would leave every event out. */
    fun testEveryApplicationHasReferences() {
        if (!CompilerCorpus.measured) return

        val without = CompilerCorpus.facts.filterValues { lines -> lines.none { it.startsWith("ref\t") } }.keys

        assertTrue("Applications with no ref fact: $without", without.isEmpty())
    }

    fun testEveryFactIsWellFormed() {
        if (!CompilerCorpus.measured) return

        val malformed = CompilerCorpus.facts.flatMap { (application, lines) ->
            lines.drop(1).filter { CompilerFacts.parse(it) == null }.map { "$application: $it" }
        }

        assertTrue("Malformed facts:\n${malformed.take(REPORTED).joinToString("\n")}", malformed.isEmpty())
    }

    private companion object {
        const val REPORTED = 25
    }
}
