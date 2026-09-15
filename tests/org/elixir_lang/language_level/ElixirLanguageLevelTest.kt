package org.elixir_lang.language_level

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The thresholds are the whole design, and every other test exercises them only indirectly - a
 * misplaced boundary shows up as a quoting failure on one CI leg rather than as a wrong mapping.
 * So they are pinned here, one assertion per side of each boundary.
 *
 * Each expected value was confirmed against the reference implementation; see [ElixirLanguageLevel] for
 * the Elixir commit and release behind each one.
 */
class ElixirLanguageLevelTest {
    @Test
    fun `a nullary range is accepted from 1_14_0`() {
        assertEquals(ElixirLanguageLevel.V1_13, ElixirLanguageLevel.of("1.13.4"))
        assertEquals(ElixirLanguageLevel.V1_14, ElixirLanguageLevel.of("1.14.0"))
        assertEquals(ElixirLanguageLevel.V1_14, ElixirLanguageLevel.of("1.14.5"))
    }

    /** Both 1.12.0 divergences share this threshold, so it is asserted on the versions either side. */
    @Test
    fun `an interpolating sigil keeps an escaped newline from 1_12_0`() {
        assertEquals(ElixirLanguageLevel.V1_11, ElixirLanguageLevel.of("1.11.4"))
        assertEquals(ElixirLanguageLevel.V1_12, ElixirLanguageLevel.of("1.12.0"))
        assertEquals(ElixirLanguageLevel.V1_12, ElixirLanguageLevel.of("1.12.3"))
    }

    /** 1.12.3 is the last 1.12 patch, 1.13.0 the first release carrying the change. */
    @Test
    fun `an escaped sigil heredoc terminator is unescaped from 1_13_0`() {
        assertEquals(ElixirLanguageLevel.V1_12, ElixirLanguageLevel.of("1.12.3"))
        assertEquals(ElixirLanguageLevel.V1_13, ElixirLanguageLevel.of("1.13.0"))
        assertEquals(ElixirLanguageLevel.V1_13, ElixirLanguageLevel.of("1.13.4"))
    }

    @Test
    fun `from_brackets on a bracketed expression starts at 1_15_0`() {
        assertEquals(ElixirLanguageLevel.V1_14, ElixirLanguageLevel.of("1.14.5"))
        assertEquals(ElixirLanguageLevel.V1_15, ElixirLanguageLevel.of("1.15.0"))
        assertEquals(ElixirLanguageLevel.V1_15, ElixirLanguageLevel.of("1.15.8"))
    }

    @Test
    fun `from_interpolation starts at 1_16_0`() {
        assertEquals(ElixirLanguageLevel.V1_15, ElixirLanguageLevel.of("1.15.8"))
        assertEquals(ElixirLanguageLevel.V1_16_0, ElixirLanguageLevel.of("1.16.0"))
        assertEquals(ElixirLanguageLevel.V1_16_0, ElixirLanguageLevel.of("1.16.1"))
    }

    @Test
    fun `from_brackets on every bracket form starts at 1_16_2`() {
        assertEquals(ElixirLanguageLevel.V1_16_0, ElixirLanguageLevel.of("1.16.1"))
        assertEquals(ElixirLanguageLevel.V1_16_2, ElixirLanguageLevel.of("1.16.2"))
        assertEquals(ElixirLanguageLevel.V1_16_2, ElixirLanguageLevel.of("1.16.3"))
    }

    @Test
    fun `ellipsis becomes a nullary call at 1_17_0`() {
        assertEquals(ElixirLanguageLevel.V1_16_2, ElixirLanguageLevel.of("1.16.3"))
        assertEquals(ElixirLanguageLevel.V1_17, ElixirLanguageLevel.of("1.17.0"))
        assertEquals(ElixirLanguageLevel.V1_17, ElixirLanguageLevel.of("1.17.3"))
    }

    @Test
    fun `a quoted remote call name is unescaped from 1_18_0`() {
        assertEquals(ElixirLanguageLevel.V1_17, ElixirLanguageLevel.of("1.17.3"))
        assertEquals(ElixirLanguageLevel.V1_18, ElixirLanguageLevel.of("1.18.0"))
        assertEquals(ElixirLanguageLevel.V1_18, ElixirLanguageLevel.of("1.18.4"))
    }

    @Test
    fun `invalid escapes and encodings are errors rather than raises from 1_19_0`() {
        assertEquals(ElixirLanguageLevel.V1_18, ElixirLanguageLevel.of("1.18.4"))
        assertEquals(ElixirLanguageLevel.V1_19, ElixirLanguageLevel.of("1.19.0"))
        assertEquals(ElixirLanguageLevel.V1_19, ElixirLanguageLevel.of("1.19.5"))
    }

    @Test
    fun `do-block and empty-file blocks gain line metadata at 1_20_0`() {
        assertEquals(ElixirLanguageLevel.V1_19, ElixirLanguageLevel.of("1.19.5"))
        assertEquals(ElixirLanguageLevel.V1_20, ElixirLanguageLevel.of("1.20.0"))
        assertEquals(ElixirLanguageLevel.V1_20, ElixirLanguageLevel.of("1.20.2"))
    }

