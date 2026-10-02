package org.elixir_lang.expander

import org.elixir_lang.expander.DirectiveFixtures.BMP
import org.elixir_lang.expander.DirectiveFixtures.SUPPLEMENTARY
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * Each ported clause of `elixir_expand`, `elixir_clauses` and `elixir_map`, over lowered snippets at every supported
 * minor, and at the first tag of each version difference.
 */
class ExpanderTest : ExpanderTestCase() {
    fun testLiteralsLeaveTheStateAlone() {
        for (literal in listOf("1", "1.5", ":a", "\"s\"", "nil", "true")) {
            assertEvery(literal, "expanded {} next 0")
        }
    }

    fun testAMatchBindsANewVariable() = assertEvery("x = 1", "expanded {x:0} next 1")

    fun testRebindingTakesANewVersion() = assertEvery("x = 1; x = 2", "expanded {x:1} next 2")

    fun testAReadKeepsTheVersion() = assertEvery("x = 1; y = x", "expanded {x:0 y:1} next 2")

    fun testAVariableRepeatedInAPatternIsOneBinding() = assertEvery("{x, x} = {1, 1}", "expanded {x:0} next 1")

    fun testAVariableBoundBeforeTheMatchIsOverriddenOnce() =
        assertEvery("x = 1; {x, y} = {x, 2}", "expanded {x:1 y:2} next 3")

    fun testATuplePattern() = assertEvery("{a, b, c} = {1, 2, 3}", "expanded {a:0 b:1 c:2} next 3")

    fun testAConsPattern() = assertEvery("[a | b] = [1, 2]", "expanded {a:0 b:1} next 2")

    fun testAListPattern() = assertEvery("[a, b] = [1, 2]", "expanded {a:0 b:1} next 2")

    fun testEmptyParentheses() = assertEvery("()", "expanded {} next 0")

    fun testANestedBlockThreadsItsVariablesOut() =
        assertEvery("(a = 1; b = a); a", "expanded {a:0 b:1} next 2")

    fun testANestedMatchExpandsTheRightSideFirst() = assertEvery("x = y = 1", "expanded {x:1 y:0} next 2")

    fun testUnderscoreTakesAVersionInAPatternFrom1_20() =
        assertSplit("_ = 1", "1.20.0-rc.5", "expanded {} next 0", "expanded {} next 1")

    fun testUnderscoreOutsideAPatternIsAnError() = assertEvery("_", "error unbound_underscore `_`")

    fun testAPinReadsTheVariableFromBeforeTheMatch() =
        assertEvery("x = 1; {x, ^x} = {2, 1}", "expanded {x:1} next 2")

    fun testAPinOfAVariableBoundInTheSameMatchIsAnError() =
        assertEvery("{x, ^x} = {1, 1}", "error undefined_var_pin `x`")

    fun testAPinReadsTheVariablesFromBeforeTheRightSide() =
        assertEvery("^y = (y = 1)", "error undefined_var_pin `y`")

    fun testAPinOfAnUndefinedVariableIsAnError() = assertEvery("^x = 1", "error undefined_var_pin `x`")

    fun testAnErrorEndsTheExpansion() = assertEvery("{^y, w} = {1, 2}", "error undefined_var_pin `y`")

    fun testAPinOfANonVariableIsAnError() = assertEvery("^1 = 1", "error invalid_arg_for_pin `^1`")

    fun testAPinOutsideAMatchIsAnError() = assertEvery("x = 1; ^x", "error pin_outside_of_match `^x`")

    fun testAPinnedVariable() = assertEvery("x = 1; ^x = 1", "expanded {x:0} next 1")

    fun testAPinnedMapKey() =
        assertEvery("key = :k; %{^key => v} = %{key => 1}", "expanded {key:0 v:1} next 2")

    fun testAMatchInsideAPatternTakesTheLeftSideFirstFrom1_18() =
        assertSplit("y = 1; (x = y) = 1", "1.18.0-rc.0", "expanded {x:2 y:1} next 3", "expanded {x:1 y:2} next 3")

    fun testAMatchInsideATuplePattern() =
        assertSplit("{a = b, c} = {1, 2}", "1.18.0-rc.0", "expanded {a:1 b:0 c:2} next 3", "expanded {a:0 b:1 c:2} next 3")

    fun testABindingInATupleElementShowsAfterTheTuple() =
        assertEvery("t = {x = 1, 2}", "expanded {t:1 x:0} next 2")

    fun testABindingInAListElementShowsAfterTheList() = assertEvery("l = [x = 1]", "expanded {l:1 x:0} next 2")

    fun testBindingsInSiblingElementsAllShowAfterTheTuple() =
        assertEvery("{x = 1, y = 2}", "expanded {x:0 y:1} next 2")

    fun testABindingIsNotVisibleToItsSiblingElement() =
        assertSplit("{x = 1, x}", "1.15.0-rc.0", "error undefined_function `x`", "error undefined_var `x`")

    fun testABindingInAMapValueShowsAfterTheMap() = assertEvery("%{k: x = 1}", "expanded {x:0} next 1")

