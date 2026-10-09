package org.elixir_lang.lowering

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.psi.ElixirAdditionInfixOperator
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.walk.GrammarShapes

/**
 * Each expected term is `Code.string_to_quoted(code, columns: true, token_metadata: true)` on that version's Elixir,
 * with the code parsed as that version's plugin parses it.
 */
class OperatorsTest : LoweringTestCase() {
    // Binary operators, one test per precedence family

    fun testMatch() = assertOperator("1 = 2", "{:=, [line: 1, column: 3], [1, 2]}")

    fun testOr() = assertOperator(
        "[1 || 2, 1 or 2, 1 ||| 2]",
        "[{:||, [line: 1, column: 4], [1, 2]}, {:or, [line: 1, column: 12], [1, 2]}, {:|||, [line: 1, column: 20], [1, 2]}]"
    )

    fun testAnd() = assertOperator(
        "[1 && 2, 1 and 2, 1 &&& 2]",
        "[{:&&, [line: 1, column: 4], [1, 2]}, {:and, [line: 1, column: 12], [1, 2]}, {:&&&, [line: 1, column: 21], [1, 2]}]"
    )

    fun testComparison() = assertOperator(
        "[1 == 2, 1 != 2, 1 =~ 2, 1 === 2, 1 !== 2]",
        "[{:==, [line: 1, column: 4], [1, 2]}, {:!=, [line: 1, column: 12], [1, 2]}, {:=~, [line: 1, column: 20], [1, 2]}, {:===, [line: 1, column: 28], [1, 2]}, {:!==, [line: 1, column: 37], [1, 2]}]"
    )

    fun testRelational() = assertOperator(
        "[1 < 2, 1 > 2, 1 <= 2, 1 >= 2]",
        "[{:<, [line: 1, column: 4], [1, 2]}, {:>, [line: 1, column: 11], [1, 2]}, {:<=, [line: 1, column: 18], [1, 2]}, {:>=, [line: 1, column: 26], [1, 2]}]"
    )

    fun testArrow() = assertOperator(
        "[1 |> 2, 1 <<< 2, 1 >>> 2, 1 <<~ 2, 1 ~>> 2, 1 <~ 2, 1 ~> 2, 1 <~> 2, 1 <|> 2]",
        "[{:|>, [line: 1, column: 4], [1, 2]}, {:<<<, [line: 1, column: 12], [1, 2]}, {:>>>, [line: 1, column: 21], [1, 2]}, {:<<~, [line: 1, column: 30], [1, 2]}, {:~>>, [line: 1, column: 39], [1, 2]}, {:<~, [line: 1, column: 48], [1, 2]}, {:~>, [line: 1, column: 56], [1, 2]}, {:<~>, [line: 1, column: 64], [1, 2]}, {:\"<|>\", [line: 1, column: 73], [1, 2]}]"
    )

    fun testIn() = assertOperator("1 in 2", "{:in, [line: 1, column: 3], [1, 2]}")

    fun testInMatch() = assertOperator(
        "[1 <- 2, 1 \\\\ 2]",
        "[{:<-, [line: 1, column: 4], [1, 2]}, {:\\\\, [line: 1, column: 12], [1, 2]}]"
    )

    fun testThree() = assertOperator("1 ^^^ 2", "{:\"^^^\", [line: 1, column: 3], [1, 2]}")

    fun testTwo() = assertOperator(
        "[1 ++ 2, 1 -- 2, 1 +++ 2, 1 --- 2, 1 .. 2, 1 <> 2]",
        "[{:++, [line: 1, column: 4], [1, 2]}, {:--, [line: 1, column: 12], [1, 2]}, {:+++, [line: 1, column: 20], [1, 2]}, {:---, [line: 1, column: 29], [1, 2]}, {:.., [line: 1, column: 38], [1, 2]}, {:<>, [line: 1, column: 46], [1, 2]}]"
    )

    fun testAddition() = assertOperator(
        "[1 + 2, 1 - 2]",
        "[{:+, [line: 1, column: 4], [1, 2]}, {:-, [line: 1, column: 11], [1, 2]}]"
    )

    fun testMultiplication() = assertOperator(
        "[1 * 2, 1 / 2]",
        "[{:*, [line: 1, column: 4], [1, 2]}, {:/, [line: 1, column: 11], [1, 2]}]"
    )

