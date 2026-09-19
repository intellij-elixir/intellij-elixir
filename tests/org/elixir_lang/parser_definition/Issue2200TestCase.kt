package org.elixir_lang.parser_definition

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.elixir

class Issue2200TestCase : ParsingTestCase() {
    /**
     * `:..//` as a call argument, which reported as `<unmatched expression> expected, got ','` while
     * the operator was missing from the lexer's operator set and the atom stopped at `:..`.
     */
    fun testOperatorDefinition() {
        assertParsedAndQuotedCorrectlyFrom(elixir("1.12.0"))
    }

    fun testPipeline() {
        assertParsedAndQuotedCorrectlyFromOrParsedWithErrors(elixir("1.12.0"), true)
    }

    override fun getTestDataPath(): String = "${super.getTestDataPath()}/issue_2200"
}
