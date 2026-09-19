package org.elixir_lang.language_level

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The code that parses, quotes and checks Elixir asks an [ElixirLanguageFeature] rather than naming an Elixir release.
 */
class LanguageLevelReferencesTest {
    @Test
    fun `only the language level and its features name an Elixir release`() {
        val references = SCANNED.map(::File).flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension in SOURCE_EXTENSIONS && it.name !in ALLOWED }
            .sortedBy { it.invariantSeparatorsPath }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> RELEASE.containsMatchIn(line) }
                    .map { (index, line) -> "${file.invariantSeparatorsPath}:${index + 1}: ${line.trim()}" }
            }
            .toList()

        assertEquals("", references.joinToString(System.lineSeparator()))
    }

    private companion object {
        val SCANNED = listOf(
            "src/org/elixir_lang/annotator",
            "src/org/elixir_lang/language_level",
            "src/org/elixir_lang/parser",
            "src/org/elixir_lang/psi",
        )
        val ALLOWED = setOf("ElixirLanguageLevel.kt", "ElixirLanguageFeature.kt")
        val SOURCE_EXTENSIONS = setOf("kt", "java")
        val RELEASE = Regex("""["][0-9]+[.][0-9]+([.][0-9]+)?(-rc[.][0-9]+)?["]""")
    }
}