    fun testPowerFrom113() = assertOperator(
        "1 ** 2",
        "1.13.4" to "{:**, [line: 1, column: 3], [1, 2]}",
        NEWEST to "{:**, [line: 1, column: 3], [1, 2]}",
    )

    fun testPipe() = assertOperator("[1 | 2]", "[{:|, [line: 1, column: 4], [1, 2]}]")

    fun testType() = assertOperator("1 :: 2", "{:\"::\", [line: 1, column: 3], [1, 2]}")

    fun testWhen() = assertOperator("1 when 2", "{:when, [line: 1, column: 3], [1, 2]}")

    fun testEveryPrecedenceNests() = assertOperator(
        "1 = 2 or 3 and 4 == 5 < 6 |> 7 in 8 ^^^ 9 ++ 10 + 11 * 12 ** 13",
        "1.13.4" to "{:=, [line: 1, column: 3], [1, {:or, [line: 1, column: 7], [2, {:and, [line: 1, column: 12], [3, {:==, [line: 1, column: 18], [4, {:<, [line: 1, column: 23], [5, {:|>, [line: 1, column: 27], [6, {:in, [line: 1, column: 32], [7, {:\"^^^\", [line: 1, column: 37], [8, {:++, [line: 1, column: 43], [9, {:+, [line: 1, column: 49], [10, {:*, [line: 1, column: 54], [11, {:**, [line: 1, column: 59], ~c\"\\f\\r\"}]}]}]}]}]}]}]}]}]}]}]}",
        NEWEST to "{:=, [line: 1, column: 3], [1, {:or, [line: 1, column: 7], [2, {:and, [line: 1, column: 12], [3, {:==, [line: 1, column: 18], [4, {:<, [line: 1, column: 23], [5, {:|>, [line: 1, column: 27], [6, {:in, [line: 1, column: 32], [7, {:\"^^^\", [line: 1, column: 37], [8, {:++, [line: 1, column: 43], [9, {:+, [line: 1, column: 49], [10, {:*, [line: 1, column: 54], [11, {:**, [line: 1, column: 59], ~c\"\\f\\r\"}]}]}]}]}]}]}]}]}]}]}]}",
    )

    fun testLeftAssociativeOperatorsNestOnTheLeft() = assertOperator(
        "[1 - 2 - 3, 1 in 2 in 3]",
        "[{:-, [line: 1, column: 8], [{:-, [line: 1, column: 4], [1, 2]}, 3]}, {:in, [line: 1, column: 20], [{:in, [line: 1, column: 15], [1, 2]}, 3]}]"
    )

    fun testRightAssociativeOperatorsNestOnTheRight() = assertOperator(
        "[1 = 2 = 3, 1 when 2 when 3, 1 :: 2 :: 3, 1 | 2 | 3]",
        "[{:=, [line: 1, column: 4], [1, {:=, [line: 1, column: 8], [2, 3]}]}, {:when, [line: 1, column: 15], [1, {:when, [line: 1, column: 22], [2, 3]}]}, {:\"::\", [line: 1, column: 32], [1, {:\"::\", [line: 1, column: 37], [2, 3]}]}, {:|, [line: 1, column: 45], [1, {:|, [line: 1, column: 49], [2, 3]}]}]"
    )

    // Newlines around a binary operator

    fun testANewlineAfterAnOperatorIsItsNewlines() = assertOperator(
        "1 +\n2",
        "{:+, [newlines: 1, line: 1, column: 3], [1, 2]}"
    )

    fun testANewlineBeforeAnOperatorIsItsNewlines() = assertOperator(
        "1\n|> 2",
        "{:|>, [newlines: 1, line: 2, column: 1], [1, 2]}"
    )

    fun testANewlineBeforeAnOperatorAfterASigil() = assertOperator(
        "~c\"a\"\n|> 1",
        "{:|>, [newlines: 1, line: 2, column: 1], [{:sigil_c, [delimiter: \"\\\"\", line: 1, column: 1], [{:<<>>, [line: 1, column: 1], [\"a\"]}, []]}, 1]}"
    )

    fun testACommentAndNewlineBeforeAnOperatorAfterASigil() = assertOperator(
        "~c\"a\" # c\n|> 1",
        "{:|>, [newlines: 1, line: 2, column: 1], [{:sigil_c, [delimiter: \"\\\"\", line: 1, column: 1], [{:<<>>, [line: 1, column: 1], [\"a\"]}, []]}, 1]}"
    )

