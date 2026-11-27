package org.elixir_lang.formatter

import org.elixir_lang.PlatformTestCase

/**
 * A reformat keeps what Elixir keeps in a heredoc's value: only spaces and tabs, up to the closing delimiter's
 * indentation, are the heredoc's indentation. A vertical tab or a form feed is text, whatever [Character.isWhitespace] says.
 */
class HeredocLineWhitespaceFormattingTest : PlatformTestCase() {
    fun testAVerticalTabAfterTheIndentationIsKept() = assertFormatted("  \u000Bb")

    fun testAFormFeedAfterTheIndentationIsKept() = assertFormatted("  \u000Cb")

    /** The formatter moves a line indented less than the closing delimiter to the delimiter's indentation, which leaves its value as it was. */
    fun testAVerticalTabAtTheStartOfAnOutdentedLineIsKept() = assertFormatted("\u000Bb", "  \u000Bb")

    fun testAFormFeedAtTheStartOfAnOutdentedLineIsKept() = assertFormatted("\u000Cb", "  \u000Cb")

    fun testAnOutdentedLineIsMovedToTheClosingDelimitersIndentation() = assertFormatted("b", "  b")

    fun testSpacesAreStillTheHeredocsIndentation() = assertFormatted("  b")

    /** The heredoc's lines under a closing delimiter indented by 2, in a function body, are already as the formatter lays them out. */
    private fun assertFormatted(line: String, formattedLine: String = line) {
        val source = "def f do\n  x = \"\"\"\n  a\n$line\n  c\n  \"\"\"\nend\n"
        val expected = "def f do\n  x = \"\"\"\n  a\n$formattedLine\n  c\n  \"\"\"\nend\n"

        assertEquals(expected, reformattedWithoutMixFormat("heredoc.ex", source))
    }
}
