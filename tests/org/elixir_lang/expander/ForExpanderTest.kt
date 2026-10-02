package org.elixir_lang.expander

/**
 * `for`, over lowered snippets at every supported minor and at the first tag of each version difference. Its options
 * see only the variables before it, its generators and filters bind for those after them and the `do`, and nothing it
 * binds is visible after it.
 */
class ForExpanderTest : ExpanderTestCase() {
    fun testAGeneratorBindsForTheDoAndNothingAfter() =
        assertVersioned("for x <- [1, 2], do: y = x", "{} next 2", "{} next 3")

    fun testAGeneratorReadsTheGeneratorsBeforeIt() =
        assertVersioned("for x <- [1],\ny <- [x], do: {x, y}", "{} next 2", "{} next 3")

    fun testAFilterBindsForTheDo() = assertVersioned("for x <- [1],\ny = x, do: y", "{} next 2", "{} next 3")

    fun testWhatARightSideBindsIsVisibleAfterItOnlyBefore1_13() {
        val code = "for x <- (y = [1]), do: y"

        assertLevels(code, LEVELS + "1.13.0-rc.0" + "1.15.0-rc.0") {
            when {
                isBefore(it, "1.13.0-rc.0") -> "expanded {} next 2"
                isBefore(it, "1.15.0-rc.0") -> "error undefined_function `y`"
                else -> "error undefined_var `y`"
            }
        }
    }

    fun testACyclicPatternIsAnErrorAtItsGeneratorFrom1_18() =
        assertSplit(
            "for {x = y, x = {:ok, y}} <- [1], do: x",
            "1.18.0-rc.0",
            "expanded {} next 2",
            "error recursive `{x = y, x = {:ok, y}} <- [1]`"
        )

    fun testAPatternSeesTheEnvItsRightSideLeftOnlyFrom1_13() =
        assertSplit(
            "for %{Bar => a, Foo.Bar => b} <- (alias Foo.Bar; %{}), do: a",
            "1.13.0-rc.0",
            "expanded {} next 2",
            "error repeated_key `%{Bar => a, Foo.Bar => b}`"
        )

    fun testAnOptionCannotReadAGenerator() =
        assertSplit(
            "for x <- [1], into: x, do: x",
            "1.15.0-rc.0",
            "error undefined_function `x`",
            "error undefined_var `x`"
        )

    fun testWhatAnOptionBindsIsVisibleToTheDo() =
        assertVersioned("for x <- [1], into: (y = []), do: {x, y}", "{} next 2", "{} next 3")

    fun testAPinnedGenerator() = assertVersioned("y = [1]\nfor ^y <- [[1]], do: y", "{y:0} next 1", "{y:0} next 2")

    fun testAGuardedGenerator() = assertVersioned("for x when x <- [true], do: x", "{} next 1", "{} next 2")

    fun testABitstringGenerator() = assertVersioned("for <<c <- \"ab\">>, do: c", "{} next 1", "{} next 2")

    fun testABitstringGeneratorOfTwoSegments() =
        assertVersioned("for <<a, b <- \"ab\">>, do: {a, b}", "{} next 2", "{} next 3")

    fun testASizedBinaryInABitstringGenerator() =
        assertVersioned("for <<x::binary-size(1) <- \"ab\">>, do: x", "{} next 1", "{} next 2")

    fun testAnUnsizedBinaryInABitstringGeneratorIsAnError() =
        assertEvery("for <<x::binary <- \"ab\">>, do: x", "error unsized_binary `x::binary`")

    fun testAnUnsizedBinaryBeforeTheLastSegmentOfABitstringGeneratorIsAnError() =
        assertEvery("for <<x::binary, y <- \"ab\">>, do: {x, y}", "error unsized_binary `x::binary`")

    fun testAUniqThatIsNotABooleanIsAnError() {
        val code = "for x <- [1], uniq: 1, do: x"

        assertEvery(code, "error for_invalid_uniq `$code`")
    }

    fun testAUniqOfAVariableIsAnError() {
        val code = "for x <- [1], uniq: y, do: x"

        assertEvery("y = true\n$code", "error for_invalid_uniq `$code`")
    }

    fun testAGeneratorsErrorComesBeforeTheOptionsValidation() =
        assertEvery("for x <- _, uniq: 1, do: x", "error unbound_underscore `_`")

