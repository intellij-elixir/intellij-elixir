package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/**
 * `&`, over lowered snippets at every supported minor and at the first tag of each version difference. A capture that
 * needs no function lookup expands as the `fn` Elixir rewrites it to, whose parameters, one per distinct `&N`, each take
 * a version; one that needs a lookup isn't ported.
 */
class CaptureExpanderTest : ExpanderTestCase() {
    fun testAListCapture() = assertVersioned("&[&1]", "{} next 1", "{} next 2")

    fun testEachDistinctArgumentTakesAVersion() = assertVersioned("&[&2, &1, &2]", "{} next 2", "{} next 3")

    fun testAPairCapture() = assertVersioned("&{&1, 1}", "{} next 1", "{} next 2")

    fun testAPairOfSequentialArgumentsIsATupleSpecialForm() = assertVersioned("&{&1, &2}", "{} next 2", "{} next 3")

    fun testATupleOfOneArgument() = assertVersioned("&{&1}", "{} next 1", "{} next 2")

    fun testABitstringOfOneArgument() = assertVersioned("&<<&1>>", "{} next 1", "{} next 2")

    fun testAMapCapture() = assertVersioned("&%{a: &1}", "{} next 1", "{} next 2")

    fun testTheIdentityCapture() {
        assertVersioned("& &1", "{} next 1", "{} next 2")
        assertVersioned("&(&1)", "{} next 1", "{} next 2")
    }

    fun testACaptureReadsTheVariablesBeforeIt() = assertVersioned("x = 1\n&{&1, x}", "{x:0} next 2", "{x:0} next 3")

    fun testWhatACaptureBindsIsNotVisibleAfterIt() = assertVersioned("&[y = &1]", "{} next 2", "{} next 3")

    fun testAnFnInsideACapture() = assertVersioned("&(fn x -> &1 end)", "{} next 2", "{} next 4")

    fun testACaptureOfARemoteFunctionOnAVariable() = assertEvery("m = :lists\n&m.reverse/1", "expanded {m:0} next 1")

    fun testACaptureOfARemoteCallOfSequentialArgumentsOnAVariable() =
        assertEvery("m = :lists\n&m.reverse(&1)", "expanded {m:0} next 1")

    fun testACaptureOfARemoteCallOfOtherArgumentsOnAVariable() =
        assertEvery("m = :lists\n&m.reverse(&1, 1)", "unported `m.reverse(&1, 1)`")

    fun testACaptureArgumentOutsideACaptureIsAnError() {
        assertEvery("&1", "error capture_arg_outside_of_capture `&1`")
        assertEvery("&0", "error capture_arg_outside_of_capture `&0`")
    }

    fun testANonPositiveArgumentInsideACaptureIsAnError() =
        assertSplit(
            "&[&0]",
            "1.14.0-rc.0",
            "error unallowed_capture_arg `&0`",
            "error invalid_arity_for_capture `&0`"
        )

    fun testAnArgumentWithoutItsPredecessorIsAnError() {
        assertSplit(
            "&(&2)",
            "1.16.0-rc.0",
            "error capture_arg_without_predecessor `&(&2)`",
            "error capture_arg_without_predecessor `&2`"
        )
        assertEvery("&[&2]", "error capture_arg_without_predecessor `&[&2]`")
    }

    fun testAPairWithAGapIsAnErrorAtTheCapture() =
        assertEvery("&{&1, &3}", "error capture_arg_without_predecessor `&{&1, &3}`")

    fun testACallWithAGapIsAnErrorAtTheCaptureBefore1_16AndAtTheCallFrom() {
        val code = "&(\nfoo(&2)\n)"

        assertSplit(
            code,
            "1.16.0-rc.0",
            "error capture_arg_without_predecessor `$code`",
            "error capture_arg_without_predecessor `foo(&2)`"
        )
    }

    fun testACallWithoutArgumentsIsAnErrorAtTheCaptureBefore1_16AndAtTheCallFrom() =
        assertSplit("&foo(1)", "1.16.0-rc.0", "error invalid_args_for_capture `&foo(1)`", "error invalid_args_for_capture `foo(1)`")

