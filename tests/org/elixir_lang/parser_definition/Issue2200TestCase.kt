package org.elixir_lang.parser_definition

import org.elixir_lang.language_level.ElixirLanguageLevel

class Issue2200TestCase : ParsingTestCase() {
    /**
     * `:..//` as a call argument, which reported as `<unmatched expression> expected, got ','` while
     * the operator was missing from the lexer's operator set and the atom stopped at `:..`.
     */
    fun testOperatorDefinition() {
        assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_12)
    }

    fun testPipeline() {
        assertParsedAndQuotedCorrectlyFromOrParsedWithErrors(ElixirLanguageLevel.V1_12, true)
    }

    override fun getTestDataPath(): String = "${super.getTestDataPath()}/issue_2200"
}