    fun testABindingInAMapUpdateShowsAfterTheMap() =
        assertEvery("m = %{k: 1}; %{m | k: v = 2}", "expanded {m:0 v:1} next 2")

    fun testAMapUpdate() = assertEvery("m = %{k: 1}; y = %{m | k: 2}", "expanded {m:0 y:1} next 2")

    fun testAMapUpdateInAPatternIsAnError() =
        assertEvery("m = %{k: 1}; %{m | k: v} = m", "error update_syntax_in_wrong_context `%{m | k: v}`")

    fun testAMapPattern() = assertEvery("%{k: x} = %{k: 1}", "expanded {x:0} next 1")

    fun testARepeatedKeyInAMapPatternIsAnError() =
        assertEvery("%{k: a, k: b} = %{k: 1}", "error repeated_key `%{k: a, k: b}`")

    fun testARepeatedKeyInAMapPatternIsComparedAsExpanded() =
        assertEvery(
            "%{{(), 1} => a, {nil, 1} => b} = %{}",
            "error repeated_key `%{{(), 1} => a, {nil, 1} => b}`"
        )

    fun testARepeatedKeyInAMapPatternIsComparedAsExpandedInsideAList() =
        assertEvery("%{[()] => a, [nil] => b} = %{}", "error repeated_key `%{[()] => a, [nil] => b}`")

    fun testARepeatedKeyOutsideAPatternOnlyWarns() = assertEvery("%{k: 1, k: 2}", "expanded {} next 0")

    fun testAVariableAsAMapKeyInAPatternIsAnError() =
        assertEvery("x = 1; %{x => 1} = %{1 => 1}", "error invalid_variable_in_map_key_match `%{x => 1}`")

    fun testANonPairInAMapIsAnErrorFrom1_17() =
        assertLevels("%{1}", LEVELS.filterNot { isBefore(it, "1.17.0") }) { "error not_kv_pair `%{1}`" }

    fun testAPinnedMapKeyOfABoundVariable() =
        assertEvery("x = 1; %{^x => v} = %{1 => 2}", "expanded {v:1 x:0} next 2")

    fun testAPinNestedInAMapKeyInAPatternIsAllowedFrom1_14() =
        assertSplit(
            "a = 1; %{{^a, 1} => v} = %{{1, 1} => 2}",
            "1.14.0-rc.0",
            "error invalid_pin_in_map_key_match `%{{^a, 1} => v}`",
            "expanded {a:0 v:1} next 2"
        )

    fun testAZeroFloatInAPattern() = assertEvery("0.0 = 0.0", "expanded {} next 0")

    fun testRecursiveVariablesInAPatternAreAnErrorFrom1_18() {
        val code = "{x = y, x = {:ok, y}} = {{:ok, 1}, {:ok, 1}}"

        assertSplit(code, "1.18.0-rc.0", "expanded {x:1 y:0} next 2", "error recursive `$code`")
    }

    fun testAMatchOfAVariableWithItselfInAPatternIsRecursiveFrom1_18() =
        assertSplit("(x = x) = 1", "1.18.0-rc.0", "expanded {x:0} next 1", "error recursive `(x = x) = 1`")

    fun testAVariableRepeatedInAPatternInAnElementIsWrittenAtTheNextVersionOn1_18And1_19() =
        assertWindow(
            "t = [{x, x} = {1, 1}]",
            "1.18.0-rc.0",
            "1.20.0-rc.5",
            "expanded {t:1 x:0} next 2",
            "expanded {t:1 x:1} next 2"
        )

    fun testVariablesDefinedTogetherAreNotACycle() =
        assertSplit(
            "(foo = {bar = {baz, bat}}) = {{1, 2}}",
            "1.18.0-rc.0",
            "expanded {bar:2 bat:1 baz:0 foo:3} next 4",
            "expanded {bar:1 bat:3 baz:2 foo:0} next 4"
        )

    fun testAnUndefinedVariableIsALocalCallBefore1_15AndAnErrorFrom() =
        assertSplit("x", "1.15.0-rc.0", "error undefined_function `x`", "error undefined_var `x`")

    fun testAStrayArrowIsAnError() = assertEvery("(x -> y)", "error unhandled_arrow_op `x -> y`")

    fun testAStrayTypeOperatorIsAnErrorFrom1_15() =
        assertSplit(
            "(1 :: 2)",
            "1.15.0-rc.0",
            "error undefined_function `1 :: 2`",
            "error unhandled_type_op `1 :: 2`",
        )

    fun testAStrayConsOperatorIsAnErrorFrom1_15() =
        assertSplit(
            "(1 | 2)",
            "1.15.0-rc.0",
            "error undefined_function `1 | 2`",
            "error unhandled_cons_op `1 | 2`",
        )

    fun testACallOfACallIsInvalid() = assertEvery("unquote(1)(2)", "error invalid_call `unquote(1)(2)`")

    fun testARemoteCallOnALiteralIsInvalid() {
        assertEvery("1.foo()", "error invalid_call `1.foo()`")
        assertEvery("\"a\".foo()", "error invalid_call `\"a\".foo()`")
    }

