package org.elixir_lang.expander

/**
 * `case`, `cond`, `receive`, `try`, `fn` and `__STACKTRACE__`, over lowered snippets at every supported minor and at
 * the first tag of each version difference. Each body starts from the state before its construct, and each construct
 * takes a version once its clauses are expanded from 1.20.
 */
class ClauseExpanderTest : ExpanderTestCase() {
    fun testACaseBodySeesItsHeadAndBindsNothingAfter() =
        assertVersioned("x = 1\ncase x do\n1 -> y = 2\nend", "{x:0} next 2", "{x:0} next 3")

    fun testACaseKeepsWhatItsSubjectBinds() =
        assertVersioned("case x = 1 do\n_ -> 1\nend", "{x:0} next 1", "{x:0} next 3")

    fun testACaseBodyReadsItsHeadsVariables() = assertVersioned("case 1 do\na -> b = a\nend", "{} next 2", "{} next 3")

    fun testACaseGuardReadsItsHeadsVariables() =
        assertVersioned("x = true\ncase x do\ny when y -> y\nend", "{x:0} next 2", "{x:0} next 3")

    fun testEachCaseClauseStartsFromTheStateBeforeItAndKeepsTheVersion() =
        assertVersioned("case 1 do\n1 -> a = 1\n_ -> b = 2\nend\nc = 3", "{c:2} next 3", "{c:4} next 5")

    fun testACaseWithoutOptionsIsAnError() = assertEvery("case 1, []", "error missing_option `case 1, []`")

    fun testACaseWhoseOptionsAreNotAListIsAnError() = assertEvery("case 1, 2", "error invalid_args `case 1, 2`")

    fun testACaseWithTwoDoBlocksIsAnError() {
        val code = "case 1, do: (1 -> 1), do: (2 -> 2)"

        assertEvery(code, "error duplicated_clauses `$code`")
    }

    fun testACaseWithAnElseIsAnError() {
        val code = "case 1, do: (1 -> 1), else: (2 -> 2)"

        assertEvery(code, "error unexpected_option `$code`")
    }

    fun testACaseWithoutClausesIsAnError() {
        val code = "case 1 do\n:ok\nend"

        assertEvery(code, "error bad_or_missing_clauses `$code`")
    }

    fun testACaseClauseWithTwoArgumentsIsAnErrorAtTheCaseBefore1_18AndAtTheClauseFrom() {
        val code = "case 1 do\na, b -> a\nend"

        assertSplit(code, "1.18.0-rc.0", "error wrong_number_of_args_for_clause `$code`", "error wrong_number_of_args_for_clause `a, b -> a`")
    }

    fun testACaseClauseWithTwoArgumentsAndAGuardIsAnError() {
        val code = "case 1 do\na, b when true -> a\nend"

        assertSplit(
            code,
            "1.18.0-rc.0",
            "error wrong_number_of_args_for_clause `$code`",
            "error wrong_number_of_args_for_clause `a, b when true -> a`"
        )
    }

    fun testALaterCaseClausesErrorFollowsTheEarlierClauses() {
        val code = "case 1 do\n1 -> 1\n2, 3 -> 2\nend"

        assertSplit(code, "1.18.0-rc.0", "error wrong_number_of_args_for_clause `$code`", "error wrong_number_of_args_for_clause `2, 3 -> 2`")
    }

    fun testACaseInAPatternIsAnError() =
        assertEvery("case 1 do\n_ -> 1\nend = 1", "error invalid_pattern_in_match `case 1 do\n_ -> 1\nend`")

    fun testACaseInAGuardIsAnError() =
        assertEvery(
            "x = 1\ncase x do\ny when case(y, do: (_ -> true)) -> y\nend",
            "error invalid_expr_in_guard `case(y, do: (_ -> true))`"
        )

    fun testAConditionsBindingsReachOnlyItsBody() = assertVersioned("cond do\na = 1 -> b = a\nend", "{} next 2", "{} next 3")

    fun testAConditionReadsTheVariablesBeforeTheCond() =
        assertVersioned("x = 1\ncond do\nx -> y = x\nend", "{x:0} next 2", "{x:0} next 3")

