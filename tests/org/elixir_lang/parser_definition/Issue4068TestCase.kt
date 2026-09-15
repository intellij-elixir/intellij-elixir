package org.elixir_lang.parser_definition

import org.elixir_lang.language_level.ElixirLanguageLevel

class Issue4068TestCase : ParsingTestCase() {
    fun testNullaryRangeParenthesized() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_14, false)
    fun testNullaryRangeArgument() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_14, false)
    fun testNullaryRangeOperatorDefinition() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_14, false)
    fun testNullaryRangeNot() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_14, false)
    fun testNullaryRangeMatchOperands() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_14, false)
    fun testNullaryRangeContainers() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_14, false)
    fun testNullaryRangeNoParenthesesArguments() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_14, false)
    fun testNullaryRangeEndOfLine() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_14, false)
    fun testRangeOperatorNewline() = assertParsedAndQuotedCorrectly(false)
    fun testRangeSpacedOperands() = assertParsedAndQuotedCorrectly(false)

    fun testNoParenthesesManyArgumentsDoBlockArgument() = assertParsedAndQuotedCorrectly(false)
    fun testForManyArgumentsDoBlockArgument() = assertParsedAndQuotedCorrectly(false)
    fun testForManyArgumentsDoBlockSecondArgument() = assertParsedAndQuotedCorrectly(false)
    fun testWithManyArgumentsDoBlockArgument() = assertParsedAndQuotedCorrectly(false)

    fun testMultiLetterSigilDoubleQuotes() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_15, false)
    fun testMultiLetterSigilBracketsModifier() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_15, false)
    fun testMultiLetterSigilParenthesesModifiers() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_15, false)
    fun testMultiLetterSigilSingleQuotes() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_15, false)
    fun testMultiLetterSigilHeredoc() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_15, false)
    fun testMultiLetterSigilDigits() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_17, false)
    fun testMultiLetterSigilUnknown() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_15, false)
    fun testMultiLetterSigilEmpty() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_15, false)
    fun testMultiLetterSigilBraces() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_15, false)

    fun testPinnedStructName() = assertParsedAndQuotedCorrectly(false)
    fun testPinnedStructNameEmpty() = assertParsedAndQuotedCorrectly(false)
    fun testPrefixedStructNames() = assertParsedAndQuotedCorrectly(false)
    fun testStepStructName() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_13, false)
    fun testEllipsisStructName() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_17, false)
    fun testPinnedStructNameAccess() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_17, false)

    fun testUnicodeRemoteCall() = assertParsedAndQuotedCorrectly(false)
    fun testCombiningMarkIdentifierForms() = assertParsedAndQuotedCorrectly(false)
    fun testDecomposedIdentifier() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_14, false)
    fun testDecomposedIdentifierForms() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_14, false)

    fun testStabWhenManyArguments() = assertParsedAndQuotedCorrectly()

    fun testHexadecimalByteEscapeString() = assertParsedAndQuotedCorrectly(false)
    fun testHexadecimalByteEscapeAfterMultibyte() = assertParsedAndQuotedCorrectly(false)
    fun testHexadecimalByteEscapeHeredoc() = assertParsedAndQuotedCorrectly(false)
    fun testHexadecimalByteEscapeCharList() =
        assertParsedAndQuotedAroundErrorOrRaise(ElixirLanguageLevel.V1_19, "Elixir.UnicodeConversionError", false)
    fun testHexadecimalByteEscapeUtf8String() = assertParsedAndQuotedCorrectly(false)
    fun testHexadecimalByteEscapeUtf8CharList() = assertParsedAndQuotedCorrectly(false)
    fun testHexadecimalByteEscapeInterpolated() = assertParsedAndQuotedCorrectly(false)

    fun testAtAmbiguousDualOperator() = assertParsedAndQuotedCorrectly(false)
    fun testAtAmbiguousUnaryPlus() = assertParsedAndQuotedCorrectly(false)
    fun testAmbiguousUnaryPlusTypeOperation() = assertParsedAndQuotedCorrectly(false)
    fun testAmbiguousDualOperatorInfixArgument() = assertParsedAndQuotedCorrectly(false)
    fun testAmbiguousDualOperatorDoBlock() = assertParsedAndQuotedCorrectly(false)
    fun testAmbiguousKeywordKeyNotOperator() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_12, false)
    fun testAmbiguousKeywordKeyNewlineNotOperator() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_12, false)

    fun testLiteralSigilEscapedNewline() = assertParsedAndQuotedCorrectly(false)

    fun testHeredocSpacesThenTabs() = assertParsedAndQuotedCorrectly(false)
    fun testCharListHeredocSpacesThenTabs() = assertParsedAndQuotedCorrectly(false)

    fun testSteppedRangeVariables() = assertParsedAndQuotedCorrectly(false)
    fun testSteppedRangeCallForms() = assertParsedAndQuotedCorrectly(false)

    fun testCaptureStepOperator() = assertParsedAndQuotedCorrectly(false)
    fun testCaptureStepOperatorEscapedNewline() = assertParsedAndQuotedCorrectly(false)
    fun testStepOperatorUnary() = assertParsedAndQuotedCorrectly(false)
    fun testCaptureOperatorEscapedNewline() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_20, false)
    fun testCaptureOperatorsEscapedNewline() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_20, false)

    fun testDotKeywordKey() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_13, false)
    fun testSteppedRangeKeywordKey() = assertParsedAndQuotedCorrectly(false)
    fun testSteppedRangeKeywordKeyForms() = assertParsedAndQuotedCorrectly(false)

    fun testMapNonPairEntries() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_17, false)
    fun testMapTupleEntry() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_17, false)
    fun testMapNonPairEntryForms() = assertParsedAndQuotedCorrectlyFrom(ElixirLanguageLevel.V1_17, false)

    fun testNotInLineStart() = assertParsedAndQuotedCorrectly(false)
    fun testNotInLineStartNewline() = assertParsedAndQuotedCorrectly(false)
    fun testNotLineStartInPrefixedIdentifier() = assertParsedAndQuotedCorrectly(false)

    fun testRemoteCallAfterDotNewline() = assertParsedAndQuotedCorrectly(false)
    fun testRemoteParenthesesCallAfterDotNewline() = assertParsedAndQuotedCorrectly(false)
    fun testRemoteParenthesesCallArgumentsAfterDotNewline() = assertParsedAndQuotedCorrectly(false)
    fun testRemoteNoParenthesesCallAfterDotNewline() = assertParsedAndQuotedCorrectly(false)

    fun testMicroSignIdentifier() = assertParsedAndQuotedCorrectly(false)
    fun testMicroSignMatch() = assertParsedAndQuotedCorrectly(false)
    fun testMicroSignIdentifierForms() = assertParsedAndQuotedCorrectly(false)

    fun testEmptyInterpolation() = assertParsedAndQuotedCorrectly(false)
    fun testEmptyInterpolationNewline() = assertParsedAndQuotedCorrectly(false)
    fun testEmptyInterpolationLaterLine() = assertParsedAndQuotedCorrectly(false)

    fun testEscapedNewlineBeforeDualOperator() = assertParsedAndQuotedCorrectly(false)
    fun testEscapedNewlineAfterDualOperator() = assertParsedAndQuotedCorrectly(false)
    fun testEscapedNewlineAfterDualOperatorForms() = assertParsedAndQuotedCorrectly(false)
    fun testEscapedNewlineBeforeDualOperatorOperand() = assertParsedAndQuotedCorrectly(false)
    fun testEscapedNewlineBeforeDualOperatorOperandForms() = assertParsedAndQuotedCorrectly(false)
    fun testUnspacedEscapedNewlineBeforeDualOperatorOperand() = assertParsedAndQuotedCorrectly(false)

    fun testCaptureEllipsis() = assertParsedAndQuotedCorrectly(false)
    fun testEllipsisDivision() = assertParsedAndQuotedCorrectly(false)

    fun testParenthesizedRangeStep() = assertParsedAndQuotedCorrectlyFromOrParsedWithErrors(ElixirLanguageLevel.V1_12, false)

    fun testCharacterOutsideBasicMultilingualPlane() = assertParsedAndQuotedCorrectly(false)

    fun testQuotedRemoteCallNameEscape() = assertParsedAndQuotedCorrectly(false)
    fun testCaptureQuotedRemoteCallNameEscape() = assertParsedAndQuotedCorrectly(false)
    fun testCaptureParenthesesQuotedRemoteCallNameEscape() = assertParsedAndQuotedCorrectly(false)
    fun testQuotedRemoteCallNameInvalidEscape() =
        assertParsedAndQuotedCorrectlyBeforeOrRaise(ElixirLanguageLevel.V1_18, ElixirLanguageLevel.V1_19, "Elixir.MatchError", false)
    fun testQuotedRemoteCallNameInvalidUnicodeEscape() =
        assertParsedAndQuotedCorrectlyBeforeOrRaise(ElixirLanguageLevel.V1_18, ElixirLanguageLevel.V1_19, "Elixir.MatchError", false)
    fun testQuotedRemoteCallNameInvalidBracedEscape() =
        assertParsedAndQuotedCorrectlyBeforeOrRaise(ElixirLanguageLevel.V1_18, ElixirLanguageLevel.V1_19, "Elixir.MatchError", false)
    fun testInvalidHexadecimalEscapeString() = assertParsedAndQuotedAroundError(false)
    fun testQuotedRemoteCallNameInvalidCodePoint() =
        assertParsedAndQuotedCorrectlyBeforeOrRaise(ElixirLanguageLevel.V1_18, ElixirLanguageLevel.V1_19, "Elixir.MatchError", false)
    fun testQuotedRemoteCallNameSurrogate() =
        assertParsedAndQuotedCorrectlyBeforeOrRaise(ElixirLanguageLevel.V1_18, ElixirLanguageLevel.V1_19, "Elixir.MatchError", false)

    fun testEscapedLineSeparator() = assertParsedAndQuotedCorrectlyBefore(ElixirLanguageLevel.V1_20, false)
    fun testEscapedLineSeparatorLiteralSigil() = assertParsedAndQuotedCorrectlyBefore(ElixirLanguageLevel.V1_20, false)
    fun testLineSeparatorHeredoc() = assertParsedAndQuotedCorrectlyBefore(ElixirLanguageLevel.V1_20, false)

    fun testSemicolon() = assertParsedAndQuotedCorrectly(false)

    fun testCharacterNewlineLine() = assertParsedAndQuotedCorrectly(false)
    fun testCharacterEscapedNewlineLine() = assertParsedAndQuotedCorrectly(false)

    override fun getTestDataPath(): String = "${super.getTestDataPath()}/issue_4068"
}
