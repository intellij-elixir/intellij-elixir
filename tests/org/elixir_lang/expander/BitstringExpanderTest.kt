package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/** `elixir_bitstring:expand`, over lowered snippets at every supported minor and at each version difference. */
class BitstringExpanderTest : ExpanderTestCase() {
    fun testASizedSegmentPattern() = assertEvery("<<x::8>> = <<1>>", "expanded {x:0} next 1")

    fun testABinaryTailPattern() = assertEvery("<<a, rest::binary>> = \"ab\"", "expanded {a:0 rest:1} next 2")

    fun testASizeReadsAVariableFromBeforeThePattern() =
        assertEvery("n = 8; <<x::size(n)>> = <<1>>", "expanded {n:0 x:1} next 2")

    fun testASizeReadsAVariableAnEarlierSegmentBinds() =
        assertEvery("<<n, x::size(n)>> = <<8, 1>>", "expanded {n:0 x:1} next 2")

    fun testAPinnedSegment() = assertEvery("x = 1; <<^x>> = <<1>>", "expanded {x:0} next 1")

    fun testAFloatSegment() = assertEvery("<<x::float>> = <<1.0::float>>", "expanded {x:0} next 1")

    fun testASizeAndUnit() = assertEvery("n = 1; <<x::size(n)-unit(8)>> = <<1>>", "expanded {n:0 x:1} next 2")

    fun testASizeTimesUnit() = assertEvery("<<x::8*4>> = <<1, 2, 3, 4>>", "expanded {x:0} next 1")

    fun testTheSameSizeTwice() = assertEvery("<<x::size(8)-size(8)>> = <<1>>", "expanded {x:0} next 1")

    fun testATypedNestedBitstring() = assertEvery("<< <<x>>::binary >> = \"a\"", "expanded {x:0} next 1")

    fun testALiteralInterpolationInAPattern() =
        assertEvery("<<\"foo#{\"bar\"}\", rest::binary>> = \"foobarbaz\"", "expanded {rest:0} next 1")

    fun testAVariableInterpolationInAPatternIsUnported() =
        assertEvery("x = \"a\"; \"#{x}\" = \"a\"", "unported `#{x}`")

    fun testAnUnknownSpecIsUnported() = assertEvery("<<x::foo>> = <<1>>", "unported `foo`")

    fun testTwoSizesThatAreNotLiteralsAreUnported() =
        assertEvery("n = 8; <<x::size(n)-size(n)>> = <<1>>", "unported `size(n)`")

    fun testAConstruction() = assertEvery("x = 1; y = <<x::size(8), 2>>", "expanded {x:0 y:1} next 2")

    fun testABindingInAConstructionShowsAfterIt() =
        assertSplit("_ = <<(x = 8)>>; x", "1.20.0-rc.5", "expanded {x:0} next 1", "expanded {x:0} next 2")

    fun testASegmentOfAConstructionDoesNotSeeItsSiblingsBinding() =
        assertSplit("<<(x = 1), x>>", "1.15.0-rc.0", "unported `x`", "error undefined_var `x`")

    fun testASizeInAConstructionReadsOnlyWhatTheBitstringCould() =
        assertSplit("<<(x = 8), 1::size(x)>>", "1.15.0-rc.0", "unported `x`", "error undefined_var `x`")

    fun testABindingInAConstructionSizeIsKeptOnlyBefore1_19() =
        assertLevels("_ = <<1::size(y = 8)>>; y", LEVELS) { version ->
            when {
                isBefore(version, "1.14.0-rc.0") -> "error bad_size_argument `1::size(y = 8)`"
                isBefore(version, "1.19.0-rc.1") -> "expanded {y:0} next 1"
                else -> "error undefined_var `y`"
            }
        }

    fun testASizeSeesItsOwnSegmentOnlyBefore1_19() =
        assertSplit("<<n::size(n)>> = <<8>>", "1.19.0-rc.1", "expanded {n:0} next 1", "error undefined_var `n`")

    fun testASizeOfTheSameNameAsItsSegment() =
        assertEvery("foo = 8; <<foo::size(foo)>> = <<8>>", "expanded {foo:1} next 2")

    fun testAnUnboundSizeIsAnError() = assertEvery("<<x::size(n)>> = <<1>>", "error undefined_var `n`")

    fun testASizeCannotReadALaterSegment() = assertEvery("<<x::size(n), n>> = <<1, 8>>", "error undefined_var `n`")

