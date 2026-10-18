package org.elixir_lang.inspection

import junit.framework.TestCase
import org.elixir_lang.language_level.ElixirLanguageLevel

/** The compiler's `did_you_mean/2` order and cut: nearest first, five at most, then by name. */
class DidYouMeanTest : TestCase() {
    fun testTheNearestFiveAreKept() {
        val entries = (1..7).map { DidYouMean.Entry("n$it", 1, distance = 1.0 - it / 100.0) }

        assertEquals(listOf("n1", "n2", "n3", "n4", "n5"), DidYouMean.suggestions(entries).map(DidYouMean.Entry::name))
    }

    fun testEqualDistancesKeepTheirInputOrderWhereTheCutFalls() {
        val entries = listOf("d", "c", "b", "a", "f", "e").map { DidYouMean.Entry(it, 1) }

        assertEquals(listOf("a", "b", "c", "d", "f"), DidYouMean.suggestions(entries).map(DidYouMean.Entry::name))
    }

    fun testTheKeptAreOrderedByNameAndArityKeepsItsInputOrder() {
        val entries = listOf(
            DidYouMean.Entry("b", 3),
            DidYouMean.Entry("a", 2),
            DidYouMean.Entry("b", 1),
            DidYouMean.Entry("a", 1)
        )

        assertEquals(listOf("a/2", "a/1", "b/3", "b/1"), DidYouMean.suggestions(entries).map(DidYouMean.Entry::toString))
    }

    fun testFewerThanFiveAreAllKept() {
        assertEquals(2, DidYouMean.suggestions(listOf(DidYouMean.Entry("a", 1), DidYouMean.Entry("b", 1))).size)
    }

    /** Each value is `String.jaro_distance/2` on Elixir 1.20.4 on OTP 29. */
    fun testTheDistanceIsTheCompilersJaroDistance() {
        assertEquals(
            listOf(0.8222222222222223, 0.0, 1.0, 0.0, 0.0, 0.7579365079365079, 0.9166666666666666),
            listOf(
                DidYouMean.distance("Dwayne", "Duane", LEVEL),
                DidYouMean.distance("even", "odd", LEVEL),
                DidYouMean.distance("same", "same", LEVEL),
                DidYouMean.distance("", "abc", LEVEL),
                DidYouMean.distance("abc", "", LEVEL),
                DidYouMean.distance("to_list", "to_string", LEVEL),
                DidYouMean.distance("snoc", "sno", LEVEL)
            )
        )
    }

    /** `cafe` and `cafe` + U+0301 share three of four graphemes, where counting code points would give five. */
    fun testAGraphemeOfSeveralCodePointsCountsOnce() {
        assertEquals(0.8333333333333334, DidYouMean.distance("café", "cafe", LEVEL))
        assertEquals(0.8666666666666667, DidYouMean.distance("caféx", "caféy", LEVEL))
    }

    /** Characters match only inside the window, whose edges are excluded. */
    fun testTransposedGraphemesOutsideTheWindowDoNotMatch() {
        assertEquals(0.0, DidYouMean.distance("ab", "ba", LEVEL))
        assertEquals(0.0, DidYouMean.distance("éa", "aé", LEVEL))
    }

    fun testAnOpenArityIsPrintedWithAPlus() {
        assertEquals("foo/1+", DidYouMean.Entry("foo", 1, open = true).toString())
    }

    fun testANameIsPrintedAsTheCompilerPrintsIt() {
        assertEquals("\"my fun\"/2", DidYouMean.Entry("my fun", 2).toString())
    }

    private companion object {
        val LEVEL: ElixirLanguageLevel = ElixirLanguageLevel.of("1.20.4", "29.0.6")
    }
}