    fun testANewlineBeforeAnOperatorAfterASigilHeredoc() = assertOperator(
        "~S\"\"\"\na\n\"\"\"\n|> 1",
        "{:|>, [newlines: 1, line: 4, column: 1], [{:sigil_S, [delimiter: \"\\\"\\\"\\\"\", line: 1, column: 1], [{:<<>>, [indentation: 0, line: 1, column: 1], [\"a\\n\"]}, []]}, 1]}"
    )

    fun testAnEscapedNewlineAfterANewlineBeforeAnOperator() = assertOperator(
        "[1\n\\\n|> 2, ~c\"a\"\n\\\n|> 1]",
        "[{:|>, [newlines: 1, line: 3, column: 1], [1, 2]}, {:|>, [newlines: 1, line: 5, column: 1], [{:sigil_c, [delimiter: \"\\\"\", line: 3, column: 7], [{:<<>>, [line: 3, column: 7], [\"a\"]}, []]}, 1]}]"
    )

    fun testANewlineCharacterBeforeAnOperatorIsNotANewline() = assertOperator(
        "?\n\n|> 1",
        "1.18.4" to "{:|>, [newlines: 1, line: 2, column: 1], [10, 1]}",
        "1.19.5" to "{:|>, [newlines: 1, line: 3, column: 1], [10, 1]}",
    )

    fun testAnEscapedNewlineCharacterBeforeAnOperatorIsNotANewline() = assertOperator(
        "?\\\n|> 1",
        "1.18.4" to "{:|>, [line: 1, column: 4], [10, 1]}",
        "1.19.5" to "{:|>, [line: 2, column: 1], [10, 1]}",
    )

    fun testACommentAfterAnOperatorRestartsItsNewlines() = assertOperator(
        "1 +\n# c\n2",
        "{:+, [newlines: 1, line: 1, column: 3], [1, 2]}"
    )

    fun testNewlinesOnBothSidesOfAnOperator() = assertOperator(
        "1\n\n|>\n\n2",
        "{:|>, [newlines: 2, line: 3, column: 1], [1, 2]}"
    )

    fun testNewlinesAfterAnOperatorReplaceThoseBeforeIt() = assertOperator(
        "1\n\n|>\n2",
        "{:|>, [newlines: 1, line: 3, column: 1], [1, 2]}"
    )

    fun testANewlineAroundAPipeInAList() = assertOperator(
        "[[1 |\n2], [1\n| 2]]",
        "[[{:|, [newlines: 1, line: 1, column: 5], [1, 2]}], [{:|, [newlines: 1, line: 3, column: 1], [1, 2]}]]"
    )

    fun testAMatchHasNewlinesFrom115() = assertOperator(
        "1 =\n2",
        "1.14.5" to "{:=, [line: 1, column: 3], [1, 2]}",
        "1.15.8" to "{:=, [newlines: 1, line: 1, column: 3], [1, 2]}",
    )

    fun testAMatchTakesTheNewlinesBeforeItUntil115() = assertOperator(
        "1\n=\n\n2",
        "1.14.5" to "{:=, [newlines: 1, line: 2, column: 1], [1, 2]}",
        "1.15.8" to "{:=, [newlines: 2, line: 2, column: 1], [1, 2]}",
    )

    fun testAnEscapedNewlineIsNotANewline() = assertOperator(
        "1 \\\n+ 2",
        "{:+, [line: 2, column: 1], [1, 2]}"
    )

    // Unary operators

    fun testUnary() = assertOperator(
        "[-1, +1, !1, not 1, ^1, ~~~1]",
        "[{:-, [line: 1, column: 2], [1]}, {:+, [line: 1, column: 6], [1]}, {:!, [line: 1, column: 10], [1]}, {:not, [line: 1, column: 14], [1]}, {:^, [line: 1, column: 21], [1]}, {:\"~~~\", [line: 1, column: 25], [1]}]"
    )

    fun testAUnaryOperatorTakesNoNewlines() = assertOperator(
        "[-\n1, not\n1]",
        "[{:-, [line: 1, column: 2], [1]}, {:not, [line: 2, column: 4], [1]}]"
    )

    fun testUnaryOperatorsNest() = assertOperator("- - 1", "{:-, [line: 1, column: 1], [{:-, [line: 1, column: 3], [1]}]}")

