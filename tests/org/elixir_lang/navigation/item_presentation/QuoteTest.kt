package org.elixir_lang.navigation.item_presentation

import org.elixir_lang.PlatformTestCase

/** `quote`'s item presentation names the `location:` and `unquote:` options it is given, one comma between them. */
class QuoteTest : PlatformTestCase() {
    fun testLocationAndUnquoteAreSeparatedByOneComma() =
        assertEquals("quote location: :keep, unquote: false", myFixture.quotePresentation("quote location: :keep, unquote: false do\n end"))

    fun testUnquoteAloneFollowsQuoteWithoutAComma() =
        assertEquals("quote unquote: false", myFixture.quotePresentation("quote unquote: false do\n end"))

    fun testLocationAlone() = assertEquals("quote location: :keep", myFixture.quotePresentation("quote location: :keep do\n end"))
}
