package org.elixir_lang.parser_definition;

import org.elixir_lang.language_level.ElixirLanguageLevel;
/**
 * Created by kadie.enheduanna.inanna on 8/8/14.
 */
public class LiteralWordsHeredocParsingTestCase extends ParsingTestCase {
    public void testEmpty() {
        assertParsedAndQuotedAroundError();
    }

    public void testEmptyHexadecimalEscapeSequence() {
        assertParsedAndQuotedCorrectly();
    }

    public void testEmptyUnicodeEscapeSequence() {
        assertParsedAndQuotedCorrectly();
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

    public void testSigilModifiers() {
        assertParsedAndQuotedCorrectly();
    }

    public void testWhitespaceEndPrefix() {
        assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.of("1.12.0"));
    }

    @Override
    protected String getTestDataPath() {
        return super.getTestDataPath() + "/literal_words_heredoc_parsing_test_case";
    }
}