    fun testARemoteCallWithoutArgumentsIsAnErrorAtTheCaptureBefore1_14_0_rc_1AndAtTheCallFrom() =
        assertSplit(
            "m = :lists\n&m.reverse(1)",
            "1.14.0-rc.1",
            "error invalid_args_for_capture `&m.reverse(1)`",
            "error invalid_args_for_capture `m.reverse(1)`"
        )

    fun testAMultiLineRemoteCallIsAnErrorOnTheCapturesLineBefore1_14_0_rc_1AndOnTheCallsFrom() =
        assertSplit(
            "x = :lists\n&(\nx.foo(1))",
            "1.14.0-rc.1",
            "error invalid_args_for_capture `&(\nx.foo(1))`",
            "error invalid_args_for_capture `x.foo(1)`"
        )

    fun testARemoteCallWithAnArgumentInItsModuleIsAnErrorAtTheCaptureBefore1_16AndAtTheCallFrom() =
        assertSplit(
            "&(&1).reverse(&3)",
            "1.16.0-rc.0",
            "error capture_arg_without_predecessor `&(&1).reverse(&3)`",
            "error capture_arg_without_predecessor `(&1).reverse(&3)`"
        )

    fun testAnEmptyListIsAnError() = assertEvery("&[]", "error invalid_args_for_capture `&[]`")

    fun testALiteralIsAnError() = assertEvery("&:a", "error invalid_args_for_capture `&:a`")

    fun testAVariableIsAnError() = assertEvery("x = 1\n&x", "error invalid_args_for_capture `&x`")

    fun testABlockIsAnError() = assertEvery("&(1; 2)", "error block_expr_in_capture `&(1; 2)`")

    fun testANestedCaptureIsAnError() = assertEvery("&[&1, &(&2)]", "error nested_capture `&(&2)`")

    fun testACaptureOfACaptureIsAnError() = assertEvery("&(&(&1))", "error nested_capture `&(&1)`")

    fun testAnArityAbove255IsAnError() {
        assertEvery("&foo/256", "error invalid_arity_for_capture `&foo/256`")
        assertEvery("&m.reverse/256", "error invalid_arity_for_capture `&m.reverse/256`")
    }

    fun testACaptureInAPatternIsAnError() {
        assertEvery("(&[&1]) = 1", "error invalid_pattern_in_match `&[&1]`")
        assertEvery("(&1) = 1", "error invalid_pattern_in_match `&1`")
        assertEvery("(&super/1) = 1", "error invalid_pattern_in_match `&super/1`")
    }

    fun testACaptureInAGuardIsAnError() =
        assertEvery("x = 1\ncase x do\ny when &[&1] -> y\nend", "error invalid_expr_in_guard `&[&1]`")

    fun testACaptureThatNeedsALookupIsUnported() {
        assertEvery("&foo/1", "unported `&foo/1`")
        assertEvery("&:erlang.abs/1", "unported `&:erlang.abs/1`")
        assertEvery("&foo(&1)", "unported `&foo(&1)`")
        assertEvery("&(&1 + &2)", "unported `&(&1 + &2)`")
        assertEvery("&super(&1)", "unported `&super(&1)`")
        assertEvery("&super/1", "unported `&super/1`")
    }

    fun testACapturesBodyIsExpandedAsAnFnBody() = assertEvery("&(&1 + 1)", "unported `&1 + 1`")

    /** A block of one expression, which source never lowers to, captures that expression. */
    fun testABlockOfOneExpression() {
        val code = "&[&1]"

        assertEquals(
            LEVELS.joinToString("\n") { "$it: expanded {} next " + if (isBefore(it, "1.20.0-rc.5")) 1 else 2 },
            LEVELS.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val capture = lower(code, level) as ElixirAst.Call
                val list = capture.arguments!!.single()
                val ast = ElixirAst.Call(capture.meta, capture.callee, listOf(ElixirAst.Block(list.meta, listOf(list))))

                "$version: " + render(code, Expander.expand(ast, ExState.empty(level), Env.empty(level, NO_KERNEL), level, NO_EXPORTS))
            }
        )
    }

    /** [code] expands to `expanded [before]` before 1.20.0-rc.5, and to `expanded [from]` from it. */
    private fun assertVersioned(code: String, before: String, from: String) =
        assertSplit(code, "1.20.0-rc.5", "expanded $before", "expanded $from")
}
