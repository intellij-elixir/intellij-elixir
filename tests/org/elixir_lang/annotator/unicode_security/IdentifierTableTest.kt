package org.elixir_lang.annotator.unicode_security

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class IdentifierTableTest {
    /**
     * A release missing from the index silently checks with an older release's table, so adding one to
     * `.github/ci-versions.json` without rerunning `generate.exs` must fail here.
     */
    @Test
    fun `every declared release from 1_14 has its own row`() {
        val beam = JsonParser.parseString(Files.readString(Path.of(".github", "ci-versions.json")))
            .asJsonObject["beam"].asJsonObject
        val declared = (listOf(beam["baseline"]) + (beam["additional"]?.asJsonArray ?: JsonArray()))
            .map { ElixirLanguageLevel.of(it.asJsonObject["elixir"].asString).elixir }
            .map { it.major to it.minor }
            .filter { (major, minor) -> major > 1 || minor >= 14 }
            .toSet()

        assertEquals(declared, IDENTIFIER_TABLE_UNICODE_VERSIONS.map { (release, _) -> release }.toSet())
    }

    @Test
    fun `a minor newer than the index uses the newest table`() {
        assertSame(
            IdentifierTable.forLanguageLevel(ElixirLanguageLevel.of("1.20.4")),
            IdentifierTable.forLanguageLevel(ElixirLanguageLevel.of("1.21.0")),
        )
    }
}
