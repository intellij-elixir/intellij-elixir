package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * Calls in a guard, from the guard context `elixir_clauses:guard` sets, with `x` and `y` bound: which of them
 * `elixir_rewrite` lets through on each Elixir release, and on each Erlang/OTP release for the guard functions OTP adds.
 */
class GuardCallTest : ExpanderTestCase() {
    override val exports: Exports = CallFixtures.EXPORTS
    override val kernel: KernelImports = CallFixtures.KERNEL

    fun testAnInlinedOperator() = assertGuard("x > 0") { BOUND }

    fun testAKernelFunctionRewrittenToAGuardFunction() {
        assertGuard("elem(x, 0)") { BOUND }
        assertGuard("is_map_key(x, :a)") { BOUND }
    }

    fun testPutElemIsAGuardFrom1_20() =
        assertGuard("put_elem(x, 0, 1)", LEVELS + "1.20.0-rc.5" + "1.20.0-rc.6") { version ->
            if (isBefore(version, "1.20.0-rc.6")) "error invalid_guard `put_elem(x, 0, 1)`" else BOUND
        }

    fun testAFunctionInlinedToAnErlangFunctionThatIsNotAGuardIsInvalid() =
        assertGuard("Integer.to_string(x)") { "error invalid_guard `Integer.to_string(x)`" }

    fun testARemoteCallOfAnotherModuleIsInvalid() = assertGuard("Map.get(x, :a)") { "error invalid_guard `Map.get(x, :a)`" }

    fun testIsRecordOfTwoOrThreeIsRefused() {
        assertGuard(":erlang.is_record(x, :a)") { "error invalid_guard `:erlang.is_record(x, :a)`" }
        assertGuard(":erlang.is_record(x, :a, 2)") { "error invalid_guard `:erlang.is_record(x, :a, 2)`" }
    }

    fun testAndalsoIsAGuardOperator() = assertGuard(":erlang.andalso(x, y)") { BOUND }

    fun testAKernelMacroIsOpaque() = assertGuard("x and y") { "opaque imported_macro Elixir.Kernel.and/2 `x and y`" }

    fun testALocalCallIsUndefined() = assertGuard("foo(x)") { "error undefined_function `foo(x)`" }

    fun testAMapLookupWithParentheses() =
        assertGuard("x.a()") { version ->
            if (isBefore(version, "1.14.0-rc.0")) {
                "error parens_map_lookup_guard `x.a()`"
            } else {
                "error parens_map_lookup `x.a()`"
            }
        }

    fun testAMapLookup() = assertGuard("x.a == 1") { BOUND }

    fun testMaxIsAGuardFromOtp26() =
        assertOtp(
            "max(x, y)",
            "1.13.4" to "25.3.2.21" to "error invalid_guard `max(x, y)`",
            "1.14.5" to "26.2.5.21" to BOUND,
            "1.13.4" to null to BOUND,
        )

    fun testIsIntegerOfThreeAndIsRecordOfOneAreGuardsFromOtp29() {
        assertOtp(
            ":erlang.is_integer(x, 0, 10)",
            "1.19.5" to "28.4" to "error invalid_guard `:erlang.is_integer(x, 0, 10)`",
            "1.20.4" to "29.0.6" to BOUND,
            "1.20.4" to null to BOUND,
        )
        assertOtp(
            ":erlang.is_record(x)",
            "1.19.5" to "28.4" to "error invalid_guard `:erlang.is_record(x)`",
            "1.20.4" to "29.0.6" to BOUND,
            "1.20.4" to null to BOUND,
        )
    }

    private fun assertGuard(code: String, versions: List<String> = LEVELS, expected: (String) -> String) =
        assertEquals(
            versions.joinToString("\n") { "$it: ${expected(it)}" },
            versions.joinToString("\n") { "$it: " + guard(code, ElixirLanguageLevel.of(it)) },
        )

    /** [code] at each Elixir and OTP pair, an OTP of `null` being unknown. */
    private fun assertOtp(code: String, vararg expected: Pair<Pair<String, String?>, String>) =
        assertEquals(
            expected.joinToString("\n") { (level, text) -> "$level: $text" },
            expected.joinToString("\n") { (level, _) ->
                "$level: " + guard(code, ElixirLanguageLevel.of(level.first, level.second))
            },
        )

    private fun guard(code: String, level: ElixirLanguageLevel): String {
        val state = ExState.empty(level).copy(read = mapOf(X to 0, Y to 1), version = 2)
        val env = Env.empty(level, kernel).copy(context = Env.Context.GUARD)

        return render(code, guard(lower(code, level), state, env, level, exports, structs))
    }

    private companion object {
        const val BOUND = "expanded {x:0 y:1} next 2"
        val X = Variable("x", Variable.NIL)
        val Y = Variable("y", Variable.NIL)
    }
}
