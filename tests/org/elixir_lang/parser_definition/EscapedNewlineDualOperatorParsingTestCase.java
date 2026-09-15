package org.elixir_lang.parser_definition;

import org.elixir_lang.language_level.ElixirLanguageLevel;
import org.elixir_lang.language_level.ElixirLanguageLevelResolver;

import java.io.IOException;

/**
 * A `\` + newline next to a spaced `+` or `-` after an identifier. Before Elixir 1.20.0 the escaped newline is not
 * space, so `f -\` newline `var` is the call `f(-var)` and `f \` newline `-var` is a subtraction; from 1.20.0 both
 * swap.
 * <p>
 * Both language levels are forced, and only parse trees are checked, for the reasons given on
 * {@link CaptureArgumentParsingTestCase}.
 */
public class EscapedNewlineDualOperatorParsingTestCase extends ParsingTestCase {
    private static final String AFTER_OPERATOR = "f -\\\nvar\n";
    private static final String BEFORE_OPERAND = "f \\\n-var\n";

    public void testAfterOperatorBelow1_20() throws IOException {
        assertParsedAtLanguageLevel(ElixirLanguageLevel.V1_19, "AfterOperatorBelow1_20", AFTER_OPERATOR);
    }

    public void testAfterOperatorFrom1_20() throws IOException {
        assertParsedAtLanguageLevel(ElixirLanguageLevel.V1_20, "AfterOperatorFrom1_20", AFTER_OPERATOR);
    }

    public void testBeforeOperandBelow1_20() throws IOException {
        assertParsedAtLanguageLevel(ElixirLanguageLevel.V1_19, "BeforeOperandBelow1_20", BEFORE_OPERAND);
    }

    public void testBeforeOperandFrom1_20() throws IOException {
        assertParsedAtLanguageLevel(ElixirLanguageLevel.V1_20, "BeforeOperandFrom1_20", BEFORE_OPERAND);
    }

    private void assertParsedAtLanguageLevel(
            ElixirLanguageLevel languageLevel,
            String expectedName,
            String source
    ) throws IOException {
        ElixirLanguageLevelResolver.overrideLanguageLevel(getProject(), languageLevel);

        parseFile(expectedName, source);

        checkResult(expectedName, myFile);
    }

    @Override
    protected String getTestDataPath() {
        return "testData/org/elixir_lang/parser_definition/escaped_newline_dual_operator_parsing_test_case";
    }
}
