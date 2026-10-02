package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * Snippets expanded as the body of `M.f/1`, where Elixir carries on after an error from 1.15, then each error it
 * reported and how its own code raised after one. The expected errors were read from plain-Elixir compiles of the same
 * bodies on 1.14.5, 1.15.8, 1.18.4, 1.19.5 and 1.20.4.
 */
class ContinuingErrorExpanderTest : ExpanderTestCase() {
    fun testStacktraceOutsideRescue() =
        assertContinues(
            "__STACKTRACE__",
            "error stacktrace_not_allowed `__STACKTRACE__`",
            "expanded {} next 0; reported stacktrace_not_allowed `__STACKTRACE__`",
        )

    fun testPinOutsideAPattern() =
        assertContinues(
            "x = 1; ^x",
            "error pin_outside_of_match `^x`",
            "expanded {x:0} next 1; reported pin_outside_of_match `^x`",
        )

    fun testAPinOfALiteral() =
        assertContinues(
            "y = 1; ^1 = y",
            "error invalid_arg_for_pin `^1`",
            "expanded {y:0} next 1; reported invalid_arg_for_pin `^1`",
        )

    fun testUnderscoreOutsideAPattern() =
        assertLevels("_", LEVELS + "1.20.0-rc.4") { version ->
            when {
                isBefore(version, "1.15.0-rc.0") -> "error unbound_underscore `_`"
                isBefore(version, "1.20.0-rc.5") -> "expanded {} next 0; reported unbound_underscore `_`"
                else -> "expanded {} next 1; reported unbound_underscore `_`"
            }
        }

    fun testAnUndefinedVariable() =
        assertContinues("x", "unported `x`", "expanded {} next 0; reported undefined_var `x`")

    fun testAnUndefinedPinnedVariable() =
        assertContinues(
            "y = 1; ^x = y",
            "error undefined_var_pin `x`",
            "expanded {y:0} next 1; reported undefined_var_pin `x`",
        )

    fun testAnUndefinedSize() =
        assertContinues(
            "y = 1; <<x::size(n)>> = y",
            "error undefined_var `n`",
            "expanded {x:1 y:0} next 2; reported undefined_var `n`",
        )

    fun testErrorsAreReportedInOrder() =
        assertLevels("y = 1; _; x; ^z = y; __STACKTRACE__", LEVELS) { version ->
            when {
                isBefore(version, "1.15.0-rc.0") -> "error unbound_underscore `_`"
                else ->
                    "expanded {y:0} next ${if (isBefore(version, "1.20.0-rc.5")) 1 else 2}; " +
                        "reported unbound_underscore `_`; reported undefined_var `x`; reported undefined_var_pin `z`; " +
                        "reported stacktrace_not_allowed `__STACKTRACE__`"
            }
        }

    fun testErrorsInsideNestedConstructsAreReportedInOrder() =
        assertLevels("y = 1; case y do a -> {_, fn -> b end, a} end; c", LEVELS) { version ->
            when {
                isBefore(version, "1.15.0-rc.0") -> "error unbound_underscore `_`"
                else ->
                    "expanded {y:0} next ${if (isBefore(version, "1.20.0-rc.5")) 2 else 5}; " +
                        "reported unbound_underscore `_`; reported undefined_var `b`; reported undefined_var `c`"
            }
        }

    fun testASpecTypeGivenTwice() =
        assertContinues(
            "y = 1; <<x::integer-float>> = y",
            "error bittype_mismatch `x::integer-float`",
            "expanded {x:1 y:0} next 2; reported bittype_mismatch `x::integer-float`",
        )

    fun testALiteralOfTheWrongType() =
        assertContinues(
            "<<1::binary>>",
            "error bittype_mismatch `1::binary`",
            "expanded {} next 0; reported bittype_mismatch `1::binary`",
        )