    fun testAUnaryOperand() = assertOperator("1 = -1", "{:=, [line: 1, column: 3], [1, {:-, [line: 1, column: 5], [1]}]}")

    fun testASolitaryUnaryIsWrappedInTheFileBlockUntil115() = assertOperator(
        "not 1",
        "1.14.5" to "{:__block__, [], [{:not, [line: 1, column: 1], [1]}]}",
        "1.15.8" to "{:not, [line: 1, column: 1], [1]}",
    )

    fun testASolitaryUnaryIsWrappedInParenthesesBlock() = assertOperator(
        "(!1)",
        "1.14.5" to "{:__block__, [closing: [line: 1, column: 4], line: 1, column: 1], [{:!, [line: 1, column: 2], [1]}]}",
        "1.15.8" to "{:__block__, [], [{:!, [line: 1, column: 2], [1]}]}",
    )

    fun testAPrefixDoubleSlashIsDivisionByTheDivisionOperator() = assertOperator(
        "//2",
        "{:/, [line: 1, column: 2], [{:/, [line: 1, column: 1], nil}, 2]}"
    )

    // Captures

    fun testACaptureArgument() = assertOperator("[&1, & 1]", "[{:&, [line: 1, column: 2], [1]}, {:&, [line: 1, column: 6], [1]}]")

    fun testCapturingAnExpression() = assertOperator(
        "&(&1 + 1)",
        "1.17.3" to "{:&, [line: 1, column: 1], [{:+, [line: 1, column: 6], [{:&, [line: 1, column: 3], [1]}, 1]}]}",
        "1.18.4" to "{:&, [line: 1, column: 1], [{:+, [parens: [line: 1, column: 2, closing: [line: 1, column: 9]], line: 1, column: 6], [{:&, [line: 1, column: 3], [1]}, 1]}]}",
        "1.20.4" to "{:&, [line: 1, column: 1], [{:+, [parens: [closing: [line: 1, column: 9], line: 1, column: 2], line: 1, column: 6], [{:&, [line: 1, column: 3], [1]}, 1]}]}",
    )

    fun testCapturingDivision() = assertOperator(
        "&//2",
        "{:&, [line: 1, column: 1], [{:/, [line: 1, column: 3], [{:/, [line: 1, column: 2], nil}, 2]}]}"
    )

    // Ranges

    fun testARange() = assertOperator(
        "[1..2, 1 .. 2]",
        "[{:.., [line: 1, column: 3], [1, 2]}, {:.., [line: 1, column: 10], [1, 2]}]"
    )

    fun testASteppedRangeIsAtItsRangeFrom112() = assertOperator(
        "[1..2//3, 1 .. 2 // 3]",
        "1.12.3" to "[{:..//, [line: 1, column: 3], [1, 2, 3]}, {:..//, [line: 1, column: 13], [1, 2, 3]}]",
        NEWEST to "[{:..//, [line: 1, column: 3], [1, 2, 3]}, {:..//, [line: 1, column: 13], [1, 2, 3]}]",
    )

    fun testASteppedRangeTakesItsRangesNewlines() = assertOperator(
        "1..\n2//3",
        "1.12.3" to "{:..//, [newlines: 1, line: 1, column: 2], [1, 2, 3]}",
        NEWEST to "{:..//, [newlines: 1, line: 1, column: 2], [1, 2, 3]}",
    )

    fun testASteppedParenthesizedRangeTakesItsParens() = assertOperator(
        "(1..2)//3",
        "1.17.3" to "{:..//, [line: 1, column: 3], [1, 2, 3]}",
        "1.18.4" to "{:..//, [parens: [line: 1, column: 1, closing: [line: 1, column: 6]], line: 1, column: 3], [1, 2, 3]}",
        "1.20.4" to "{:..//, [parens: [closing: [line: 1, column: 6], line: 1, column: 1], line: 1, column: 3], [1, 2, 3]}",
    )

    fun testANullaryRangeFrom114() = assertOperator(
        "[..]",
        "1.14.5" to "[{:.., [line: 1, column: 2], []}]",
        NEWEST to "[{:.., [line: 1, column: 2], []}]",
    )

    // `not in`

