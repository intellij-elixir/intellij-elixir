package org.elixir_lang.language_level

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Production code asks an [ElixirLanguageFeature] instead of comparing levels, so a level inserted between two others,
 * or a behaviour a later release removes, changes one entry rather than every comparison that read the boundary.
 */
class LanguageLevelReferencesTest {
    @Test
    fun `only the level and feature enums name a level`() {
        val references = File("src").walkTopDown()
            .filter { it.isFile && it.extension in SOURCE_EXTENSIONS && it.name !in ALLOWED }
            .sortedBy { it.invariantSeparatorsPath }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> LEVEL.containsMatchIn(line) }
                    .map { (index, line) -> "${file.invariantSeparatorsPath}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertEquals("", references.joinToString(System.lineSeparator()))
    }

    private companion object {
        val ALLOWED = setOf("ElixirLanguageLevel.kt", "ElixirLanguageFeature.kt")
        val SOURCE_EXTENSIONS = setOf("kt", "java")
        val LEVEL = Regex("(^|[^A-Za-z0-9_])V1_[0-9]+")
    }
}
