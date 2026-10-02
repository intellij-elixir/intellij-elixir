package org.elixir_lang.expander

import org.elixir_lang.elixir_surface.LegManifest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Every head a [Clause] entry declares is a clause of Elixir's expander on some supported version. */
class PortParityTest {
    @Test
    fun `every declared head is in some leg's expander manifest`() {
        val manifestHeads = File(LegManifest.ROOT)
            .listFiles { directory -> File(directory, MANIFEST).isFile }
            .orEmpty()
            .flatMap { File(it, MANIFEST).readLines() }
            .filterNot { it.startsWith("#") || it.endsWith(" (absent)") }
            .map { it.substringBeforeLast(' ') }
            .toSet()

        assertEquals(
            emptyList<String>(),
            (
                Clause.entries.map { it.toString() to it.heads.toList() } +
                    ("GUARD" to GUARD_HEADS) +
                    ("CLAUSES" to CLAUSES_HEADS) +
                    ("WITH" to WITH_HEADS) +
                    ("FOR" to FOR_HEADS) +
                    ("CAPTURE" to CAPTURE_HEADS)
                ).flatMap { (owner, heads) ->
                heads.filter { it.toString() !in manifestHeads }.map { "$owner: $it" }
            }
        )
    }

    private companion object {
        const val MANIFEST = "expander-clauses.txt"
    }
}