    fun testASizedLiteralStringOfAUtfType() =
        assertContinues(
            "<<\"a\"::utf8-size(8)>>",
            "error bittype_literal_string `\"a\"::utf8-size(8)`",
            "expanded {} next 0; reported bittype_literal_string `\"a\"::utf8-size(8)`; " +
                "reported bittype_utf `\"a\"::utf8-size(8)`",
        )

    fun testASizedLiteralBitstring() =
        assertContinues(
            "<<(<<1>>)::size(8)>>",
            "error bittype_literal_bitstring `(<<1>>)::size(8)`",
            "expanded {} next 0; reported bittype_literal_bitstring `(<<1>>)::size(8)`",
        )

    fun testAnUndefinedSpecIsDropped() =
        assertContinues(
            "y = 1; <<x::\"a\", z::float>> = y",
            "error undefined_bittype `x::\"a\"`",
            "expanded {x:1 y:0 z:2} next 3; reported undefined_bittype `x::\"a\"`",
        )

    fun testASignedBitstringWithAUnitReportsOnlyTheUnit() =
        assertContinues(
            "y = 1; <<x::bits-unit(8)-signed>> = y",
            "error bittype_mismatch `x::bits-unit(8)-signed`",
            "expanded {x:1 y:0} next 2; reported bittype_mismatch `x::bits-unit(8)-signed`",
        )

    fun testASignedUtfWithASizeReportsOnlyTheSize() =
        assertContinues(
            "y = 1; <<x::utf8-size(8)-signed>> = y",
            "error bittype_utf `x::utf8-size(8)-signed`",
            "expanded {x:1 y:0} next 2; reported bittype_utf `x::utf8-size(8)-signed`",
        )

    fun testASignedBinary() =
        assertContinues(
            "y = 1; <<x::binary-signed>> = y",
            "error bittype_signed `x::binary-signed`",
            "expanded {x:1 y:0} next 2; reported bittype_signed `x::binary-signed`",
        )

    fun testAnUnsizedBinaryBeforeTheLastSegment() =
        assertContinues(
            "y = 1; <<x::binary, z>> = y",
            "error unsized_binary `x::binary`",
            "expanded {x:1 y:0 z:2} next 3; reported unsized_binary `x::binary`",
        )

    fun testAnUnsizedBinaryAtTheEndOfANestedBitstring() =
        assertContinues(
            "y = 1; <<(<<x::binary>>), z>> = y",
            "error unsized_binary `x::binary`",
            "expanded {x:1 y:0 z:2} next 3; reported unsized_binary `x::binary`",
        )

    fun testTwoUnalignedBinaries() =
        assertContinues(
            "<<(<<1::1>>)::binary, (<<1::1>>)::binary>>",
            "error unaligned_binary `(<<1::1>>)::binary`",
            "expanded {} next 0; reported unaligned_binary `(<<1::1>>)::binary`; " +
                "reported unaligned_binary `(<<1::1>>)::binary`",
        )

    fun testAFloatOfAnInvalidSizeCrashes() =
        assertContinues(
            "y = 1; <<x::float-size(10)>> = y",
            "error bittype_float_size `x::float-size(10)`",
            "error bittype_float_size `x::float-size(10)`; reported bittype_float_size `x::float-size(10)`; " +
                "crashed Elixir.MatchError",
        )

    fun testAUnitWithoutASizeCrashes() =
        assertContinues(
            "y = 1; <<x::integer-unit(8)>> = y",
            "error bittype_unit `x::integer-unit(8)`",
            "error bittype_unit `x::integer-unit(8)`; reported bittype_unit `x::integer-unit(8)`; " +
                "crashed Elixir.MatchError",
        )

    fun testABadUnitOnASizedInteger() =
        assertContinues(
            "x = 1; <<x::integer-size(8)-unit(:a)>>",
            "error bad_unit_argument `x::integer-size(8)-unit(:a)`",
            "error bad_unit_argument `x::integer-size(8)-unit(:a)`; " +
                "reported bad_unit_argument `x::integer-size(8)-unit(:a)`; crashed Elixir.ArithmeticError",
        )