    fun testACondWithoutOptionsIsAnError() = assertEvery("cond []", "error missing_option `cond []`")

    fun testACondWhoseOptionsAreNotAListIsAnError() = assertEvery("cond 1", "error invalid_args `cond 1`")

    fun testACondWithTwoDoBlocksIsAnError() {
        val code = "cond do: (1 -> 1), do: (2 -> 2)"

        assertEvery(code, "error duplicated_clauses `$code`")
    }

    fun testACondWithAnElseIsAnError() {
        val code = "cond do: (1 -> 1), else: (2 -> 2)"

        assertEvery(code, "error unexpected_option `$code`")
    }

    fun testACondWithoutClausesIsAnError() {
        val code = "cond do\n:ok\nend"

        assertEvery(code, "error bad_or_missing_clauses `$code`")
    }

    fun testACondClauseWithTwoArgumentsIsAnErrorAtTheCond() {
        val code = "cond do\na, b -> a\nend"

        assertEvery(code, "error wrong_number_of_args_for_clause `$code`")
    }

    fun testAnUnderscoreLastInACondIsAnError() =
        assertEvery("cond do\ntrue -> 1\n_ -> 2\nend", "error underscore_in_cond `_ -> 2`")

    fun testACondInAPatternIsAnError() =
        assertEvery("cond do\ntrue -> 1\nend = 1", "error invalid_pattern_in_match `cond do\ntrue -> 1\nend`")

    fun testAnEmptyCondIsUnported() = assertEvery("cond do: []", "unported `cond do: []`")

    fun testAReceiveBodySeesItsHeadAndTheAfterBodyItsTimeout() =
        assertVersioned("receive do\nm -> n = m\nafter\n0 -> t = 1\nend", "{} next 3", "{} next 4")

    fun testAnEmptyReceiveDoIsSkipped() =
        assertVersioned("receive do\nafter\n0 -> 1\nend", "{} next 0", "{} next 1")

    fun testAnAfterTimeoutsBindingsReachOnlyItsBody() =
        assertVersioned("receive do\nafter\n(t = 0) -> u = t\nend", "{} next 2", "{} next 3")

    fun testAReceiveWithoutOptionsIsAnError() = assertEvery("receive []", "error missing_option `receive []`")

    fun testAReceiveWhoseOptionsAreNotAListIsAnError() = assertEvery("receive 1", "error invalid_args `receive 1`")

    fun testAReceiveWithTwoAfterClausesIsAnError() {
        val code = "receive do\nafter\n0 -> 1\n1 -> 2\nend"

        assertEvery(code, "error multiple_after_clauses_in_receive `$code`")
    }

    fun testAReceiveWithTwoDoBlocksIsAnError() {
        val code = "receive do: (a -> a), do: (b -> b), after: (0 -> 1)"

        assertEvery(code, "error duplicated_clauses `$code`")
    }

    fun testAReceiveWithAnElseIsAnError() {
        val code = "receive do: (a -> a), else: (b -> b), after: (0 -> 1)"

        assertEvery(code, "error unexpected_option `$code`")
    }

    fun testAReceiveClauseWithTwoArgumentsIsAnErrorAtTheReceiveBefore1_18AndAtTheClauseFrom() {
        val code = "receive do\na, b -> a\nafter\n0 -> 1\nend"

        assertSplit(code, "1.18.0-rc.0", "error wrong_number_of_args_for_clause `$code`", "error wrong_number_of_args_for_clause `a, b -> a`")
    }

    fun testAnAfterClauseWithTwoArgumentsIsAnErrorAtTheReceive() {
        val code = "receive do\nafter\n0, 1 -> 1\nend"

        assertEvery(code, "error wrong_number_of_args_for_clause `$code`")
    }

    fun testAReceiveWithoutClausesIsAnError() {
        val code = "receive do\n:ok\nafter\n0 -> 1\nend"

        assertEvery(code, "error bad_or_missing_clauses `$code`")
    }

