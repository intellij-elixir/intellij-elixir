package org.elixir_lang.psi

import junit.framework.TestCase

/** [heredocLineTextStart] is `elixir_tokenizer:trim_space/2`: up to the indentation of leading spaces and tabs. */
class HeredocLineTextTest : TestCase() {
    fun testASpaceIndentationIsDropped() = assertStart(2, "  a\n", 2)

    fun testATabIsIndentation() = assertStart(2, " \ta\n", 2)

    fun testNoMoreThanTheIndentationIsDropped() = assertStart(2, "    a\n", 2)

    fun testALineIndentedLessKeepsTheRest() = assertStart(1, " a\n", 2)

    fun testAnUnindentedLineStartsAtOnce() = assertStart(0, "a\n", 2)

    fun testABlankLineStartsAtItsNewline() = assertStart(0, "\n", 2)

    fun testAWhitespaceLineShorterThanTheIndentationStartsAtItsNewline() = assertStart(1, " \n", 2)

    fun testAFormFeedIsText() = assertStart(1, " \u000Ca\n", 2)

    fun testNothingIsDroppedUnderNoIndentation() = assertStart(0, "  a\n", 0)

    fun testALineIsReadFromItsStart() = assertEquals(3, heredocLineTextStart("ab\n   c\n", 3, 3))

    fun testAnUnterminatedLineStopsAtTheEndOfTheText() = assertStart(1, " ", 2)

    private fun assertStart(expected: Int, line: String, indentation: Int) =
        assertEquals(expected, heredocLineTextStart(line, 0, indentation))
}
