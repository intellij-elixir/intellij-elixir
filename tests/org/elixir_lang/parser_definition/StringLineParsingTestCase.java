package org.elixir_lang.parser_definition;

import org.elixir_lang.language_level.ElixirLanguageLevel;

/**
 * Created by kadie.enheduanna.inanna on 8/7/14.
 */
public class StringLineParsingTestCase extends ParsingTestCase {
    /** A plain string's escaped EOL before an interpolation, which from 1.12 leaves an empty segment. */
    public void testEscapedEOLBeforeInterpolation() {
        assertParsedAndQuotedCorrectly();
    }

    /** The same escaped EOL trailing an interpolation, where the final flush decides instead. */
    public void testEscapedEOLAfterInterpolation() {
        assertParsedAndQuotedCorrectly();
    }

    public void testEmpty() {
        assertParsedAndQuotedCorrectly();
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

    /* Tests that '#' for comments and '#' for interpolation are treated differently.  Covers bug reported in
     * {@link https://github.com/intellij-elixir/intellij-elixir/issues/2}.
     */
    public void testInterpolationRegression() {
        // TODO replace doTest(true) with assertParsedAndQuotedCorrectly() when parser complete
        doTest(true);
    }

    public void testMultiline() {
        assertParsedAndQuotedCorrectly();
    }

    public void testNestedInterpolation() {
        assertParsedAndQuotedCorrectly();
    }

    public void testWithoutInterpolation() {
        assertParsedAndQuotedCorrectly();
    }

    @Override
    protected String getTestDataPath() {
        return super.getTestDataPath() + "/string_line_parsing_test_case";
    }
}
