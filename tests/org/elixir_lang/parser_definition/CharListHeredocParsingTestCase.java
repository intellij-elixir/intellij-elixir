package org.elixir_lang.parser_definition;

import org.elixir_lang.language_level.ElixirLanguageLevel;

/**
 * Created by kadie.enheduanna.inanna on 8/8/14.
 */
public class CharListHeredocParsingTestCase extends ParsingTestCase {
    /** See ElixirLanguageLevel.V1_12. */
    public void testInterpolationFirst() {
        assertParsedAndQuotedCorrectly();
    }

    public void testEmpty() {
        assertParsedAndQuotedAroundError();
    }

    public void testEmptyUnicodeEscapeSequence() {
        assertParsedAndQuotedAroundErrorOrRaise(ElixirLanguageLevel.V1_12, "Elixir.ArgumentError");
    }

    public void testEnclosedHexEscapeSequence() {
        assertParsedAndQuotedCorrectlyBefore(ElixirLanguageLevel.V1_20);
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
        assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_12);
    }

    @Override
    protected String getTestDataPath() {
        return super.getTestDataPath() + "/char_list_heredoc_parsing_test_case";
    }
}