    fun testNotIn() = assertOperator(
        "1 not in 2",
        "1.11.4" to "{:__block__, [], [{:not, [operator: :\"not in\", line: 1, column: 3], [{:in, [line: 1, column: 3], [1, 2]}]}]}",
        "1.12.3" to "{:__block__, [], [{:not, [line: 1, column: 3], [{:in, [line: 1, column: 3], [1, 2]}]}]}",
        "1.15.8" to "{:not, [line: 1, column: 3], [{:in, [line: 1, column: 3], [1, 2]}]}",
        "1.18.4" to "{:not, [line: 1, column: 3], [{:in, [line: 1, column: 3], [1, 2]}]}",
        "1.19.5" to "{:not, [line: 1, column: 3], [{:in, [line: 1, column: 7], [1, 2]}]}",
    )

    fun testNotInOnItsOwnLine() = assertOperator(
        "1\nnot in 2",
        "1.11.4" to "{:__block__, [], [{:not, [operator: :\"not in\", line: 2, column: 1], [{:in, [line: 2, column: 1], [1, 2]}]}]}",
        "1.12.3" to "{:__block__, [], [{:not, [line: 2, column: 1], [{:in, [line: 2, column: 1], [1, 2]}]}]}",
        "1.18.4" to "{:not, [line: 2, column: 1], [{:in, [line: 2, column: 1], [1, 2]}]}",
        "1.19.5" to "{:not, [newlines: 1, line: 2, column: 1], [{:in, [line: 2, column: 5], [1, 2]}]}",
    )

    fun testNotInOnItsOwnLineAfterASigil() = assertOperator(
        "~c\"a\"\nnot in 1",
        "1.18.4" to "{:not, [line: 2, column: 1], [{:in, [line: 2, column: 1], [{:sigil_c, [delimiter: \"\\\"\", line: 1, column: 1], [{:<<>>, [line: 1, column: 1], [\"a\"]}, []]}, 1]}]}",
        "1.19.5" to "{:not, [newlines: 1, line: 2, column: 1], [{:in, [line: 2, column: 5], [{:sigil_c, [delimiter: \"\\\"\", line: 1, column: 1], [{:<<>>, [line: 1, column: 1], [\"a\"]}, []]}, 1]}]}",
    )

    fun testNotInAfterAnEscapedNewlineCharacter() = assertOperator(
        "?\\\nnot in 1",
        "1.18.4" to "{:not, [line: 1, column: 4], [{:in, [line: 1, column: 4], [10, 1]}]}",
        "1.19.5" to "{:not, [line: 2, column: 1], [{:in, [line: 2, column: 5], [10, 1]}]}",
    )

    fun testANewlineAfterNotIn() = assertOperator(
        "1 not in\n2",
        "1.18.4" to "{:not, [line: 1, column: 3], [{:in, [line: 1, column: 3], [1, 2]}]}",
        "1.19.5" to "{:not, [newlines: 1, line: 1, column: 3], [{:in, [line: 1, column: 7], [1, 2]}]}",
    )

    fun testNotInNests() = assertOperator(
        "1 not in 2 not in 3",
        "1.18.4" to "{:not, [line: 1, column: 12], [{:in, [line: 1, column: 12], [{:not, [line: 1, column: 3], [{:in, [line: 1, column: 3], [1, 2]}]}, 3]}]}",
        "1.19.5" to "{:not, [line: 1, column: 12], [{:in, [line: 1, column: 16], [{:not, [line: 1, column: 3], [{:in, [line: 1, column: 7], [1, 2]}]}, 3]}]}",
    )

    // A unary `not` or `!` before `in` is moved outside it

    fun testANotBeforeInIsMovedOutsideIt() = assertOperator(
        "not 1 in 2",
        "1.14.5" to "{:__block__, [], [{:not, [line: 1, column: 7], [{:in, [line: 1, column: 7], [1, 2]}]}]}",
        "1.19.5" to "{:not, [line: 1, column: 7], [{:in, [line: 1, column: 7], [1, 2]}]}",
        "1.20.4" to "{:not, [line: 1, column: 1], [{:in, [line: 1, column: 7], [1, 2]}]}",
    )

    fun testABangBeforeInOnTheNextLineIsMovedOutsideIt() = assertOperator(
        "!1\nin 2",
        "1.14.5" to "{:__block__, [], [{:!, [line: 2, column: 1], [{:in, [line: 2, column: 1], [1, 2]}]}]}",
        "1.19.5" to "{:!, [line: 2, column: 1], [{:in, [line: 2, column: 1], [1, 2]}]}",
        "1.20.4" to "{:!, [line: 1, column: 1], [{:in, [line: 2, column: 1], [1, 2]}]}",
    )