    @Test
    fun `versions after the newest threshold resolve to it`() {
        assertEquals(ElixirLanguageLevel.V1_20, ElixirLanguageLevel.of("1.20.3"))
        assertEquals(ElixirLanguageLevel.V1_20, ElixirLanguageLevel.of("2.0.0"))
    }

    /** Minor is compared numerically, so 1.9 must not sort above 1.15 the way strings would. */
    @Test
    fun `version parts are compared as numbers`() {
        assertEquals(ElixirLanguageLevel.V1_11, ElixirLanguageLevel.of("1.9.4"))
        assertEquals(ElixirLanguageLevel.V1_11, ElixirLanguageLevel.of("1.2.6"))
    }

    /** A missing patch is 0, which is what puts a bare "1.16" below the 1.16.2 threshold. */
    @Test
    fun `an absent patch counts as zero`() {
        assertEquals(ElixirLanguageLevel.V1_16_0, ElixirLanguageLevel.of("1.16"))
        assertEquals(ElixirLanguageLevel.V1_15, ElixirLanguageLevel.of("1.15"))
        assertEquals(ElixirLanguageLevel.V1_14, ElixirLanguageLevel.of("1.14"))
    }

    /** mise reports Elixir versions with the OTP build tag attached. */
    @Test
    fun `a build tag is ignored`() {
        assertEquals(ElixirLanguageLevel.V1_13, ElixirLanguageLevel.of("1.13.4-otp-24"))
        assertEquals(ElixirLanguageLevel.V1_16_2, ElixirLanguageLevel.of("1.16.3-otp-26"))
        assertEquals(ElixirLanguageLevel.V1_19, ElixirLanguageLevel.of("1.19.5-otp-28"))
    }

    /** The SDK's own version string, used when the canonical version has not been detected yet. */
    @Test
    fun `a whole SDK version string resolves on its embedded version`() {
        assertEquals(ElixirLanguageLevel.V1_13, ElixirLanguageLevel.of("mise Elixir 1.13.4 (OTP 24)"))
        assertEquals(ElixirLanguageLevel.V1_17, ElixirLanguageLevel.of("Elixir 1.17.3 (OTP 27)"))
        assertEquals(
            ElixirLanguageLevel.V1_13,
            ElixirLanguageLevel.of("mise Elixir 1.13.4-otp-24 (Erlang 24.3.4.6)")
        )
    }

    @Test
    fun `a version-less string falls back`() {
        assertEquals(ElixirLanguageLevel.FALLBACK, ElixirLanguageLevel.of(null))
        assertEquals(ElixirLanguageLevel.FALLBACK, ElixirLanguageLevel.of(""))
        assertEquals(ElixirLanguageLevel.FALLBACK, ElixirLanguageLevel.of("Elixir at /opt/elixir"))
    }

    /**
     * The fallback is deliberately the newest language level, not the oldest. Asserted against
     * `entries.last()` rather than a literal so adding a threshold moves it rather than breaking
     * here - but asserted at all, because flipping it to the oldest would silently change what
     * every module-less file quotes as.
     */
    @Test
    fun `the fallback is the newest language level`() {
        assertEquals(ElixirLanguageLevel.entries.last(), ElixirLanguageLevel.FALLBACK)
    }

    /** [ElixirLanguageLevel.of] reads each level's first release, so a level added between two others needs no threshold of its own. */
    @Test
    fun `each level's first release resolves to that level`() {
        for (level in ElixirLanguageLevel.entries) {
            assertEquals(level.firstRelease, level, ElixirLanguageLevel.of(level.firstRelease))
        }
    }

    @Test
    fun `levels are declared in release order`() {
        val releases = ElixirLanguageLevel.entries.map { level -> level.firstRelease.split('.').map(String::toInt) }

        for ((earlier, later) in releases.zipWithNext()) {
            assertTrue("$earlier before $later", compareValuesBy(earlier, later, { it[0] }, { it[1] }, { it[2] }) < 0)
        }
    }

