package org.elixir_lang.parser_definition;

import org.elixir_lang.language_level.ElixirLanguageLevel;

/**
 * Created by kadie.enheduanna.inanna on 8/3/14.
 */
public class DualOperatorParsingTestCase extends ParsingTestCase {
    public void testIdentifierOperatorEOLIdentifier() {
        assertParsedAndQuotedCorrectly();
    }

    public void testIdentifierOperatorIdentifier() {
        assertParsedAndQuotedCorrectly();
    }

    /**
     * [one-two, three] can be confused for [one(-two, three)] because the no parentheses many arguments is part of
     * matchedExpression even though it's not part of matched_expr.
     */
    public void testIdentifierOperatorIdentifierCommaIdentifierInContainer() {
        assertParsedAndQuotedCorrectly();
    }

    /**
     * see <a href="https://github.com/elixir-lang/elixir/blob/de39bbaca277002797e52ffbde617ace06233a2b/lib/elixir/src/elixir_tokenizer.erl#L609">...</a>
     */
    public void testIdentifierSpaceOperatorContainer() {
        assertParsedAndQuotedCorrectly();
    }

    public void testIdentifierSpaceOperatorEOLIdentifier() {
        assertParsedAndQuotedCorrectly();
    }

    public void testIdentifierSpaceOperatorIdentifier() {
        assertParsedAndQuotedCorrectly();
    }

    /**
     * see <a href="https://github.com/elixir-lang/elixir/blob/de39bbaca277002797e52ffbde617ace06233a2b/lib/elixir/src/elixir_tokenizer.erl#L610">...</a>
     */
    public void testIdentifierSpaceOperatorOperatorOperand() {
        assertParsedAndQuotedCorrectly();
    }

    /**
     * Elixir 1.13.0 (elixir-lang/elixir bbde3cb98) lexes a unary operator before {@code /} as an identifier without a
     * preceding {@code &}; earlier versions reject it.
     */
    public void testIdentifierSpaceOperatorSlashIdentifier() {
        assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.of("1.13.0"));
    }

    public void testIdentifierSpaceOperatorSpaceCommentEOLIdentifier() {
        assertParsedAndQuotedCorrectly();
    }

    public void testIdentifierSpaceOperatorSpaceIdentifier() {
        assertParsedAndQuotedCorrectly();
    }

    public void testIdentifierCommaOperatorIdentifier() {
        assertParsedAndQuotedCorrectly();
    }

    public void testIdentifierCommaOperatorSpaceIdentifier() {
        assertParsedAndQuotedCorrectly();
    }

    public void testIdentifierCommaSpaceDualSpaceIdentifier() {
        assertParsedAndQuotedCorrectly();
    }

    public void testIdentifierCommaSpaceOperatorIdentifier() {
        assertParsedAndQuotedCorrectly();
    }

    public void testIdentifierEOLOperatorSpaceIdentifier() {
        assertParsedAndQuotedCorrectly();
    }

    public void testIdentifierEOLOperatorIdentifier() {
        assertParsedAndQuotedCorrectly();
    }

    public void testIdentifierOperatorSpaceIdentifier() {
        assertParsedAndQuotedCorrectly();
    }

    public void testEnum() {
        assertParsedAndQuotedCorrectly();
    }

    @Override
    protected String getTestDataPath() {
        return super.getTestDataPath() + "/dual_operator_parsing_test_case";
    }
}
