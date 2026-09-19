package org.elixir_lang.parser_definition

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.elixir

class Issue4068TestCase : ParsingTestCase() {
    fun testNullaryRangeParenthesized() = assertParsedAndQuotedCorrectlyFrom(elixir("1.14.0"), false)
    fun testNullaryRangeArgument() = assertParsedAndQuotedCorrectlyFrom(elixir("1.14.0"), false)
    fun testNullaryRangeOperatorDefinition() = assertParsedAndQuotedCorrectlyFrom(elixir("1.14.0"), false)
    fun testNullaryRangeNot() = assertParsedAndQuotedCorrectlyFrom(elixir("1.14.0"), false)
    fun testNullaryRangeMatchOperands() = assertParsedAndQuotedCorrectlyFrom(elixir("1.14.0"), false)
    fun testNullaryRangeContainers() = assertParsedAndQuotedCorrectlyFrom(elixir("1.14.0"), false)
    fun testNullaryRangeNoParenthesesArguments() = assertParsedAndQuotedCorrectlyFrom(elixir("1.14.0"), false)
    fun testNullaryRangeEndOfLine() = assertParsedAndQuotedCorrectlyFrom(elixir("1.14.0"), false)
    fun testRangeOperatorNewline() = assertParsedAndQuotedCorrectly(false)
    fun testRangeSpacedOperands() = assertParsedAndQuotedCorrectly(false)

    fun testNoParenthesesManyArgumentsDoBlockArgument() = assertParsedAndQuotedCorrectly(false)
    fun testForManyArgumentsDoBlockArgument() = assertParsedAndQuotedCorrectly(false)
    fun testForManyArgumentsDoBlockSecondArgument() = assertParsedAndQuotedCorrectly(false)
    fun testWithManyArgumentsDoBlockArgument() = assertParsedAndQuotedCorrectly(false)

    fun testMultiLetterSigilDoubleQuotes() = assertParsedAndQuotedCorrectlyFrom(elixir("1.15.0"), false)
    fun testMultiLetterSigilBracketsModifier() = assertParsedAndQuotedCorrectlyFrom(elixir("1.15.0"), false)
    fun testMultiLetterSigilParenthesesModifiers() = assertParsedAndQuotedCorrectlyFrom(elixir("1.15.0"), false)
    fun testMultiLetterSigilSingleQuotes() = assertParsedAndQuotedCorrectlyFrom(elixir("1.15.0"), false)
    fun testMultiLetterSigilHeredoc() = assertParsedAndQuotedCorrectlyFrom(elixir("1.15.0"), false)
    fun testMultiLetterSigilDigits() = assertParsedAndQuotedCorrectlyFrom(elixir("1.17.0"), false)
    fun testMultiLetterSigilUnknown() = assertParsedAndQuotedCorrectlyFrom(elixir("1.15.0"), false)
    fun testMultiLetterSigilEmpty() = assertParsedAndQuotedCorrectlyFrom(elixir("1.15.0"), false)
    fun testMultiLetterSigilBraces() = assertParsedAndQuotedCorrectlyFrom(elixir("1.15.0"), false)

    fun testPinnedStructName() = assertParsedAndQuotedCorrectly(false)
    fun testPinnedStructNameEmpty() = assertParsedAndQuotedCorrectly(false)
    fun testPrefixedStructNames() = assertParsedAndQuotedCorrectly(false)
    fun testStepStructName() = assertParsedAndQuotedCorrectlyFrom(elixir("1.13.0"), false)
    fun testEllipsisStructName() = assertParsedAndQuotedCorrectlyFrom(elixir("1.17.0"), false)
    fun testPinnedStructNameAccess() = assertParsedAndQuotedCorrectlyFrom(elixir("1.17.0"), false)

    fun testUnicodeRemoteCall() = assertParsedAndQuotedCorrectly(false)
    fun testCombiningMarkIdentifierForms() = assertParsedAndQuotedCorrectly(false)
    fun testDecomposedIdentifier() = assertParsedAndQuotedCorrectlyFrom(elixir("1.14.0"), false)
    fun testDecomposedIdentifierForms() = assertParsedAndQuotedCorrectlyFrom(elixir("1.14.0"), false)

    fun testStabWhenManyArguments() = assertParsedAndQuotedCorrectly()

    fun testHexadecimalByteEscapeString() = assertParsedAndQuotedCorrectly(false)
    fun testHexadecimalByteEscapeAfterMultibyte() = assertParsedAndQuotedCorrectly(false)
    fun testHexadecimalByteEscapeHeredoc() = assertParsedAndQuotedCorrectly(false)
    fun testHexadecimalByteEscapeCharList() =
        assertParsedAndQuotedAroundErrorOrRaise(elixir("1.19.0"), "Elixir.UnicodeConversionError", false)
    fun testHexadecimalByteEscapeUtf8String() = assertParsedAndQuotedCorrectly(false)
    fun testHexadecimalByteEscapeUtf8CharList() = assertParsedAndQuotedCorrectly(false)
    fun testHexadecimalByteEscapeInterpolated() = assertParsedAndQuotedCorrectly(false)

