package org.elixir_lang.snapshot

import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.junit.UnitTestCase
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Fails when what the leg's Elixir compiler says about Elixir's own tests is not a well-formed set of facts, or not
 * the facts whose line count and SHA-256 are committed for the leg.
 */
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

    fun testFactsMatchTheirCommittedHashes() {
        if (!CompilerCorpus.measured) return

        val elixirVersion = environment("ELIXIR_VERSION")
        val hashes = CompilerCorpus.facts.map { (application, lines) ->
            val text = lines.joinToString("\n", postfix = "\n")
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

            "$application\t${lines.size}\t${HexFormat.of().formatHex(digest)}"
        }

        LegManifest.assertMatchesFile(
            CompilerFacts.ROOT,
            elixirVersion,
            "corpus.sha256",
            hashes.joinToString("\n", postfix = "\n"),
            LegManifest.regenerateCommand(CompilerCorpusFactsTest::class, elixirVersion, environment("ERLANG_VERSION"))
        )
    }

    private companion object {
        const val REPORTED = 25
    }
}
