package org.elixir_lang.inspection

import junit.framework.TestCase

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

    fun testAnOpenArityIsPrintedWithAPlus() {
        assertEquals("foo/1+", DidYouMean.Entry("foo", 1, open = true).toString())
    }

    fun testANameIsPrintedAsTheCompilerPrintsIt() {
        assertEquals("\"my fun\"/2", DidYouMean.Entry("my fun", 2).toString())
    }
}