    fun testEachPartOfATryStartsFromTheStateBeforeIt() =
        assertVersioned(
            "try do\nd = 1\nrescue\ne -> e\ncatch\nk, v -> v\nelse\nr -> r\nafter\naf = 1\nend",
            "{} next 6",
            "{} next 7"
        )

    fun testATryReadsTheVariablesBeforeIt() =
        assertVersioned("x = 1\ntry do\ny = x\nafter\nz = x\nend", "{x:0} next 3", "{x:0} next 4")

    fun testATrysElseDoesNotSeeItsDo() =
        assertSplit(
            "try do\na = 1\nrescue\n_ -> 1\nelse\nb -> {b, a}\nend",
            "1.15.0-rc.0",
            "error undefined_function `a`",
            "error undefined_var `a`"
        )

    fun testARescueOfAVariableInAListOfAtoms() =
        assertVersioned("try do\n1\nrescue\ne in [:\"Elixir.ArgumentError\"] -> e\nend", "{} next 1", "{} next 2")

    fun testARescueOfAVariableInAnAtom() =
        assertVersioned("try do\n1\nrescue\ne in :\"Elixir.ArgumentError\" -> e\nend", "{} next 1", "{} next 2")

    fun testARescueOfAListOfAtoms() =
        assertVersioned("try do\n1\nrescue\n[:\"Elixir.ArgumentError\"] -> 1\nend", "{} next 0", "{} next 2")

    fun testARescueOfAVariableInAConsListIsAnError() =
        assertEvery(
            "try do\n1\nrescue\ne in [:a | [:b]] -> e\nend",
            "error invalid_rescue_clause `e in [:a | [:b]] -> e`"
        )

    fun testARescueOfAConsListIsAnError() =
        assertEvery("try do\n1\nrescue\n[:a | [:b]] -> 1\nend", "error invalid_rescue_clause `[:a | [:b]] -> 1`")

    fun testARescueOfAVariableInUnderscore() =
        assertVersioned("try do\n1\nrescue\ne in _ -> e\nend", "{} next 1", "{} next 2")

    fun testARescueOfAnAlias() =
        assertVersioned("try do\n1\nrescue\nArgumentError -> 1\nend", "{} next 0", "{} next 2")

    fun testARescueOfAVariableInAnAlias() =
        assertVersioned("try do\n1\nrescue\ne in ArgumentError -> e\nend", "{} next 1", "{} next 2")

    /** Before 1.15 the call is what `_ in` reads; from 1.15 it is expanded once as a macro, and isn't one. */
    fun testARescueOfACall() =
        assertSplit(
            "try do\n1\nrescue\nfoo() -> 1\nend",
            "1.15.0-rc.0",
            "error undefined_function `foo()`",
            "error invalid_rescue_clause `foo() -> 1`",
        )

    fun testARescueOfAVariableInTheModule() =
        assertVersioned("try do\n1\nrescue\ne in __MODULE__ -> e\nend", "{} next 1", "{} next 2")

    fun testARescueOfALiteralIsAnError() =
        assertEvery("try do\n1\nrescue\n1 -> 1\nend", "error invalid_rescue_clause `1 -> 1`")

    fun testARescueOfAVariableInAVariableIsAnError() =
        assertEvery("x = 1\ntry do\n1\nrescue\ne in x -> e\nend", "error invalid_rescue_clause `e in x -> e`")

    fun testARescueOfATupleInAListIsAnError() =
        assertEvery("try do\n1\nrescue\n{a} in [:b] -> a\nend", "error invalid_rescue_clause `{a} in [:b] -> a`")

    fun testARescueClauseWithTwoArgumentsIsAnErrorAtTheClause() =
        assertEvery("try do\n1\nrescue\na, b -> 1\nend", "error wrong_number_of_args_for_clause `a, b -> 1`")

    fun testACatchOfOneArgumentCatchesAThrow() =
        assertVersioned("try do\n1\ncatch\nk -> k\nend", "{} next 1", "{} next 2")

    fun testACatchOfTwoArgumentsWithAGuard() =
        assertVersioned("try do\n1\ncatch\nk, v when v -> 1\nend", "{} next 2", "{} next 3")

