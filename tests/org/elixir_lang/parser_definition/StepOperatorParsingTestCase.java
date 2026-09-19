package org.elixir_lang.parser_definition;

import org.elixir_lang.language_level.ElixirLanguageLevel;
import org.elixir_lang.language_level.ElixirLanguageLevelResolver;

import java.io.IOException;

/**
 * `//` before Elixir 1.12.0, where there is no step operator: an identifier before `//` is a call on `(/)/step`, and
 * `..//:` is `..(/([/: value]))`.
 * <p>
 * Both language levels are forced, and only parse trees are checked, for the reasons given on
 * {@link CaptureArgumentParsingTestCase}.
 */
public class StepOperatorParsingTestCase extends ParsingTestCase {
    private static final String STEPPED_RANGE = "x..y//1\n";
    private static final String KEYWORD_KEY = "[..//: 1]\n";

    public void testSteppedRangeBelow1_12() throws IOException {
        assertParsedAtLanguageLevel(ElixirLanguageLevel.of("1.11.0"), "SteppedRangeBelow1_12", STEPPED_RANGE);
    }

    public void testSteppedRangeFrom1_12() throws IOException {
        assertParsedAtLanguageLevel(ElixirLanguageLevel.of("1.12.0"), "SteppedRangeFrom1_12", STEPPED_RANGE);
    }

    public void testKeywordKeyBelow1_12() throws IOException {
        assertParsedAtLanguageLevel(ElixirLanguageLevel.of("1.11.0"), "KeywordKeyBelow1_12", KEYWORD_KEY);
    }

    public void testKeywordKeyFrom1_12() throws IOException {
        assertParsedAtLanguageLevel(ElixirLanguageLevel.of("1.12.0"), "KeywordKeyFrom1_12", KEYWORD_KEY);
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
        return "testData/org/elixir_lang/parser_definition/step_operator_parsing_test_case";
    }
}
