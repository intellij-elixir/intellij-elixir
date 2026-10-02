package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/** `elixir_clauses:guard`, from the guard context its caller sets, with `x` and `y` bound. */
class GuardTest : ExpanderTestCase() {
    fun testAGuardOfBoundVariables() = assertGuard("x when y") { "expanded {x:0 y:1} next 2" }

    fun testANestedWhenSplitsIntoEachGuard() =
        assertGuard("x when y when z") { version ->
            if (isBefore(version, "1.15.0-rc.0")) "error undefined_function `z`" else "error undefined_var `z`"
        }

    fun testAGuardIsExpandedInGuardContext() =
        assertGuard("x when (y = 1)") { "error invalid_expr_in_guard `y = 1`" }

    private fun assertGuard(code: String, expected: (String) -> String) =
        assertEquals(
            LEVELS.joinToString("\n") { "$it: ${expected(it)}" },
            LEVELS.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val state = ExState.empty(level).copy(read = mapOf(X to 0, Y to 1), version = 2)
                val env = Env.empty(level, NO_KERNEL).copy(context = Env.Context.GUARD)

                "$version: " + render(code, guard(lower(code, level), state, env, level, NO_EXPORTS))
            }
        )

    private companion object {
        val X = Variable("x", Variable.NIL)
        val Y = Variable("y", Variable.NIL)
    }
}
