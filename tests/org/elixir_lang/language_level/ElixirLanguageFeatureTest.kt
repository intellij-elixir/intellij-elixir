package org.elixir_lang.language_level

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageFeature.*

/**
 * Each window is pinned here as the Elixir releases either side of it, so a `since` or `removedIn` edited by mistake
 * fails by name rather than as a quoting failure on one CI leg. The boundaries are the releases each entry's KDoc
 * cites.
 */
class ElixirLanguageFeatureTest : BasePlatformTestCase() {
    fun testEachFeatureAppliesFromTheReleaseThatIntroducedIt() {
        val boundaries = mapOf(
            ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER to ("1.11.4" to "1.12.0"),
            ESCAPED_NEWLINE_COUNTED_IN_LITERAL_SIGIL_LINE to ("1.11.4" to "1.12.0"),
            EMPTY_LEADING_HEREDOC_SEGMENT to ("1.11.4" to "1.12.0"),
            STEP_OPERATOR to ("1.11.4" to "1.12.0"),
            REMOTE_CALL_ON_NAME_LINE to ("1.12.3" to "1.13.0"),
            UNESCAPED_SIGIL_HEREDOC_TERMINATOR to ("1.12.3" to "1.13.0"),
            NORMALIZED_IDENTIFIERS to ("1.13.4" to "1.14.0"),
            FROM_BRACKETS_ON_BRACKETED_EXPRESSION to ("1.14.5" to "1.15.0"),
            ADJACENT_CAPTURE_ARGUMENT to ("1.14.5" to "1.15.0"),
            FROM_INTERPOLATION to ("1.15.8" to "1.16.0"),
            FROM_BRACKETS_ON_EVERY_BRACKET_FORM to ("1.16.1" to "1.16.2"),
            ELLIPSIS_NULLARY_CALL to ("1.16.3" to "1.17.0"),
            AMBIGUOUS_DUAL_OPERATOR_CALL to ("1.16.3" to "1.17.0"),
            UNESCAPED_QUOTED_REMOTE_CALL_NAME to ("1.17.3" to "1.18.0"),
            NEWLINE_COUNTED_IN_CHARACTER to ("1.18.4" to "1.19.0"),
            IN_OF_NOT_IN_ON_ITS_OWN_LINE to ("1.18.4" to "1.19.0"),
            LINE_METADATA_ON_BLOCK to ("1.19.5" to "1.20.0"),
            ESCAPED_NEWLINE_AS_SPACE to ("1.19.5" to "1.20.0"),
        )

        assertEquals(entries.filter { it.since != ElixirLanguageLevel.entries.first() }.toSet(), boundaries.keys)

        for ((feature, releases) in boundaries) {
            val (without, with) = releases
            assertFalse("$feature on $without", feature.isSufficient(ElixirLanguageLevel.of(without)))
            assertTrue("$feature on $with", feature.isSufficient(ElixirLanguageLevel.of(with)))
        }
    }

    fun testEachRemovedFeatureStopsAtTheReleaseThatRemovedIt() {
        val removals = mapOf(
            DECIMAL_NUMBER_ENDS_BEFORE_WORD to ("1.11.4" to "1.12.0"),
            SOLITARY_UNARY_WRAPPED_IN_EVERY_BLOCK to ("1.14.5" to "1.15.0"),
            ENCLOSING_PARENS_MERGE_BLOCK_METADATA to ("1.16.3" to "1.17.0"),
        )

        assertEquals(entries.filter { it.removedIn != null }.toSet(), removals.keys)

        for ((feature, releases) in removals) {
            val (last, removed) = releases
            assertTrue("$feature on $last", feature.isSufficient(ElixirLanguageLevel.of(last)))
            assertFalse("$feature on $removed", feature.isSufficient(ElixirLanguageLevel.of(removed)))
        }
    }

    fun testEveryWindowOpensBeforeItCloses() {
        for (feature in entries) {
            val removedIn = feature.removedIn ?: continue
            assertTrue("$feature", removedIn > feature.since)
        }
    }

    fun testIsAvailableReadsTheElementsLanguageLevel() {
        val file = myFixture.configureByText("available.ex", "x")

        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, ElixirLanguageLevel.of("1.11.4"))
            assertFalse(ElixirLanguageLevelResolver.isAvailable(STEP_OPERATOR, file))

            ElixirLanguageLevelResolver.overrideLanguageLevel(project, ElixirLanguageLevel.of("1.12.0"))
            assertTrue(ElixirLanguageLevelResolver.isAvailable(STEP_OPERATOR, file))
        } finally {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        }
    }
}
