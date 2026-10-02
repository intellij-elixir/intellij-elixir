package org.elixir_lang.expander

/**
 * `with`, over lowered snippets at every supported minor and at the first tag of each version difference. Each `<-`
 * pattern binds for the clauses after it and the `do`, `else` sees only the variables before the `with`, and nothing
 * it binds is visible after it.
 */
class WithExpanderTest : ExpanderTestCase() {
    fun testAPatternBindsForTheDoAndNothingAfter() =
        assertVersioned("x = 1\nwith {:ok, y} <- {:ok, x} do\nz = y\nend", "{x:0} next 3", "{x:0} next 4")

    fun testAPatternBindsForTheClausesAfterIt() =
        assertVersioned(
            "with {:ok, a} <- {:ok, 1},\n{:ok, b} <- {:ok, a} do\n{a, b}\nend",
            "{} next 2",
            "{} next 3"
        )

    fun testABareClauseBindsForTheClausesAfterIt() =
        assertVersioned("with a = 1,\n{:ok, b} <- {:ok, a} do\n{a, b}\nend", "{} next 2", "{} next 3")

    fun testAVariablePattern() = assertVersioned("with a <- 1 do\na\nend", "{} next 1", "{} next 2")

    fun testWhatARightSideBindsIsVisibleAfterItOnlyBefore1_13() {
        val code = "with {:ok, a} <- {:ok, b = 1} do\nb\nend"

        assertLevels(code, LEVELS + "1.13.0-rc.0" + "1.15.0-rc.0") {
            when {
                isBefore(it, "1.13.0-rc.0") -> "expanded {} next 2"
                isBefore(it, "1.15.0-rc.0") -> "unported `b`"
                else -> "error undefined_var `b`"
            }
        }
    }

    fun testAPatternSeesTheEnvItsRightSideLeftOnlyFrom1_13() =
        assertSplit(
            "with %{Bar => a, Foo.Bar => b} <- (alias Foo.Bar; %{}) do\na\nend",
            "1.13.0-rc.0",
            "expanded {} next 2",
            "error repeated_key `%{Bar => a, Foo.Bar => b}`"
        )

    fun testAPinReadsTheClausesBeforeIt() =
        assertVersioned("with {:ok, a} <- {:ok, 1},\n^a <- 1 do\na\nend", "{} next 1", "{} next 2")

    fun testAPinCannotReadWhatItsRightSideBinds() =
        assertEvery("with ^b <- (b = 1) do\n1\nend", "error undefined_var_pin `b`")

    fun testAGuardReadsItsPattern() = assertVersioned("with x when x <- true do\nx\nend", "{} next 1", "{} next 2")

    fun testElseSeesOnlyTheVariablesBeforeTheWith() =
        assertSplit(
            "with {:ok, a} <- 1 do\na\nelse\n_ -> a\nend",
            "1.15.0-rc.0",
            "unported `a`",
            "error undefined_var `a`"
        )

    fun testEachElseClauseStartsFromTheStateBeforeTheWithAndKeepsTheVersion() =
        assertVersioned(
            "with {:ok, a} <- 1 do\na\nelse\n{:error, b} -> b\nc -> c\nend\nd = 1",
            "{d:3} next 4",
            "{d:4} next 5"
        )

    fun testAWithWithoutDoIsAnError() =
        assertEvery("with {:ok, a} <- 1", "error missing_option `with {:ok, a} <- 1`")

    fun testAClausesErrorComesBeforeTheMissingDo() = assertEvery("with x <- _", "error unbound_underscore `_`")

    fun testAnUnknownOptionIsAnError() {
        val code = "with x <- 1, do: x, foo: 1"

        assertEvery(code, "error unexpected_option `$code`")
    }

    fun testTheDosErrorComesBeforeAnUnknownOption() =
        assertEvery("with x <- 1, do: _, foo: 1", "error unbound_underscore `_`")

    fun testASecondDoIsAnUnexpectedOption() {
        val code = "with x <- 1, do: x, do: 2"

        assertEvery(code, "error unexpected_option `$code`")
    }

    fun testASecondElseIsAnUnexpectedOption() {
        val code = "with x <- 1, do: x, else: (a -> a), else: (b -> b)"

        assertEvery(code, "error unexpected_option `$code`")
    }

    fun testAListBeforeTheDoBlockIsAClauseBefore1_15AndOptionsFrom() {
        val code = "with x <- 1, [foo: 1] do\nx\nend"

        assertLevels(code, LEVELS + "1.15.0-rc.0") {
            if (isBefore(it, "1.15.0-rc.0")) "expanded {} next 1" else "error unexpected_option `$code`"
        }
    }

    fun testAnElseListBeforeTheDoBlockIsAClauseBefore1_15AndOptionsFrom() {
        val code = "with x <- 1, else: (a -> a) do\nx\nend"

        assertLevels(code, LEVELS + "1.15.0-rc.0") {
            when {
                isBefore(it, "1.15.0-rc.0") -> "error unhandled_arrow_op `a -> a`"
                isBefore(it, "1.20.0-rc.5") -> "expanded {} next 2"
                else -> "expanded {} next 3"
            }
        }
    }

    fun testAnElseThatIsNotClausesIsAnError() {
        val code = "with x <- 1 do\nx\nelse\n:ok\nend"

        assertEvery(code, "error bad_or_missing_clauses `$code`")
    }

    fun testAnElseClauseWithTwoArgumentsIsAnErrorAtTheWithBefore1_18AndAtTheClauseFrom() {
        val code = "with {:ok, x} <- 1 do\nx\nelse\na, b -> a\nend"

        assertSplit(
            code,
            "1.18.0-rc.0",
            "error wrong_number_of_args_for_clause `$code`",
            "error wrong_number_of_args_for_clause `a, b -> a`"
        )
    }

    fun testAnElseClauseWithTwoArgumentsAndAGuardIsAnError() {
        val code = "with {:ok, x} <- 1 do\nx\nelse\na, b when true -> a\nend"

        assertSplit(
            code,
            "1.18.0-rc.0",
            "error wrong_number_of_args_for_clause `$code`",
            "error wrong_number_of_args_for_clause `a, b when true -> a`"
        )
    }

    fun testACyclicPatternIsAnErrorAtItsClauseFrom1_18() =
        assertSplit(
            "with {x = y, x = {:ok, y}} <- 1 do\nx\nend",
            "1.18.0-rc.0",
            "expanded {} next 2",
            "error recursive `{x = y, x = {:ok, y}} <- 1`"
        )

    fun testAnOptionThatIsNotAPairIsUnported() = assertEvery("with x <- 1, [1, do: 2]", "unported `1`")

    fun testAWithInAPatternIsAnError() =
        assertEvery("with(x <- 1, do: x) = 1", "error invalid_pattern_in_match `with(x <- 1, do: x)`")

    fun testAWithInAGuardIsAnError() =
        assertEvery(
            "x = 1\ncase x do\ny when with(z <- y, do: z) -> y\nend",
            "error invalid_expr_in_guard `with(z <- y, do: z)`"
        )

    /** [code] expands to `expanded [before]` before 1.20.0-rc.5, and to `expanded [from]` from it. */
    private fun assertVersioned(code: String, before: String, from: String) =
        assertSplit(code, "1.20.0-rc.5", "expanded $before", "expanded $from")
}