    fun testAtAmbiguousDualOperator() = assertParsedAndQuotedCorrectly(false)
    fun testAtAmbiguousUnaryPlus() = assertParsedAndQuotedCorrectly(false)
    fun testAmbiguousUnaryPlusTypeOperation() = assertParsedAndQuotedCorrectly(false)
    fun testAmbiguousDualOperatorInfixArgument() = assertParsedAndQuotedCorrectly(false)
    fun testAmbiguousDualOperatorDoBlock() = assertParsedAndQuotedCorrectly(false)
    fun testAmbiguousKeywordKeyNotOperator() = assertParsedAndQuotedCorrectlyFrom(elixir("1.12.0"), false)
    fun testAmbiguousKeywordKeyNewlineNotOperator() = assertParsedAndQuotedCorrectlyFrom(elixir("1.12.0"), false)

    fun testLiteralSigilEscapedNewline() = assertParsedAndQuotedCorrectly(false)

    fun testHeredocSpacesThenTabs() = assertParsedAndQuotedCorrectly(false)
    fun testCharListHeredocSpacesThenTabs() = assertParsedAndQuotedCorrectly(false)

    fun testSteppedRangeVariables() = assertParsedAndQuotedCorrectly(false)
    fun testSteppedRangeCallForms() = assertParsedAndQuotedCorrectly(false)

    fun testCaptureStepOperator() = assertParsedAndQuotedCorrectly(false)
    fun testCaptureStepOperatorEscapedNewline() = assertParsedAndQuotedCorrectly(false)
    fun testStepOperatorUnary() = assertParsedAndQuotedCorrectly(false)
    fun testCaptureOperatorEscapedNewline() = assertParsedAndQuotedCorrectlyFrom(elixir("1.20.0"), false)
    fun testCaptureOperatorsEscapedNewline() = assertParsedAndQuotedCorrectlyFrom(elixir("1.20.0"), false)

    fun testDotKeywordKey() = assertParsedAndQuotedCorrectlyFrom(elixir("1.13.0"), false)
    fun testSteppedRangeKeywordKey() = assertParsedAndQuotedCorrectly(false)
    fun testSteppedRangeKeywordKeyForms() = assertParsedAndQuotedCorrectly(false)

    fun testMapNonPairEntries() = assertParsedAndQuotedCorrectlyFrom(elixir("1.17.0"), false)
    fun testMapTupleEntry() = assertParsedAndQuotedCorrectlyFrom(elixir("1.17.0"), false)
    fun testMapNonPairEntryForms() = assertParsedAndQuotedCorrectlyFrom(elixir("1.17.0"), false)

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

    fun testParenthesizedRangeStep() = assertParsedAndQuotedCorrectlyFromOrParsedWithErrors(elixir("1.12.0"), false)

    fun testCharacterOutsideBasicMultilingualPlane() = assertParsedAndQuotedCorrectly(false)

    fun testQuotedRemoteCallNameEscape() = assertParsedAndQuotedCorrectly(false)
    fun testCaptureQuotedRemoteCallNameEscape() = assertParsedAndQuotedCorrectly(false)
    fun testCaptureParenthesesQuotedRemoteCallNameEscape() = assertParsedAndQuotedCorrectly(false)
    fun testQuotedRemoteCallNameInvalidEscape() =
        assertParsedAndQuotedCorrectlyBeforeOrRaise(elixir("1.18.0"), elixir("1.19.0"), "Elixir.MatchError", false)
    fun testQuotedRemoteCallNameInvalidUnicodeEscape() =
        assertParsedAndQuotedCorrectlyBeforeOrRaise(elixir("1.18.0"), elixir("1.19.0"), "Elixir.MatchError", false)
    fun testQuotedRemoteCallNameInvalidBracedEscape() =
        assertParsedAndQuotedCorrectlyBeforeOrRaise(elixir("1.18.0"), elixir("1.19.0"), "Elixir.MatchError", false)
    fun testInvalidHexadecimalEscapeString() = assertParsedAndQuotedAroundError(false)
    fun testQuotedRemoteCallNameInvalidCodePoint() =
        assertParsedAndQuotedCorrectlyBeforeOrRaise(elixir("1.18.0"), elixir("1.19.0"), "Elixir.MatchError", false)
    fun testQuotedRemoteCallNameSurrogate() =
        assertParsedAndQuotedCorrectlyBeforeOrRaise(elixir("1.18.0"), elixir("1.19.0"), "Elixir.MatchError", false)

    fun testEscapedLineSeparator() = assertParsedAndQuotedCorrectlyBefore(elixir("1.20.0"), false)
    fun testEscapedLineSeparatorLiteralSigil() = assertParsedAndQuotedCorrectlyBefore(elixir("1.20.0"), false)
    fun testLineSeparatorHeredoc() = assertParsedAndQuotedCorrectlyBefore(elixir("1.20.0"), false)

    fun testSemicolon() = assertParsedAndQuotedCorrectly(false)

    fun testCharacterNewlineLine() = assertParsedAndQuotedCorrectly(false)
    fun testCharacterEscapedNewlineLine() = assertParsedAndQuotedCorrectly(false)

    override fun getTestDataPath(): String = "${super.getTestDataPath()}/issue_4068"
}