    fun testARearrangedInTakesNoNewlines() = assertOperator(
        "!1 in\n2",
        "1.19.5" to "{:!, [line: 1, column: 4], [{:in, [line: 1, column: 4], [1, 2]}]}",
        "1.20.4" to "{:!, [line: 1, column: 1], [{:in, [line: 1, column: 4], [1, 2]}]}",
    )

    fun testAParenthesizedInIsNotRearranged() = assertOperator(
        "!(1 in 2)",
        "1.17.3" to "{:!, [line: 1, column: 1], [{:in, [line: 1, column: 5], [1, 2]}]}",
        "1.18.4" to "{:!, [line: 1, column: 1], [{:in, [parens: [line: 1, column: 2, closing: [line: 1, column: 9]], line: 1, column: 5], [1, 2]}]}",
        "1.20.4" to "{:!, [line: 1, column: 1], [{:in, [parens: [closing: [line: 1, column: 9], line: 1, column: 2], line: 1, column: 5], [1, 2]}]}",
    )

    // From 1.17, `a -[1]` is `a(-[1])`

    fun testAnIdentifierBeforeASignAndAContainerIsACallFrom117() = assertOperator(
        "a -[1]",
        "1.17.3" to "{:a, [ambiguous_op: nil, line: 1, column: 1], [{:-, [line: 1, column: 3], [[1]]}]}",
        NEWEST to "{:a, [ambiguous_op: nil, line: 1, column: 1], [{:-, [line: 1, column: 3], [[1]]}]}",
    )

    fun testAnIdentifierBeforeASignAndParenthesesIsACallFrom117() = assertOperator(
        "a +(1)",
        "1.17.3" to "{:a, [ambiguous_op: nil, line: 1, column: 1], [{:+, [line: 1, column: 3], [1]}]}",
        NEWEST to "{:a, [ambiguous_op: nil, line: 1, column: 1], [{:+, [line: 1, column: 3], [1]}]}",
    )

    fun testAnEscapedNewlineBeforeTheSignIsSpaceFrom120() = assertOperator(
        "a \\\n-[1]",
        "1.20.4" to "{:a, [ambiguous_op: nil, line: 1, column: 1], [{:-, [line: 2, column: 1], [[1]]}]}",
    )

    fun testBeforeTheSignIsASubtractionUntil117() = assertSubtractsAList("a -[1]", "1.16.3", "[line: 1, column: 3]")

    fun testAnEscapedNewlineBeforeTheSignIsNotSpaceUntil120() =
        assertSubtractsAList("a \\\n-[1]", "1.19.5", "[line: 2, column: 1]")

    // Operator tokens and every shape

    fun testAnOperatorTokenLowersToItsAtom() {
        val file = parse("1 + 2", NEWEST)
        val operator = PsiTreeUtil.findChildOfType(file, ElixirAdditionInfixOperator::class.java)!!

        assertEquals(":+", inspect(lowerElement(file, operator).toOtp()))
    }

    fun testEveryOperatorShapeLowers() {
        val snippets = listOf(
            OLDEST to "1..2\n..//: 1",
            // matched operations are a no-parentheses call's arguments; top-level ones are unmatched
            NEWEST to """
                a 1 = 1 || 1 && 1 == 1 < 1 |> 1 in 1 <- 1 ^^^ 1 ++ 1 + 1 * 1 ** 1, 1 \\ 1, 1 | 1 :: 1 when 1, 1 not in 1, 1..1//1, -1, &1, &(&1), ..
                1 = 1 || 1 && 1 == 1 < 1 |> 1 in 1 <- 1 ^^^ 1 ++ 1 + 1 * 1 ** 1
                1 \\ 1
                1 | 1 :: 1 when 1
                1 not in 1
                1..1//1
                -1
                &(&1)
                %A{}
                @a
                a.b
                fn -> 1 end
                1 = if 1 do end
                1 || if 1 do end
                1 && if 1 do end
                1 == if 1 do end
                1 < if 1 do end
                1 |> if 1 do end
                1 in if 1 do end
                1 not in if 1 do end
                1 <- if 1 do end
                1 \\ if 1 do end
                1 ^^^ if 1 do end
                1 ++ if 1 do end
                1 + if 1 do end
                1 * if 1 do end
                1 ** if 1 do end
                1..1//if 1 do end
                1 | if 1 do end
                1 :: if 1 do end
                1 when if 1 do end
                - if 1 do end
                & if 1 do end
            """.trimIndent(),
        )
        val seen = mutableSetOf<Class<*>>()

        for ((version, code) in snippets) {
            val file = parse(code, version)

            ReadAction.computeBlocking<Unit, Throwable> {
                val lowering = Lowering.of(file, ElixirLanguageLevel.of(version))

                PsiTreeUtil.processElements(file) { element ->
                    if (Lowering.classifier.classify(element.javaClass) == Lowering.Bucket.OPERATOR) {
                        seen.add(element.javaClass)
                        val lowered = lowering.lower(element)

                        assertFalse(
                            "${element.javaClass.simpleName} `${element.text}` left an operator unlowered",
                            unloweredShapes(lowered).any { Lowering.classifier.classify(it) == Lowering.Bucket.OPERATOR }
                        )
                    }
                    true
                }
            }
        }

        val operatorShapes = GrammarShapes.CONCRETE.filter {
            Lowering.classifier.classify(it) == Lowering.Bucket.OPERATOR
        }

        assertEquals(
            "operator shapes no snippet reaches",
            emptyList<String>(),
            operatorShapes.filterNot { it in seen }.map { it.simpleName }.sorted()
        )
    }

