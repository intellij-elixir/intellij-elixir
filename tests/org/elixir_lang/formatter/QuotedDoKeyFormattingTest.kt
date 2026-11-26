package org.elixir_lang.formatter

import org.elixir_lang.PlatformTestCase

/**
 * A one-liner's `do:` body stays on its line, and a quoted `"do":` is that same key. The expected layouts are what
 * `mix format` gives, with the key left as written: `mix format` also rewrites `"do":` to `do:`.
 */
class QuotedDoKeyFormattingTest : PlatformTestCase() {
    fun testDoKeyListBody() = assertFormatted("if x, do: [a: 1, b: 2]\n")

    fun testQuotedDoKeyListBody() = assertFormatted("if x, \"do\": [a: 1, b: 2]\n")

    fun testDoKeyMapUpdateBody() = assertFormatted("if x, do: %{y | a: 1, b: 2}\n")

    fun testQuotedDoKeyMapUpdateBody() = assertFormatted("if x, \"do\": %{y | a: 1, b: 2}\n")

    private fun assertFormatted(source: String) = assertEquals(source, reformattedWithoutMixFormat("one_liner.ex", source))
}
