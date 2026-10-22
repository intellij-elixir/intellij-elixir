package org.elixir_lang.expander

import org.elixir_lang.elixir_surface.LegManifest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every head a [Clause] entry declares is a clause of Elixir's expander on some supported version, and every clause of
 * Elixir's expander on a supported version is declared by some entry.
 */
class PortParityTest {
    @Test
    fun `every declared head is in some leg's expander manifest`() {
        val manifestHeads = manifestHeads()

        assertEquals(
            emptyList<String>(),
            declared().flatMap { (owner, heads) ->
                heads.filter { it.toString() !in manifestHeads }.map { "$owner: $it" }
            }
        )
    }

    @Test
    fun `every manifest head is declared by some entry`() {
        val declaredHeads = declared().flatMap { (_, heads) -> heads.map(Clause.Head::toString) }.toSet()

        assertEquals(emptyList<String>(), manifestHeads().filter { it !in declaredHeads }.sorted())
    }

    private companion object {
        const val MANIFEST = "expander-clauses.txt"

        /** Each head of Elixir's expander on some leg, as [Clause.Head.toString] writes it. */
        fun manifestHeads(): Set<String> =
            File(LegManifest.ROOT)
                .listFiles { directory -> File(directory, MANIFEST).isFile }
                .orEmpty()
                .flatMap { File(it, MANIFEST).readLines() }
                .filterNot { it.startsWith("#") || it.endsWith(" (absent)") }
                .map { it.substringBeforeLast(' ') }
                .toSet()

        /** Each owner of declared heads, with its heads. */
        fun declared(): List<Pair<String, List<Clause.Head>>> =
            Clause.entries.map { it.toString() to it.heads.toList() } +
                ("GUARD" to GUARD_HEADS) +
                ("CLAUSES" to CLAUSES_HEADS) +
                ("WITH" to WITH_HEADS) +
                ("FOR" to FOR_HEADS) +
                ("CAPTURE" to CAPTURE_HEADS)
    }
}
