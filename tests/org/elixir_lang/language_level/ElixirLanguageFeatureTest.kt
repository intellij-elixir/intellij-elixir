package org.elixir_lang.language_level

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageFeature.*

/**
 * Each window is pinned here as the Elixir tags either side of it: the last tag without the behaviour and the first
 * with it, pre-releases included, so a boundary edited by mistake fails by name rather than as a quoting failure on
 * one CI leg. The tags are the first ones containing the commits each entry's KDoc cites.
 */
class ElixirLanguageFeatureTest : BasePlatformTestCase() {
    fun testEachFeatureAppliesFromTheFirstTagThatShippedIt() {
        val boundaries = mapOf(
            ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER to ("1.11.4" to "1.12.0-rc.0"),
            ESCAPED_NEWLINE_COUNTED_IN_LITERAL_SIGIL_LINE to ("1.11.4" to "1.12.0-rc.0"),
            EMPTY_LEADING_HEREDOC_SEGMENT to ("1.11.4" to "1.12.0-rc.0"),
            STEP_OPERATOR to ("1.11.4" to "1.12.0-rc.0"),
            REMOTE_CALL_ON_NAME_LINE to ("1.12.3" to "1.13.0-rc.0"),
            UNESCAPED_SIGIL_HEREDOC_TERMINATOR to ("1.13.0-rc.0" to "1.13.0-rc.1"),
            NORMALIZED_IDENTIFIERS to ("1.13.4" to "1.14.0-rc.0"),
            FROM_BRACKETS_ON_BRACKETED_EXPRESSION to ("1.14.5" to "1.15.0-rc.0"),
            ADJACENT_CAPTURE_ARGUMENT to ("1.14.5" to "1.15.0-rc.0"),
            FROM_INTERPOLATION to ("1.15.8" to "1.16.0-rc.0"),
            FROM_BRACKETS_ON_EVERY_BRACKET_FORM to ("1.16.1" to "1.16.2"),
            ELLIPSIS_NULLARY_CALL to ("1.16.3" to "1.17.0-rc.0"),
            AMBIGUOUS_DUAL_OPERATOR_CALL to ("1.16.3" to "1.17.0-rc.0"),
            UNESCAPED_QUOTED_REMOTE_CALL_NAME to ("1.17.3" to "1.18.0-rc.0"),
            NEWLINE_COUNTED_IN_CHARACTER to ("1.19.0-rc.0" to "1.19.0-rc.1"),
            IN_OF_NOT_IN_ON_ITS_OWN_LINE to ("1.19.0-rc.0" to "1.19.0-rc.1"),
            LINE_METADATA_ON_BLOCK to ("1.19.5" to "1.20.0-rc.0"),
            ESCAPED_NEWLINE_AS_SPACE to ("1.19.5" to "1.20.0-rc.0"),
            HEREDOC_TERMINATOR_AFTER_CONTENT_IS_CONTENT to ("1.11.4" to "1.12.0-rc.0"),
            TYPE_OPERATOR_AFTER_END to ("1.11.4" to "1.12.0-rc.0"),
            SIGN_KEYWORD_KEY_AFTER_CALL to ("1.11.4" to "1.12.0-rc.0"),
            STEP_ATOM to ("1.11.4" to "1.12.0-rc.0"),
            BASED_NUMBER_CONTINUES_INTO_DIGITS to ("1.11.4" to "1.12.0-rc.0"),
            POWER_OPERATOR to ("1.12.3" to "1.13.0-rc.0"),
            DOT_KEYWORD_KEY to ("1.12.3" to "1.13.0-rc.0"),
            CALL_AND_ELLIPSIS_MAP_ENTRIES to ("1.12.3" to "1.13.0-rc.0"),
            UNARY_OPERATOR_REFERENCE to ("1.12.3" to "1.13.0-rc.0"),
            GRAPHEME_CLUSTER_CRASH_IN_QUOTED_CALL_NAME to ("1.12.3" to "1.13.0-rc.0"),
            NULLARY_RANGE to ("1.13.4" to "1.14.0-rc.0"),
            MULTI_LETTER_SIGIL_NAMES to ("1.14.5" to "1.15.0-rc.0"),
            DIGITS_IN_SIGIL_NAMES to ("1.16.3" to "1.17.0-rc.0"),
            MAP_ENTRY_WITHOUT_ASSOCIATION to ("1.16.3" to "1.17.0-rc.0"),
            ESCAPED_NEWLINE_BEFORE_ARITY to ("1.19.5" to "1.20.0-rc.0"),
            HEXADECIMAL_ESCAPE_NEEDS_TWO_DIGITS to ("1.19.5" to "1.20.0-rc.0"),
            BIDI_CHARACTERS_REJECTED to ("1.13.0-rc.0" to "1.13.0-rc.1"),
            MIXED_SCRIPT_BY_UNDERSCORE_CHUNK to ("1.17.3" to "1.18.0-rc.0"),
            LINE_BREAKS_REJECTED_IN_COMMENTS to ("1.19.0-rc.0" to "1.19.0-rc.1"),
            LINE_BREAKS_REJECTED_IN_QUOTED_TEXT to ("1.19.5" to "1.20.0-rc.0"),
            ESCAPE_ERRORS_NAME_THE_INVALID_CHARACTER to ("1.11.4" to "1.12.0-rc.0"),
            ALIAS_ERROR_COVERS_PUNCTUATION to ("1.13.4" to "1.14.0-rc.0"),
            NUMBER_ERROR_QUOTES_THE_CHARACTER to ("1.13.4" to "1.14.0-rc.0"),
            HEREDOC_OPENING_ERROR_SAYS_OPENING to ("1.15.0-rc.1" to "1.15.0-rc.2"),
            MIXED_SCRIPT_GUIDANCE_REQUIRES_UNDERSCORES to ("1.17.3" to "1.18.0-rc.0"),
        )

        assertEquals(entries.filter { it.sinceElixir != null }.toSet(), boundaries.keys)

        for ((feature, tags) in boundaries) {
            val (without, with) = tags
            assertFalse("$feature on $without", feature.isSufficient(ElixirLanguageLevel.of(without)))
            assertTrue("$feature on $with", feature.isSufficient(ElixirLanguageLevel.of(with)))
        }
    }