    fun testACursorIsAnErrorFrom1_17() =
        assertSplit(
            "__cursor__()",
            "1.17.0-rc.0",
            "error undefined_function `__cursor__()`",
            "error __cursor__ `__cursor__()`",
        )

    fun testParallelBitstringPatternsAreAnErrorBefore1_18() =
        assertSplit(
            "<<x>> = <<y>> = <<1>>",
            "1.18.0-rc.0",
            "error parallel_bitstring_match `<<y>>`",
            "expanded {x:1 y:0} next 2"
        )

    fun testParallelBitstringPatternsInsideAPatternAreAnErrorBefore1_18() =
        assertSplit(
            "{<<x>> = <<y>>} = {<<1>>}",
            "1.18.0-rc.0",
            "error parallel_bitstring_match `<<y>>`",
            "expanded {x:0 y:1} next 2"
        )

    fun testParallelMapPatternsWithPinnedKeysAreUnportedBefore1_18() =
        assertSplit(
            "k = 1; %{^k => <<x>>} = %{^k => <<y>>} = %{1 => <<1>>}",
            "1.18.0-rc.0",
            "unported `%{^k => <<y>>}`",
            "expanded {k:0 x:2 y:1} next 3"
        )

    fun testParallelMapPatternsWithPinnedKeysAndNoBitstringsExpand() =
        assertEvery("k = 1; %{^k => x} = %{^k => y} = %{1 => 1}", "expanded {k:0 x:2 y:1} next 3")

    fun testParallelMapPatternsPairTheirFieldsInKeyOrder() =
        assertSplit(
            "%{b: <<x>>,\na: <<y>>} = %{b: <<z>>,\na: <<w>>} = %{a: <<1>>, b: <<2>>}",
            "1.18.0-rc.0",
            "error parallel_bitstring_match `<<w>>`",
            "expanded {w:1 x:2 y:3 z:0} next 4"
        )

    fun testParallelMapPatternsPairTheirAtomKeysByCodePoint() =
        assertSplit(
            "%{\"$SUPPLEMENTARY\": <<x>>,\n\"$BMP\": <<y>>} = %{\"$SUPPLEMENTARY\": <<z>>,\n\"$BMP\": <<w>>} = " +
                "%{\"$BMP\": <<1>>, \"$SUPPLEMENTARY\": <<2>>}",
            "1.18.0-rc.0",
            "error parallel_bitstring_match `<<w>>`",
            "expanded {w:1 x:2 y:3 z:0} next 4"
        )

    fun testABitstringSpecInAMapKeyPatternIsNotAVariable() {
        assertEvery("%{<<1::integer>> => v} = %{<<1>> => 2}", "expanded {v:0} next 1")
        assertEvery("%{<<1::size(8)-unit(1)>> => v} = %{<<1>> => 2}", "expanded {v:0} next 1")
    }

    fun testTheEnvironmentNamesAreNotVariables() {
        for (name in listOf("__MODULE__", "__DIR__")) {
            assertEvery("l = [1]; [$name | t] = l", "expanded {l:0 t:1} next 2")
            assertEvery(name, "expanded {} next 0")
        }
        assertEvery("__ENV__", "expanded {} next 0")
        assertEvery("__CALLER__", "error caller_not_allowed `__CALLER__`")
        assertSplit(
            "l = [1]; [__CALLER__ | t] = l",
            "1.13.0-rc.0",
            "error caller_not_allowed `__CALLER__`",
            "error invalid_pattern_in_match `__CALLER__`",
        )
        assertSplit(
            "l = [1]; [__ENV__ | t] = l",
            "1.13.0-rc.0",
            "error env_not_allowed `__ENV__`",
            "error invalid_pattern_in_match `__ENV__`",
        )
    }

    fun testALocalCallWithNoImportIsUndefined() = assertEvery("foo(1)", "error undefined_function `foo(1)`")

    fun testUnderscoreInAConsPattern() =
        assertSplit("[_ | t] = [1, 2]", "1.20.0-rc.5", "expanded {t:0} next 1", "expanded {t:1} next 2")

    fun testUnderscoreTakesNoVersionWhileARepeatedVariableIsWrittenAtTheNextVersion() {
        val written = ElixirLanguageLevel.of("1.18.0-rc.0").elixir
        val taken = ElixirLanguageLevel.of("1.20.0-rc.5").elixir

        assertLevels(
            "t = [{x, x, _} = {1, 1, 2}]",
            (LEVELS + listOf("1.18.0-rc.0", "1.20.0-rc.4", "1.20.0-rc.5"))
                .sortedBy { ElixirLanguageLevel.of(it).elixir }
        ) { version ->
            val elixir = ElixirLanguageLevel.of(version).elixir

            when {
                elixir < written -> "expanded {t:1 x:0} next 2"
                elixir < taken -> "expanded {t:1 x:1} next 2"
                else -> "expanded {t:2 x:0} next 3"
            }
        }
    }
}
