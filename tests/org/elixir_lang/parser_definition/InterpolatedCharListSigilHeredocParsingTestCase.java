package org.elixir_lang.parser_definition;

import org.elixir_lang.language_level.ElixirLanguageLevel;
/**
 * Created by kadie.enheduanna.inanna on 8/8/14.
 */
public class InterpolatedCharListSigilHeredocParsingTestCase extends ParsingTestCase {
    public void testEmpty() {
        assertParsedAndQuotedAroundError();
    }

    public void testEscapeSequences() {
        assertParsedAndQuotedCorrectly();
    }

    public void testInterpolation() {
        assertParsedAndQuotedCorrectly();
    }

    public void testMinimal() {
        assertParsedAndQuotedCorrectly();
    }

    public void testWhitespaceEndPrefix() {
        assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.of("1.12.0"));
    }

    @Override
    protected String getTestDataPath() {
        return super.getTestDataPath() + "/interpolated_char_list_sigil_heredoc_parsing_test_case";
    }
}