    fun testASizeCannotReadAVariableTheSamePatternBoundOutsideTheBitstring() =
        assertSplit(
            "{n, <<x::size(n)>>} = {8, <<1>>}",
            "1.14.0-rc.0",
            "error undefined_var_in_spec `x::size(n)`",
            "error undefined_var `n`"
        )

    fun testAPinnedSizeFrom1_15() =
        assertSplit(
            "n = 8; <<y::size(^n)>> = <<1>>",
            "1.15.0-rc.1",
            "error pin_outside_of_match `^n`",
            "expanded {n:0 y:1} next 2"
        )

    fun testAPinnedSizeOfAVariableTheSamePatternBinds() =
        assertSplit(
            "<<n, x::size(^n)>> = <<8, 1>>",
            "1.15.0-rc.1",
            "error pin_outside_of_match `^n`",
            "error undefined_var_pin `n`"
        )

    fun testAMatchInASize() =
        assertSplit(
            "<<x::size(a = 8)>> = <<1>>",
            "1.14.0-rc.0",
            "error bad_size_argument `x::size(a = 8)`",
            "error invalid_expr_in_bitsize `a = 8`"
        )

    fun testALiteralSizeThatIsNotAnIntegerBefore1_14() =
        assertSplit(
            "<<x::size(:a)>> = <<1>>",
            "1.14.0-rc.0",
            "error bad_size_argument `x::size(:a)`",
            "expanded {x:0} next 1"
        )

    fun testUnderscoreInASize() = assertEvery("<<x::size(_)>> = <<1>>", "error unbound_underscore `_`")

    fun testAnUnsizedBinaryBeforeTheLastSegment() =
        assertEvery("<<x::binary, y>> = \"ab\"", "error unsized_binary `x::binary`")

    fun testAnUnsizedBinaryAtTheEndOfANestedBitstring() =
        assertEvery("<<(<<x::binary>>), y>> = \"ab\"", "error unsized_binary `x::binary`")

    fun testAPinnedBinaryInfersItsSizeFrom1_16() =
        assertSplit(
            "x = \"cd\"; <<^x::binary, rest::binary>> = \"cdef\"",
            "1.16.0-rc.1",
            "error unsized_binary `^x::binary`",
            "expanded {rest:1 x:0} next 2"
        )

    fun testAPinnedBinaryLastInANestedPatternInfersItsSizeOnlyFrom1_16_0_rc_1Until1_16_3() =
        assertLevels(
            "x = \"a\"; <<(<<^x::binary>>)::binary, y>> = \"ab\"",
            (LEVELS + listOf("1.16.0-rc.1", "1.16.2")).sortedBy { ElixirLanguageLevel.of(it).elixir }
        ) { version ->
            if (isBefore(version, "1.16.0-rc.1") || !isBefore(version, "1.16.3")) {
                "error unsized_binary `^x::binary`"
            } else {
                "expanded {x:0 y:1} next 2"
            }
        }

    fun testAnUnalignedBinary() =
        assertEvery("<<(<<1::1>>)::binary>>", "error unaligned_binary `(<<1::1>>)::binary`")

    fun testAListSegmentInAPattern() =
        assertLevels("<<[1]>> = <<1>>", LEVELS) { version ->
            when {
                isBefore(version, "1.18.0-rc.0") -> "error invalid_literal `<<[1]>>`"
                isBefore(version, "1.19.0-rc.0") -> "expanded {} next 0"
                else -> "error unknown_match `<<[1]>>`"
            }
        }

    fun testAnAtomSegmentInAConstructionBefore1_18() =
        assertSplit("<<:a>>", "1.18.0-rc.0", "error invalid_literal `<<:a>>`", "expanded {} next 0")

    fun testABareSegmentReportsThePreviousBareSegmentBefore1_20() =
        assertLevels("<<x, [1]>> = <<1, 2>>", LEVELS + "1.20.0-rc.4") { version ->
            when {
                isBefore(version, "1.18.0-rc.0") -> "error invalid_literal `x`"
                isBefore(version, "1.19.0-rc.0") -> "expanded {x:0} next 1"
                isBefore(version, "1.20.0-rc.5") -> "error unknown_match `x`"
                else -> "error unknown_match `<<x, [1]>>`"
            }
        }

    fun testAMatchInsideABitstringPattern() =
        assertSplit("<<x = y>> = <<1>>", "1.19.0-rc.0", "error nested_match `<<x = y>>`", "error unknown_match `x = y`")