    fun testACatchOfOneArgumentWithAGuard() =
        assertVersioned("try do\n1\ncatch\nv when v -> 1\nend", "{} next 1", "{} next 2")

    fun testACatchOfThreeArgumentsIsAnErrorAtTheClause() =
        assertEvery("try do\n1\ncatch\na, b, c -> 1\nend", "error wrong_number_of_args_for_clause `a, b, c -> 1`")

    fun testACatchOfThreeArgumentsWithAGuardIsAnErrorFrom1_18() =
        assertSplit(
            "try do\n1\ncatch\na, b, c when c -> 1\nend",
            "1.18.0-rc.0",
            "expanded {} next 3",
            "error wrong_number_of_args_for_clause `a, b, c when c -> 1`"
        )

    fun testATryWithOnlyADoIsAnError() =
        assertEvery("try do\n1\nend", "error missing_option `try do\n1\nend`")

    fun testATryWithoutOptionsIsAnError() = assertEvery("try []", "error missing_option `try []`")

    fun testATryWhoseOptionsAreNotAListIsAnError() = assertEvery("try 1", "error invalid_args `try 1`")

    fun testATryWithTwoDoBlocksIsAnError() = assertEvery("try do: 1, do: 2", "error duplicated_clauses `try do: 1, do: 2`")

    fun testATryWithAnUnknownOptionIsAnError() {
        val code = "try do: 1, foo: (a -> a)"

        assertEvery(code, "error unexpected_option `$code`")
    }

    fun testATryElseClauseWithTwoArgumentsIsAnErrorAtTheTryBefore1_18AndAtTheClauseFrom() {
        val code = "try do\n1\nrescue\n_ -> 1\nelse\na, b -> 1\nend"

        assertSplit(code, "1.18.0-rc.0", "error wrong_number_of_args_for_clause `$code`", "error wrong_number_of_args_for_clause `a, b -> 1`")
    }

    fun testATryInAPatternIsAnError() =
        assertEvery("try do\n1\nafter\n2\nend = 1", "error invalid_pattern_in_match `try do\n1\nafter\n2\nend`")

    fun testATryWithoutRescueClausesIsAnError() {
        val code = "try do\n1\nrescue\n:ok\nend"

        assertEvery(code, "error bad_or_missing_clauses `$code`")
    }

    fun testTheStacktraceInARescue() =
        assertVersioned("try do\n1\nrescue\n_ -> s = __STACKTRACE__\nend", "{} next 1", "{} next 3")

    fun testTheStacktraceInACatch() =
        assertVersioned("try do\n1\ncatch\n_ -> s = __STACKTRACE__\nend", "{} next 1", "{} next 3")

    fun testTheStacktraceInAnFnInARescue() =
        assertVersioned("try do\n1\nrescue\n_ -> fn -> __STACKTRACE__ end\nend", "{} next 0", "{} next 3")

    fun testTheStacktraceInARescueInARescue() =
        assertVersioned(
            "try do\n1\nrescue\n_ ->\ntry do\n2\nrescue\n_ -> s = __STACKTRACE__\nend\nend",
            "{} next 1",
            "{} next 5"
        )

    fun testTheStacktraceOutsideATryIsAnError() =
        assertEvery("__STACKTRACE__", "error stacktrace_not_allowed `__STACKTRACE__`")

    fun testTheStacktraceAfterATryIsAnError() =
        assertEvery("try do\n1\nrescue\n_ -> 1\nend\n__STACKTRACE__", "error stacktrace_not_allowed `__STACKTRACE__`")

    fun testTheStacktraceInATrysDoElseOrAfterIsAnError() {
        assertEvery("try do\n__STACKTRACE__\nrescue\n_ -> 1\nend", "error stacktrace_not_allowed `__STACKTRACE__`")
        assertEvery(
            "try do\n1\nrescue\n_ -> 1\nelse\n_ -> __STACKTRACE__\nend",
            "error stacktrace_not_allowed `__STACKTRACE__`"
        )
        assertEvery(
            "try do\n1\nrescue\n_ -> 1\nafter\n__STACKTRACE__\nend",
            "error stacktrace_not_allowed `__STACKTRACE__`"
        )
    }

