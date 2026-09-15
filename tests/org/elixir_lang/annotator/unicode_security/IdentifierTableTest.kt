package org.elixir_lang.annotator.unicode_security

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IdentifierTableTest {
    /**
     * `generate.exs` builds a table for each release in `.github/ci-versions.json` from 1.14, when Elixir began checking
     * identifiers, so a level added without regenerating the tables would silently check nothing.
     */
    @Test
    fun `every level from 1_14 has a generated table`() {
        for (level in ElixirLanguageLevel.entries.filter { it >= ElixirLanguageLevel.V1_14 }) {
            assertNotNull(level.firstRelease, IdentifierTable.forLanguageLevel(level))
        }
    }

    @Test
    fun `levels before 1_14 have no table`() {
        for (level in ElixirLanguageLevel.entries.filter { it < ElixirLanguageLevel.V1_14 }) {
            assertNull(level.firstRelease, IdentifierTable.forLanguageLevel(level))
        }
    }
}
