package org.elixir_lang.beam.decompiler

import junit.framework.TestCase

class ParameterTextTest : TestCase() {
    fun testACommentIsDropped() =
        assertEquals("{a, b}", ParameterText.normalised("{a, # the first\n b}"))

    fun testWhitespaceBetweenTokensIsOneSpace() =
        assertEquals("x \\\\ nil", ParameterText.normalised("x   \\\\ \t nil"))

    fun testANewlineBetweenTokensIsOneSpace() =
        assertEquals("[a, b]", ParameterText.normalised("[a,\n      b]"))

    fun testLeadingAndTrailingWhitespaceIsDropped() =
        assertEquals("a", ParameterText.normalised("  a \n"))

    fun testATokenWithNoSeparatorIsCopiedAsWritten() =
        assertEquals("{a,b}", ParameterText.normalised("{a,b}"))

    fun testAStringIsCopiedUnchanged() =
        assertEquals("x \\\\ \"a   b\"", ParameterText.normalised("x \\\\ \"a   b\""))

    fun testACharlistIsCopiedUnchanged() =
        assertEquals("x \\\\ 'a   b'", ParameterText.normalised("x \\\\ 'a   b'"))

    fun testASigilIsCopiedUnchanged() =
        assertEquals("x \\\\ ~s(a   b)", ParameterText.normalised("x \\\\ ~s(a   b)"))

    fun testAHashInsideAStringIsNotAComment() =
        assertEquals("x \\\\ \"# not\"", ParameterText.normalised("x \\\\ \"# not\""))

    fun testAHeredocIsCopiedUnchanged() {
        val heredoc = "x \\\\ \"\"\"\n  hello\n  \"\"\""

        assertEquals(heredoc, ParameterText.normalised(heredoc))
    }

    fun testASigilHeredocIsCopiedUnchanged() {
        val heredoc = "x \\\\ ~S\"\"\"\n  hello\n  \"\"\""

        assertEquals(heredoc, ParameterText.normalised(heredoc))
    }

    fun testAnEscapedDelimiterInAHeredocDoesNotEndIt() {
        val heredoc = "x \\\\ \"\"\"\n  a \\\"\"\" b\n  c\n  \"\"\""

        assertEquals(heredoc, ParameterText.normalised(heredoc))
    }

    fun testAnEscapedNewlineInAStringIsCopiedUnchanged() {
        val string = "x \\\\ \"a\\\nb\""

        assertEquals(string, ParameterText.normalised(string))
    }

    fun testADefaultIsFoundAtTheTopLevelOnly() {
        assertEquals("x", ParameterText.withoutDefault("x \\\\ nil"))
        assertNull(ParameterText.withoutDefault("{a, b}"))
        assertNull(ParameterText.withoutDefault("[a \\\\ 1]"))
    }

    /** `do:` as a keyword key opens no block, so it leaves the depth of the `\\` after it alone. */
    fun testAKeywordKeyIsNotABlock() {
        assertEquals("[do: body]", ParameterText.withoutDefault("[do: body] \\\\ [do: nil]"))
        assertEquals("opts", ParameterText.withoutDefault("opts \\\\ [do: nil]"))
    }

    fun testAHeadWithOneDefaultCoversTheArityBelow() {
        assertEquals(listOf("q"), ParameterText.covered(listOf("q", "x \\\\ nil"), 1))
        assertEquals(listOf("q", "x"), ParameterText.covered(listOf("q", "x \\\\ nil"), 2))
    }

    /** Elixir fills the last defaults first, so `f(a \\ 1, b \\ 2, c)` called with two arguments binds `a` and `c`. */
    fun testAHeadWithTwoDefaultsDropsTheLastDefaultsFirst() {
        val head = listOf("a \\\\ 1", "b \\\\ 2", "c")

        assertEquals(listOf("c"), ParameterText.covered(head, 1))
        assertEquals(listOf("a", "c"), ParameterText.covered(head, 2))
        assertEquals(listOf("a", "b", "c"), ParameterText.covered(head, 3))
    }

    fun testADefaultInTheMiddleDropsFromTheLastDefault() {
        val head = listOf("a", "b \\\\ 1", "c", "d \\\\ 2")

        assertEquals(listOf("a", "b", "c", "d"), ParameterText.covered(head, 4))
        assertEquals(listOf("a", "b", "c"), ParameterText.covered(head, 3))
        assertEquals(listOf("a", "c"), ParameterText.covered(head, 2))
    }

    /** An arity the head cannot reach drops as many of the last defaults as there are, and no more. */
    fun testReachingAnArityOutOfReachDropsAsManyDefaultsAsThereAre() {
        val head = listOf("a", "b \\\\ 1", "c", "d \\\\ 2")

        assertEquals(listOf("a", "c"), ParameterText.reaching(head, 1))
        assertEquals(listOf("a", "b", "c", "d"), ParameterText.reaching(head, 5))
        assertEquals(listOf("a", "c"), ParameterText.reaching(head, 2))
    }

    fun testAnArityOutOfReachIsNull() {
        val head = listOf("a", "b \\\\ 1", "c", "d \\\\ 2")

        assertNull(ParameterText.covered(head, 1))
        assertNull(ParameterText.covered(head, 5))
    }
}
