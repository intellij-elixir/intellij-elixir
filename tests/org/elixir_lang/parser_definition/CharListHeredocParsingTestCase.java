package org.elixir_lang.parser_definition;

import org.elixir_lang.language_level.ElixirLanguageLevel;

/**
 * Created by kadie.enheduanna.inanna on 8/8/14.
 */
public class CharListHeredocParsingTestCase extends ParsingTestCase {
    /** See {@link org.elixir_lang.language_level.ElixirLanguageFeature#EMPTY_LEADING_HEREDOC_SEGMENT}. */
    public void testInterpolationFirst() {
        assertParsedAndQuotedCorrectly();
    }

    public void testEmpty() {
        assertParsedAndQuotedAroundError();
    }

    public void testEmptyUnicodeEscapeSequence() {
        assertParsedAndQuotedAroundErrorOrRaise(ElixirLanguageLevel.of("1.12.0"), "Elixir.ArgumentError");
    }

    public void testEnclosedHexEscapeSequence() {
        assertParsedAndQuotedCorrectlyBefore(ElixirLanguageLevel.of("1.20.0"));
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
        return super.getTestDataPath() + "/char_list_heredoc_parsing_test_case";
    }
}
