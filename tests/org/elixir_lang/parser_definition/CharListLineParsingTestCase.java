package org.elixir_lang.parser_definition;

import org.elixir_lang.language_level.ElixirLanguageLevel;

/**
 * Created by kadie.enheduanna.inanna on 8/7/14.
 */
public class CharListLineParsingTestCase extends ParsingTestCase {
    public void testEmpty() {
        assertParsedAndQuotedCorrectly();
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

    public void testMultiline() {
        assertParsedAndQuotedCorrectly();
    }

    @Override
    protected String getTestDataPath() {
        return super.getTestDataPath() + "/char_list_line_parsing_test_case";
    }
}