    fun testEachRemovedFeatureStopsAtTheFirstTagThatRemovedIt() {
        val removals = mapOf(
            DECIMAL_NUMBER_ENDS_BEFORE_WORD to ("1.11.4" to "1.12.0-rc.0"),
            SOLITARY_UNARY_WRAPPED_IN_EVERY_BLOCK to ("1.14.5" to "1.15.0-rc.0"),
            ENCLOSING_PARENS_MERGE_BLOCK_METADATA to ("1.16.3" to "1.17.0-rc.0"),
            GRAPHEME_CLUSTER_CRASH_IN_QUOTED_CALL_NAME to ("1.17.3" to "1.18.0-rc.0"),
        )

        assertEquals(entries.filter { it.removedInElixir != null }.toSet(), removals.keys)

        for ((feature, tags) in removals) {
            val (last, removed) = tags
            assertTrue("$feature on $last", feature.isSufficient(ElixirLanguageLevel.of(last)))
            assertFalse("$feature on $removed", feature.isSufficient(ElixirLanguageLevel.of(removed)))
        }
    }

    fun testEachOtpFeatureAppliesFromTheOtpReleaseThatShippedIt() {
        val boundaries = mapOf(
            MAYBE_RESERVED to ("26.2.5.21" to "27.0-rc1"),
        )

        assertEquals(entries.filter { it.sinceOtp != null }.toSet(), boundaries.keys)

        for ((feature, releases) in boundaries) {
            val (without, with) = releases
            assertFalse("$feature on OTP $without", feature.isSufficient(ElixirLanguageLevel.of("1.18.4", without)))
            assertTrue("$feature on OTP $with", feature.isSufficient(ElixirLanguageLevel.of("1.18.4", with)))
        }
    }

    /** An OTP that cannot be determined is taken as the newest, as an Elixir version that cannot be is. */
    fun testAnUnknownOtpHasEveryOtpFeature() {
        for (feature in entries.filter { it.sinceOtp != null }) {
            assertTrue("$feature", feature.isSufficient(ElixirLanguageLevel.of("1.18.4", null)))
        }
    }

    /** A pre-release comes before its release, so a later minor's first pre-release keeps what earlier minors added. */
    fun testAPreReleaseOfALaterMinorKeepsEarlierFeatures() {
        assertTrue(STEP_OPERATOR.isSufficient(ElixirLanguageLevel.of("1.13.0-rc.0")))
        assertFalse(LINE_METADATA_ON_BLOCK.isSufficient(ElixirLanguageLevel.of("1.19.5")))
        assertTrue(LINE_METADATA_ON_BLOCK.isSufficient(ElixirLanguageLevel.of("1.20.0-rc.0")))
    }

    fun testEveryWindowOpensBeforeItCloses() {
        for (feature in entries) {
            val since = feature.sinceElixir ?: continue
            val removedIn = feature.removedInElixir ?: continue
            assertTrue("$feature", removedIn > since)
        }
    }

    fun testIsAvailableReadsTheElementsLanguageLevel() {
        val file = myFixture.configureByText("available.ex", "x")

        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, ElixirLanguageLevel.of("1.11.4"))
            assertFalse(ElixirLanguageLevelResolver.isAvailable(STEP_OPERATOR, file))

            ElixirLanguageLevelResolver.overrideLanguageLevel(project, ElixirLanguageLevel.of("1.12.0-rc.0"))
            assertTrue(ElixirLanguageLevelResolver.isAvailable(STEP_OPERATOR, file))
        } finally {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        }
    }
}