    @Test
    fun `each divergence is on from its own threshold and stays on`() {
        assertEquals(
            listOf(false, true, true, true, true, true, true, true, true, true, true),
            ElixirLanguageLevel.entries.map { it.keepsEscapedNewlineInExtractedBuffer }
        )
        assertEquals(
            listOf(false, true, true, true, true, true, true, true, true, true, true),
            ElixirLanguageLevel.entries.map { it.countsEscapedNewlineInLiteralSigilLine }
        )
        assertEquals(
            listOf(false, false, false, false, false, false, false, false, false, true, true),
            ElixirLanguageLevel.entries.map { it.countsNewlineInCharacter }
        )
        assertEquals(
            listOf(false, false, false, true, true, true, true, true, true, true, true),
            ElixirLanguageLevel.entries.map { it.normalizesIdentifiers }
        )
        assertEquals(
            listOf(false, false, true, true, true, true, true, true, true, true, true),
            ElixirLanguageLevel.entries.map { it.putsRemoteCallOnNameLine }
        )
        assertEquals(
            listOf(false, false, false, false, false, false, false, false, true, true, true),
            ElixirLanguageLevel.entries.map { it.unescapesQuotedRemoteCallName }
        )
        assertEquals(
            listOf(false, true, true, true, true, true, true, true, true, true, true),
            ElixirLanguageLevel.entries.map { it.emitsEmptyLeadingHeredocSegment }
        )
        assertEquals(
            listOf(false, false, true, true, true, true, true, true, true, true, true),
            ElixirLanguageLevel.entries.map { it.unescapesSigilHeredocTerminator }
        )
        assertEquals(
            listOf(false, false, false, false, true, true, true, true, true, true, true),
            ElixirLanguageLevel.entries.map { it.emitsFromBracketsOnBracketedExpression }
        )
        assertEquals(
            listOf(false, false, false, false, false, true, true, true, true, true, true),
            ElixirLanguageLevel.entries.map { it.emitsFromInterpolation }
        )
        assertEquals(
            listOf(false, false, false, false, false, false, true, true, true, true, true),
            ElixirLanguageLevel.entries.map { it.emitsFromBracketsOnEveryBracketForm }
        )
        assertEquals(
            listOf(false, false, false, false, false, false, false, true, true, true, true),
            ElixirLanguageLevel.entries.map { it.quotesEllipsisAsNullaryCall }
        )
        assertEquals(
            listOf(false, false, false, false, false, false, false, true, true, true, true),
            ElixirLanguageLevel.entries.map { it.quotesAmbiguousDualOperatorAsCall }
        )
        assertEquals(
            listOf(false, false, false, false, false, false, false, false, false, false, true),
            ElixirLanguageLevel.entries.map { it.emitsLineMetadataOnBlock }
        )
        assertEquals(
            listOf(false, true, true, true, true, true, true, true, true, true, true),
            ElixirLanguageLevel.entries.map { it.hasStepOperator }
        )
        assertEquals(
            listOf(false, false, false, false, false, false, false, false, false, false, true),
            ElixirLanguageLevel.entries.map { it.countsEscapedNewlineAsSpace }
        )
    }

    /**
     * Shares [ElixirLanguageLevel.V1_17] with the ellipsis and ambiguous-operator changes, but reads
     * downwards like [ElixirLanguageLevel.wrapsSolitaryUnaryNotInEveryBlock] - on below the threshold,
     * off from it - since 1.17.0 *dropped* the merge rather than adding it.
     */
    @Test
    fun `merging an enclosing paren's own metadata onto an already-block child is on only below 1_17`() {
        assertEquals(
            listOf(true, true, true, true, true, true, true, false, false, false, false),
            ElixirLanguageLevel.entries.map { it.mergesEnclosingParenMetadataOntoBlock }
        )
        assertEquals(true, ElixirLanguageLevel.of("1.16.3").mergesEnclosingParenMetadataOntoBlock)
        assertEquals(false, ElixirLanguageLevel.of("1.17.0").mergesEnclosingParenMetadataOntoBlock)
        assertEquals(false, ElixirLanguageLevel.FALLBACK.mergesEnclosingParenMetadataOntoBlock)
    }

    /**
     * Shares [ElixirLanguageLevel.V1_17] with the ellipsis change, so the boundary is asserted on the
     * versions either side of it as well as across the constants - a threshold that silently moved to
     * 1.16.2 would still pass the list above by matching the ellipsis row.
     */
    @Test
    fun `the ambiguous dual operator call is on from 1_17_0`() {
        assertEquals(false, ElixirLanguageLevel.of("1.16.3").quotesAmbiguousDualOperatorAsCall)
        assertEquals(true, ElixirLanguageLevel.of("1.17.0").quotesAmbiguousDualOperatorAsCall)
        assertEquals(true, ElixirLanguageLevel.FALLBACK.quotesAmbiguousDualOperatorAsCall)
    }

    /**
     * The one predicate that reads downwards: 1.15.0 narrowed the wrapper rather than adding it, so
     * it is on for the oldest language level and off from [ElixirLanguageLevel.V1_15]. Asserted across every
     * constant because getting the direction backwards is the easy mistake, and it would emit a
     * spurious `__block__` on every modern Elixir while looking like the other four predicates.
     */
    @Test
    fun `the unary block wrapper is on only below 1_15`() {
        assertEquals(
            listOf(true, true, true, true, false, false, false, false, false, false, false),
            ElixirLanguageLevel.entries.map { it.wrapsSolitaryUnaryNotInEveryBlock }
        )
        assertEquals(true, ElixirLanguageLevel.of("1.14.5").wrapsSolitaryUnaryNotInEveryBlock)
        assertEquals(false, ElixirLanguageLevel.of("1.15.0").wrapsSolitaryUnaryNotInEveryBlock)
        assertEquals(false, ElixirLanguageLevel.FALLBACK.wrapsSolitaryUnaryNotInEveryBlock)
    }
}