    fun testATupleSegmentInAPatternFrom1_19() =
        assertSplit("<<{x}>> = <<1>>", "1.19.0-rc.0", "expanded {x:0} next 1", "error unknown_match `{x}`")

    fun testConflictingTypes() =
        assertEvery("<<x::integer-float>> = <<1>>", "error bittype_mismatch `x::integer-float`")

    fun testConflictingSizes() = assertEvery("<<x::8-size(16)>> = <<1>>", "error bittype_mismatch `x::8-size(16)`")

    fun testALiteralOfTheWrongType() = assertEvery("<<1::binary>>", "error bittype_mismatch `1::binary`")

    fun testAUnitOtherThanOneOnABitstring() =
        assertEvery("<<x::bits-unit(8)>> = <<1>>", "error bittype_mismatch `x::bits-unit(8)`")

    fun testASpecThatIsNotANameIsUndefined() =
        assertEvery("<<x::\"a\">> = <<1>>", "error undefined_bittype `x::\"a\"`")

    fun testASpecTheLoweringLeftAsAPlaceholderIsUnported() {
        for (code in listOf("<<x::y>> = <<1>>", "<<x::integer-y>> = <<1>>")) {
            assertEquals(
                LEVELS.joinToString("\n") { "$it: unported `y`" },
                LEVELS.joinToString("\n") { version ->
                    val level = ElixirLanguageLevel.of(version)
                    val ast = placeholding(lower(code, level), "y")

                    val env = Env.empty(level, NO_KERNEL)

                    "$version: " + render(code, Expander.expand(ast, ExState.empty(level), env, level, NO_EXPORTS))
                }
            )
        }
    }

    fun testAUnitThatIsNotAnInteger() =
        assertEvery("<<x::size(8)-unit(:a)>> = <<1>>", "error bad_unit_argument `x::size(8)-unit(:a)`")

    fun testASizedLiteralBitstring() =
        assertEvery("<<(<<1>>)::size(8)>>", "error bittype_literal_bitstring `(<<1>>)::size(8)`")

    fun testASizedLiteralString() = assertEvery("<<\"foo\"::size(3)>>", "error bittype_literal_string `\"foo\"::size(3)`")

    fun testASizedUtf() = assertEvery("<<x::utf8-size(8)>> = \"a\"", "error bittype_utf `x::utf8-size(8)`")

    fun testASignedUtf() = assertEvery("<<x::utf8-signed>> = \"a\"", "error bittype_signed `x::utf8-signed`")

    fun testAFloatOfAnInvalidSize() =
        assertEvery("<<x::float-size(10)>> = <<1>>", "error bittype_float_size `x::float-size(10)`")

    fun testAHalfFloatFrom1_11_4() =
        assertLevels("<<x::float-size(16)>> = <<0, 0>>", listOf("1.11.3") + LEVELS) {
            if (isBefore(it, "1.11.4")) "error bittype_float_size `x::float-size(16)`" else "expanded {x:0} next 1"
        }

    fun testAHalfFloatNeedsOtp24() {
        val code = "<<x::float-size(16)>> = <<0, 0>>"

        assertEquals(
            "23.3.4.20: error bittype_float_size `x::float-size(16)`\n24.0-rc1: expanded {x:0} next 1",
            listOf("23.3.4.20", "24.0-rc1").joinToString("\n") { otp ->
                val level = ElixirLanguageLevel.of("1.14.5", otp)

                val env = Env.empty(level, NO_KERNEL)

                "$otp: " + render(code, Expander.expand(lower(code, level), ExState.empty(level), env, level, NO_EXPORTS))
            }
        )
    }

    fun testAUnitWithoutASize() = assertEvery("<<x::integer-unit(8)>> = <<1>>", "error bittype_unit `x::integer-unit(8)`")

    fun testABitstringSizeVariableInAMapKeyPatternFrom1_15() =
        assertSplit(
            "n = 8; %{<<1::size(n)>> => v} = %{<<1>> => 2}",
            "1.15.0-rc.1",
            "error invalid_variable_in_map_key_match `%{<<1::size(n)>> => v}`",
            "expanded {n:0 v:1} next 2"
        )

    fun testABitstringSegmentVariableInAMapKeyPattern() =
        assertEvery("%{<<x::8>> => 1} = %{<<1>> => 1}", "error invalid_variable_in_map_key_match `%{<<x::8>> => 1}`")
}