    /**
     * [code] parsed and lowered at each version in [expected], printed by [inspect] with columns and token metadata.
     */
    private fun assertOperator(code: String, vararg expected: Pair<String, String>) =
        assertEquals(
            expected.joinToString("\n") { (version, term) -> "$version: $term" },
            expected.joinToString("\n") { (version, _) ->
                "$version: " + inspect(lowerParsedAt(code, version).toOtp(COLUMNS_AND_TOKEN_METADATA))
            }
        )

    private fun assertOperator(code: String, expected: String) =
        assertOperator(code, OLDEST to expected, NEWEST to expected)

    /** [code] at [version] is `a - [1]`, whatever the calls' family makes of `a`. */
    private fun assertSubtractsAList(code: String, version: String, meta: String) {
        val call = lowerParsedAt(code, version) as ElixirAst.Call
        val arguments = call.arguments!!

        assertEquals(
            "{:-, $meta, [_, [1]]}",
            "{${inspect(call.callee.toOtp())}, ${inspect(call.meta.toOtp(COLUMNS_AND_TOKEN_METADATA))}, " +
                "[_, ${inspect(arguments.last().toOtp())}]}"
        )
        assertEquals(2, arguments.size)
    }

    /** [code] parsed as [version]'s plugin parses it, since the grammar has version-gated rules. */
    private fun parse(code: String, version: String): ElixirFile {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, ElixirLanguageLevel.of(version))
        val file = createPsiFile(getTestName(false), code) as ElixirFile
        ReadAction.computeBlocking<Unit, Throwable> { file.node.lastChildNode }

        return file
    }

    private fun lowerParsedAt(code: String, version: String): ElixirAst {
        val file = parse(code, version)

        return ReadAction.computeBlocking<ElixirAst, Throwable> {
            Lowering.lower(file, ElixirLanguageLevel.of(version))
        }
    }

    private fun lowerElement(file: ElixirFile, element: PsiElement): ElixirAst =
        ReadAction.computeBlocking<ElixirAst, Throwable> {
            Lowering.of(file, ElixirLanguageLevel.FALLBACK).lower(element)
        }

    private fun unloweredShapes(node: ElixirAst): List<Class<out PsiElement>> =
        when (node) {
            is ElixirAst.Placeholder -> when (val reason = node.reason) {
                is ElixirAst.Placeholder.Reason.Unlowered -> listOf(reason.shape)
                ElixirAst.Placeholder.Reason.Error, ElixirAst.Placeholder.Reason.Compiled -> emptyList()
            }
            is ElixirAst.Call -> unloweredShapes(node.callee) + node.arguments.orEmpty().flatMap { unloweredShapes(it) }
            is ElixirAst.Alias -> node.segments.flatMap { unloweredShapes(it) }
            is ElixirAst.Literal -> emptyList()
            is ElixirAst.ListNode -> node.elements.flatMap { unloweredShapes(it) }
            is ElixirAst.Tuple -> node.elements.flatMap { unloweredShapes(it) }
            is ElixirAst.Block -> node.expressions.flatMap { unloweredShapes(it) }
        }
}
