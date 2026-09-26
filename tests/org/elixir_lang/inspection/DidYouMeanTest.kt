package org.elixir_lang.inspection

import junit.framework.TestCase

/** The values are the examples in the docs of OTP's `string:jaro_similarity/2` and Elixir's `String.jaro_distance/2`. */
class DidYouMeanTest : TestCase() {
    fun testJaroDistanceAgreesWithOtp() {
        assertEquals(1.0, DidYouMean.jaroDistance("ditto", "ditto"))
        assertEquals(0.0, DidYouMean.jaroDistance("foo", "bar"))
        assertEquals(0.8690476190476191, DidYouMean.jaroDistance("michelle", "michael"))
        assertEquals(0.5317460317460317, DidYouMean.jaroDistance("Édouard", "Claude"))
    }

    fun testJaroDistanceAgreesWithElixir() {
        assertEquals(0.8222222222222223, DidYouMean.jaroDistance("Dwayne", "Duane"))
        assertEquals(0.0, DidYouMean.jaroDistance("even", "odd"))
        assertEquals(1.0, DidYouMean.jaroDistance("same", "same"))
    }

    /** Half a transposition counts, as OTP's `T/2` is a float. */
    fun testATranspositionCountsHalf() {
        assertEquals(0.9444444444444445, DidYouMean.jaroDistance("MARTHA", "MARHTA"))
    }

    fun testSuggestionsAreEveryArityOfANearName() {
        assertEquals(
            listOf("snoc/2", "snoc/3"),
            DidYouMean.suggestions("sno", listOf("snoc" to 3, "unrelated" to 1, "snoc" to 2))
        )
    }

    /** The nearest five, then sorted by name: `abcdzz` is near enough, but sixth. */
    fun testSuggestionsAreTheNearestFiveByName() {
        assertEquals(
            listOf("abcdef/1", "abcdeg/1", "abcdex/1", "abcdey/1", "abcdez/1"),
            DidYouMean.suggestions(
                "abcdef",
                listOf(
                    "abcdzz" to 1, "abcdez" to 1, "abcxyz" to 1, "abcdey" to 1, "abcdef" to 1, "abcdex" to 1,
                    "abcdeg" to 1
                )
            )
        )
    }

    fun testNothingNearSuggestsNothing() {
        assertEquals(emptyList<String>(), DidYouMean.suggestions("even", listOf("odd" to 1)))
    }
}