    fun testABadUnitOnAnUnsizedInteger() =
        assertContinues(
            "x = 1; <<x::integer-unit(:a)>>",
            "error bad_unit_argument `x::integer-unit(:a)`",
            "error bittype_unit `x::integer-unit(:a)`; reported bad_unit_argument `x::integer-unit(:a)`; " +
                "reported bittype_unit `x::integer-unit(:a)`; crashed Elixir.MatchError",
        )

    fun testABadUnitOnAnUnsizedFloat() =
        assertContinues(
            "x = 1; <<x::float-unit(:a)>>",
            "error bad_unit_argument `x::float-unit(:a)`",
            "error bittype_unit `x::float-unit(:a)`; reported bad_unit_argument `x::float-unit(:a)`; " +
                "reported bittype_unit `x::float-unit(:a)`; crashed Elixir.MatchError",
        )

    fun testABadUnitOnABinary() =
        assertContinues(
            "x = 1; <<x::binary-unit(:a)>>",
            "error bad_unit_argument `x::binary-unit(:a)`",
            "expanded {x:0} next 1; reported bad_unit_argument `x::binary-unit(:a)`",
        )

    fun testABadUnitOnABitstring() =
        assertContinues(
            "x = 1; <<x::bitstring-unit(:a)>>",
            "error bad_unit_argument `x::bitstring-unit(:a)`",
            "expanded {x:0} next 1; reported bad_unit_argument `x::bitstring-unit(:a)`; " +
                "reported bittype_mismatch `x::bitstring-unit(:a)`",
        )

    fun testABadUnitOnAUtf() =
        assertContinues(
            "x = 1; <<x::utf8-unit(:a)>>",
            "error bad_unit_argument `x::utf8-unit(:a)`",
            "expanded {x:0} next 1; reported bad_unit_argument `x::utf8-unit(:a)`; " +
                "reported bittype_utf `x::utf8-unit(:a)`",
        )

    fun testAFloatUnitOnASizedIntegerDoesNotCrash() =
        assertContinues(
            "x = 1; <<x::integer-size(8)-unit(1.5)>>",
            "error bad_unit_argument `x::integer-size(8)-unit(1.5)`",
            "expanded {x:0} next 1; reported bad_unit_argument `x::integer-size(8)-unit(1.5)`",
        )

    fun testAListSegmentInAPattern() =
        assertLevels("y = 1; <<[x]>> = y", LEVELS) { version ->
            when {
                isBefore(version, "1.18.0-rc.0") -> "error invalid_literal `<<[x]>>`"
                isBefore(version, "1.19.0-rc.0") -> "expanded {x:1 y:0} next 2"
                else -> "expanded {x:1 y:0} next 2; reported unknown_match `<<[x]>>`"
            }
        }

    fun testAnErrorBeforeABitstringError() =
        assertLevels("y = 1; _; <<x::integer-float>> = y", LEVELS) { version ->
            when {
                isBefore(version, "1.15.0-rc.0") -> "error unbound_underscore `_`"
                else ->
                    "expanded {x:${if (isBefore(version, "1.20.0-rc.5")) 1 else 2} y:0} " +
                        "next ${if (isBefore(version, "1.20.0-rc.5")) 2 else 3}; " +
                        "reported unbound_underscore `_`; reported bittype_mismatch `x::integer-float`"
            }
        }

    /** [code] gives [before] up to 1.14, and [from] from 1.15, where Elixir carries on. */
    private fun assertContinues(code: String, before: String, from: String) =
        assertSplit(code, "1.15.0-rc.0", before, from)

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val run = Run(level, ExpansionObserver.NONE, exports)
        val env = Env.empty(level, kernel).copy(module = "Elixir.M", function = NameArity("f", 1))
        val expansion = Expander.expand(lower(code, level), ExState.empty(level), env, run)

        return (
            listOf(render(code, expansion)) +
                run.errors.map { "reported ${it.kind} `${it.at.meta.origin.substring(code)}`" } +
                listOfNotNull(run.crash?.let { "crashed ${it.exception}" })
            ).joinToString("; ")
    }
}