    fun testTheStacktraceInAPatternIsAnErrorFrom1_13() =
        assertSplit(
            "__STACKTRACE__ = 1",
            "1.13.0-rc.0",
            "error stacktrace_not_allowed `__STACKTRACE__`",
            "error invalid_pattern_in_match `__STACKTRACE__`"
        )

    fun testTheStacktraceInAPatternInARescueExpandsBefore1_13() =
        assertSplit(
            "try do\n1\nrescue\n_ -> __STACKTRACE__ = 1\nend",
            "1.13.0-rc.0",
            "expanded {} next 0",
            "error invalid_pattern_in_match `__STACKTRACE__`"
        )

    fun testAnFnBodySeesItsParametersAndTheOuterVariables() =
        assertVersioned("x = 1\nf = fn a -> b = {a, x} end", "{f:3 x:0} next 4", "{f:4 x:0} next 5")

    fun testAnFnParameterShadowsAnOuterVariable() =
        assertVersioned("a = 1\nf = fn a -> a end", "{a:0 f:2} next 3", "{a:0 f:3} next 4")

    fun testAnFnGuard() = assertVersioned("f = fn a when a -> a end", "{f:1} next 2", "{f:2} next 3")

    fun testEachFnClauseStartsFromTheStateBeforeIt() =
        assertVersioned("f = fn\n1 -> a = 1\n_ -> b = 2\nend\nc = 3", "{c:3 f:2} next 4", "{c:5 f:4} next 6")

    fun testFnClausesOfOneArityWithAndWithoutAGuard() =
        assertVersioned("f = fn\na, b when a -> 1\nc, d -> 2\nend", "{f:4} next 5", "{f:5} next 6")

    fun testAnFnWithADefaultIsAnError() =
        assertEvery("fn a \\\\ 1 -> a end", "error defaults_in_args `fn a \\\\ 1 -> a end`")

    fun testAnFnWithADefaultInALaterClauseIsAnError() {
        val code = "fn\na -> a\nb \\\\ 1 -> b\nend"

        assertEvery(code, "error defaults_in_args `$code`")
    }

    fun testAnFnWithClausesOfTwoAritiesIsAnError() {
        val code = "fn\na -> a\na, b -> b\nend"

        assertEvery(code, "error clauses_with_different_arities `$code`")
    }

    fun testAnFnClausesErrorComesBeforeTheArityCheck() =
        assertEvery("fn\na -> a\nb, c -> case 1, 2\nend", "error invalid_args `case 1, 2`")

    fun testAnFnInAPatternIsAnError() = assertEvery("fn -> 1 end = 1", "error invalid_pattern_in_match `fn -> 1 end`")

    fun testAnFnInAGuardIsAnError() =
        assertEvery("x = 1\ncase x do\ny when fn -> 1 end -> y\nend", "error invalid_expr_in_guard `fn -> 1 end`")

    fun testEachConstructTakesAVersionFrom1_20() {
        for (construct in listOf(
            "case 1 do 1 -> 1 end",
            "cond do true -> 1 end",
            "receive do after 0 -> 1 end",
            "try do 1 after 2 end",
            "fn -> 1 end",
        )) {
            val code = "t = [{x, x} = {1, 1}, $construct]"

            assertLevels(code, (LEVELS + listOf("1.18.0-rc.0", "1.20.0-rc.4", "1.20.0-rc.5")).sortedBy { level(it) }) {
                when {
                    isBefore(it, "1.18.0-rc.0") -> "expanded {t:1 x:0} next 2"
                    isBefore(it, "1.20.0-rc.5") -> "expanded {t:1 x:1} next 2"
                    else -> "expanded {t:2 x:0} next 3"
                }
            }
        }
    }

    /** [code] expands to `expanded [before]` before 1.20.0-rc.5, and to `expanded [from]` from it. */
    private fun assertVersioned(code: String, before: String, from: String) =
        assertSplit(code, "1.20.0-rc.5", "expanded $before", "expanded $from")

    private fun level(version: String) = org.elixir_lang.language_level.ElixirLanguageLevel.of(version).elixir
}
