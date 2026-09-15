package org.elixir_lang.parser_definition;

import org.elixir_lang.language_level.ElixirLanguageLevel;

/**
 * Created by kadie.enheduanna.inanna on 9/17/14.
 */
public class AtomParsingTestCase extends ParsingTestCase {
    public void testDoubleQuotedLiteral() {
        assertParsedAndQuotedCorrectly();
    }

    public void testDoubleQuotedInterpolation() {
        assertParsedAndQuotedCorrectly();
    }

    public void testLiteral() {
        assertParsedAndQuotedCorrectly();
    }

    public void testOperator() {
        assertParsedAndQuotedCorrectly();
    }

    /** Split out of {@link #testOperator} so gating it does not cost 1.11 coverage of the other atoms. */
    public void testStepRangeOperator() {
        assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_12);
    }

    public void testSingleQuotedLiteral() {
        assertParsedAndQuotedCorrectly();
    }

    public void testSingleQuotedInterpolation() {
        assertParsedAndQuotedCorrectly();
    }

    @Override
    protected String getTestDataPath() {
        return super.getTestDataPath() + "/atom_parsing_test_case";
    }
}