    fun testAReduceWithAnIntoIsAnError() {
        val code = "for x <- [1], reduce: 0, into: [] do\nacc -> acc\nend"

        assertEvery(code, "error for_conflicting_reduce_into_uniq `$code`")
    }

    fun testAReduceWithAFalseUniqIsAnError() {
        val code = "for x <- [1], reduce: 0, uniq: false do\nacc -> acc\nend"

        assertEvery(code, "error for_conflicting_reduce_into_uniq `$code`")
    }

    fun testAnUnknownOptionIsAnErrorBeforeAnythingIsExpanded() {
        val code = "for x <- _, foo: 1, do: x"

        assertEvery(code, "error unsupported_option `$code`")
    }

    fun testAnOptionWhoseKeyIsNotAnAtomIsAnError() {
        val code = "for x <- [1], [{y, 1}], do: x"

        assertEvery(code, "error unsupported_option `$code`")
    }

    fun testAForWithoutDoIsAnErrorBeforeAnythingIsExpanded() = assertEvery("for x <- _", "error missing_option `for x <- _`")

    fun testAForWithoutDoButWithOptionsIsAnError() {
        val code = "for x <- [1], into: []"

        assertEvery(code, "error missing_option `$code`")
    }

    fun testAForThatDoesNotStartWithAGeneratorIsAnError() {
        val code = "for x = [1],\ny <- x, do: y"

        assertEvery(code, "error for_generator_start `$code`")
    }

    fun testAForWithoutGeneratorsIsAnError() = assertEvery("for do: 1", "error for_generator_start `for do: 1`")

    fun testClausesWithoutReduceAreAnError() {
        val code = "for x <- [1] do\na -> a\nend"

        assertEvery(code, "error for_without_reduce_bad_block `$code`")
    }

    fun testAReduceWithoutClausesIsAnError() {
        val code = "for x <- [1], reduce: 0 do\nx\nend"

        assertEvery(code, "error for_with_reduce_bad_block `$code`")
    }

    fun testAReduceClauseWithTwoArgumentsIsAnError() {
        val code = "for x <- [1], reduce: 0 do\na, b -> a\nend"

        assertEvery(code, "error for_with_reduce_bad_block `$code`")
    }

    fun testAReduceClauseWithTwoArgumentsAndAGuardIsAnErrorFrom1_18() {
        val code = "for x <- [1], reduce: 0 do\na, b when true -> a\nend"

        assertSplit(code, "1.18.0-rc.0", "expanded {} next 3", "error for_with_reduce_bad_block `$code`")
    }

    fun testEachReduceClauseStartsFromTheStateAfterTheGenerators() =
        assertSplit(
            "for x <- [1], reduce: 0 do\n0 -> a = x\nacc -> {acc, a}\nend",
            "1.15.0-rc.0",
            "error undefined_function `a`",
            "error undefined_var `a`"
        )

    fun testAReduceClausesHeadPinsAndShadowsTheGenerators() =
        assertVersioned("for x <- [1], reduce: 0 do\n^x -> x\nacc -> {acc, x}\nend", "{} next 2", "{} next 3")

    fun testAForNotLastInABlock() = assertVersioned("for x <- [1], do: x\ny = 1", "{y:1} next 2", "{y:2} next 3")

    fun testADiscardedForTakesNoVersionForItsUnderscore() =
        assertVersioned("_ = for x <- [1], do: x\ny = 1", "{y:1} next 2", "{y:2} next 3")

    fun testAForLastInABlockMatchedToAnUnderscore() =
        assertVersioned("y = 1\n_ = for x <- [1], do: x", "{y:0} next 2", "{y:0} next 4")

    fun testAForInAPatternIsAnError() =
        assertEvery("for(x <- [1], do: x) = 1", "error invalid_pattern_in_match `for(x <- [1], do: x)`")

    fun testAForInAGuardIsAnError() =
        assertEvery(
            "x = 1\ncase x do\ny when for(z <- y, do: z) -> y\nend",
            "error invalid_expr_in_guard `for(z <- y, do: z)`"
        )

    /** [code] expands to `expanded [before]` before 1.20.0-rc.5, and to `expanded [from]` from it. */
    private fun assertVersioned(code: String, before: String, from: String) =
        assertSplit(code, "1.20.0-rc.5", "expanded $before", "expanded $from")
}
