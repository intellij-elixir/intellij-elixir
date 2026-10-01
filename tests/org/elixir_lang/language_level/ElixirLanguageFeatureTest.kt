package org.elixir_lang.language_level

import org.elixir_lang.junit.LightTestCase
import org.elixir_lang.language_level.ElixirLanguageFeature.*

/**
 * Each window is pinned here as the Elixir tags either side of it: the last tag without the behaviour and the first
 * with it, pre-releases included, so a boundary edited by mistake fails by name rather than as a quoting failure on
 * one CI leg. The tags are the first ones containing the commits each entry's KDoc cites.
 */
class ElixirLanguageFeatureTest : LightTestCase() {
    fun testEachFeatureAppliesFromTheFirstTagThatShippedIt() {
        val boundaries = mapOf(
            ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER to ("1.11.4" to "1.12.0-rc.0"),
            ESCAPED_NEWLINE_COUNTED_IN_LITERAL_SIGIL_LINE to ("1.11.4" to "1.12.0-rc.0"),
            EMPTY_LEADING_HEREDOC_SEGMENT to ("1.11.4" to "1.12.0-rc.0"),
            STEP_OPERATOR to ("1.11.4" to "1.12.0-rc.0"),
            REMOTE_CALL_ON_NAME_LINE to ("1.12.3" to "1.13.0-rc.0"),
            LAST_ON_ALIAS to ("1.12.3" to "1.13.0-rc.0"),
            UNESCAPED_SIGIL_HEREDOC_TERMINATOR to ("1.13.0-rc.0" to "1.13.0-rc.1"),
            NORMALIZED_IDENTIFIERS to ("1.13.4" to "1.14.0-rc.0"),
            FROM_BRACKETS_ON_BRACKETED_EXPRESSION to ("1.14.5" to "1.15.0-rc.0"),
            ADJACENT_CAPTURE_ARGUMENT to ("1.14.5" to "1.15.0-rc.0"),
            FROM_INTERPOLATION to ("1.15.8" to "1.16.0-rc.0"),
            FROM_BRACKETS_ON_EVERY_BRACKET_FORM to ("1.16.1" to "1.16.2"),
            ELLIPSIS_NULLARY_CALL to ("1.16.3" to "1.17.0-rc.0"),
            AMBIGUOUS_DUAL_OPERATOR_CALL to ("1.16.3" to "1.17.0-rc.0"),
            MAP_COLUMN_AT_PERCENT to ("1.16.3" to "1.17.0-rc.0"),
            UNESCAPED_QUOTED_REMOTE_CALL_NAME to ("1.17.3" to "1.18.0-rc.0"),
            ASSOC_ON_MAP_KEY to ("1.17.3" to "1.18.0-rc.0"),
            NEWLINE_COUNTED_IN_CHARACTER to ("1.19.0-rc.0" to "1.19.0-rc.1"),
            IN_OF_NOT_IN_ON_ITS_OWN_LINE to ("1.19.0-rc.0" to "1.19.0-rc.1"),
            LINE_METADATA_ON_BLOCK to ("1.19.5" to "1.20.0-rc.0"),
            ESCAPED_NEWLINE_AS_SPACE to ("1.19.5" to "1.20.0-rc.0"),
            HEREDOC_TERMINATOR_AFTER_CONTENT_IS_CONTENT to ("1.11.4" to "1.12.0-rc.0"),
            RESERVED_WORD_BEFORE_TYPE_OPERATOR to ("1.11.4" to "1.12.0-rc.0"),
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
            IMPORT_ONLY_SIGILS to ("1.12.3" to "1.13.0-rc.0"),
            IMPORT_ONLY_SIGILS_READS_SIGIL_NAMES to ("1.15.0-rc.1" to "1.15.0-rc.2"),
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
            HEREDOC_INDENTATION_IN_COLUMNS to ("1.11.4" to "1.12.0-rc.0"),
            CLUSTER_COLUMNS_IN_QUOTED_TEXT to ("1.12.3" to "1.13.0-rc.0"),
            DELIMITER_ON_QUOTED_ATOM to ("1.12.3" to "1.13.0-rc.0"),
            INDENTATION_ON_HEREDOC to ("1.12.3" to "1.13.0-rc.0"),
            ESCAPED_INTERPOLATION_COLUMNS to ("1.14.3" to "1.14.4"),
            END_OF_EXPRESSION_ON_LAST_EXPRESSION to ("1.16.3" to "1.17.0-rc.0"),
            PARENS_ON_PARENTHESIZED_EXPRESSION to ("1.17.3" to "1.18.0-rc.0"),
            DELIMITER_OF_SINGLE_QUOTED_ATOM to ("1.17.3" to "1.18.0-rc.0"),
            DELIMITER_ON_QUOTED_KEYWORD_KEY to ("1.17.3" to "1.18.0-rc.0"),
            CLOSING_FIRST_IN_PARENS to ("1.19.5" to "1.20.0-rc.0"),
            NEWLINES_AFTER_MATCH_OPERATOR to ("1.14.5" to "1.15.0-rc.0"),
            NEWLINES_ON_NOT_IN to ("1.19.0-rc.0" to "1.19.0-rc.1"),
            REARRANGED_UNARY_KEEPS_ITS_METADATA to ("1.19.6" to "1.20.0-rc.0"),
            CLOSING_ON_BRACKET_ACCESS to ("1.12.0-rc.1" to "1.12.0"),
            DELIMITER_ON_QUOTED_REMOTE_CALL to ("1.17.3" to "1.18.0-rc.0"),
            NESTED_PARENTHESES_DROP_INNER_METADATA to ("1.18.4" to "1.19.0-rc.0"),
            CLOSING_ON_EMPTY_MULTIPLE_ALIASES to ("1.19.0-rc.0" to "1.19.0-rc.1"),
            PIN_IN_MAP_KEY_PATTERN to ("1.13.4" to "1.14.0-rc.0"),
            ZERO_FLOAT_MATCH_WARNS to ("1.15.8" to "1.16.0-rc.0"),
            PARALLEL_MATCH to ("1.17.3" to "1.18.0-rc.0"),
            REPEATED_PATTERN_VARIABLE_WRITTEN_AT_NEXT_VERSION to ("1.17.3" to "1.18.0-rc.0"),
            UNDERSCORE_TAKES_VERSION to ("1.20.0-rc.4" to "1.20.0-rc.5"),
            UNDEFINED_VARIABLE_RAISES to ("1.14.5" to "1.15.0-rc.0"),
            MISPLACED_TYPE_AND_CONS_OPERATORS to ("1.14.5" to "1.15.0-rc.0"),
            CURSOR_RAISES to ("1.16.3" to "1.17.0-rc.0"),
            BITSTRING_SIZE_EXPANDED_AS_GUARD to ("1.13.4" to "1.14.0-rc.0"),
            PIN_IN_BITSTRING_SIZE to ("1.15.0-rc.0" to "1.15.0-rc.1"),
            BITSTRING_SIZE_IN_MAP_KEY_PATTERN to ("1.15.0-rc.0" to "1.15.0-rc.1"),
            HALF_FLOAT_SEGMENT to ("1.11.3" to "1.11.4"),
            PINNED_BINARY_SEGMENT_INFERS_SIZE to ("1.16.0-rc.0" to "1.16.0-rc.1"),
            PINNED_SEGMENT_INFERS_SIZE_ONLY_WHEN_SIZED to ("1.16.2" to "1.16.3"),
            BITSTRING_PATTERN_SEGMENT_VALIDATED to ("1.18.4" to "1.19.0-rc.0"),
            BITSTRING_SIZE_HIDES_ITS_OWN_SEGMENT to ("1.19.0-rc.0" to "1.19.0-rc.1"),
            BARE_SEGMENT_PASSES_BITSTRING_META to ("1.20.0-rc.4" to "1.20.0-rc.5"),
            STACKTRACE_REFUSED_IN_PATTERN to ("1.12.3" to "1.13.0-rc.0"),
            CATCH_WHEN_ARITY_CHECKED to ("1.17.3" to "1.18.0-rc.0"),
            GENERATOR_RIGHT_SIDE_SCOPED to ("1.12.3" to "1.13.0-rc.0"),
            WITH_OPTIONS_BEFORE_LAST_ARGUMENT to ("1.14.5" to "1.15.0-rc.0"),
            REMOTE_CAPTURE_REPORTED_AT_CALL to ("1.14.0-rc.0" to "1.14.0-rc.1"),
            CAPTURE_REPORTED_AT_CALL to ("1.15.8" to "1.16.0-rc.0"),
            CAPTURE_ARGUMENT_BELOW_ONE_IS_INVALID_ARITY to ("1.13.4" to "1.14.0-rc.0"),
            CLAUSES_TAKE_VERSION to ("1.20.0-rc.4" to "1.20.0-rc.5"),
            IMPLICIT_ALIAS_NEEDS_ELIXIR_MODULE to ("1.12.3" to "1.13.0-rc.0"),
            PATTERN_SEES_RIGHT_SIDE_ENV to ("1.12.3" to "1.13.0-rc.0"),
            ALIAS_AS_NIL_REJECTED to ("1.15.8" to "1.16.0-rc.0"),
            ALIAS_EXPANDS_ONE_STEP to ("1.15.8" to "1.16.0-rc.0"),
            IMPORT_OPTION_MISTAKES_WARN to ("1.14.5" to "1.15.0-rc.0"),
            ERLANG_IMPORT_DROPS_BEHAVIOUR_INFO to ("1.14.5" to "1.15.0-rc.0"),
            IMPORT_DISCARDS_SPECIAL_FORMS to ("1.16.3" to "1.17.0-rc.0"),
            IMPORT_VALIDATES_EXCEPT_FIRST to ("1.16.3" to "1.17.0-rc.0"),
            IMPORT_ONLY_MACROS_WITHOUT_INFO to ("1.16.3" to "1.17.0-rc.0"),
            INVALID_MULTI_ALIAS_BASE_RAISES to ("1.19.0-rc.0" to "1.19.0-rc.1"),
            CIRCULAR_MODULE_CHECKED_FIRST to ("1.14.5" to "1.15.0-rc.0"),
            SIGIL_FILTER_TOLERATES_ANY_NAME to ("1.20.0-rc.4" to "1.20.0-rc.5"),
            COMPILER_PARSES_COLUMNS to ("1.15.8" to "1.16.0-rc.0"),
            QUOTE_IMPORTS_EVERY_ARITY to ("1.13.4" to "1.14.0-rc.0"),
            QUOTE_KEEP_READS_LINE_OPTION to ("1.16.3" to "1.17.0-rc.0"),
            QUOTED_DEF_CONTEXT_SKIPS_GUARD to ("1.15.8" to "1.16.0-rc.0"),
            QUOTE_META_DROPS_COLUMN to ("1.15.8" to "1.16.0-rc.0"),
            UNQUOTE_SHALLOW_VALIDATED to ("1.17.3" to "1.18.0-rc.0"),
            QUOTE_BINDING_META_DROPS_COLUMN to ("1.19.0-rc.2" to "1.19.0"),
            UNQUOTE_VALIDATED_BY_UNQUOTE to ("1.20.0-rc.6" to "1.20.0"),
            QUOTE_IN_PATTERN_WITH_UNQUOTE_RAISES to ("1.20.1" to "1.20.2"),
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
            END_OF_EXPRESSION_ON_STAB_OPERATOR to ("1.16.3" to "1.17.0-rc.0"),
            GRAPHEME_CLUSTER_CRASH_IN_QUOTED_CALL_NAME to ("1.17.3" to "1.18.0-rc.0"),
            OPERATOR_ON_NOT_IN to ("1.11.4" to "1.12.0-rc.0"),
            KERNEL_TYPESPEC_REQUIRED_BY_DEFAULT to ("1.16.3" to "1.17.0-rc.0"),
            REPEATED_PATTERN_VARIABLE_WRITTEN_AT_NEXT_VERSION to ("1.20.0-rc.4" to "1.20.0-rc.5"),
            BITSTRING_LIST_OR_ATOM_SEGMENT_REJECTED to ("1.17.3" to "1.18.0-rc.0"),
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
            HALF_FLOAT_SEGMENT to ("23.3.4.20" to "24.0-rc1"),
            UNICODE_14_GRAPHEME_CLUSTERS to ("24.3.4.17" to "25.0-rc1"),
            UNICODE_15_GRAPHEME_CLUSTERS to ("26.0-rc1" to "26.0-rc2"),
            INDIC_CONJUNCT_GRAPHEME_CLUSTERS to ("28.0-rc1" to "28.0-rc2"),
            WIDER_INDIC_CONJUNCT_GRAPHEME_CLUSTERS to ("28.5.0.7" to "29.0-rc1"),
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
