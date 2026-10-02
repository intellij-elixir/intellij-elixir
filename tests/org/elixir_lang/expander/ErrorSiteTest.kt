package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.ErrorSite.*
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Each [ErrorSite]'s outcome either side of 1.15, inside a function and in a module body. */
class ErrorSiteTest {
    private val inFunction =
        Env.empty(ElixirLanguageLevel.of("1.15.0-rc.0"), ExpanderTestCase.NO_KERNEL)
            .copy(module = "Elixir.M", function = NameArity("f", 0))

    private val outcomes = mapOf(
        CALLER_NOT_ALLOWED to Outcome.Continues,
        STACKTRACE_NOT_ALLOWED to Outcome.Continues,
        INVALID_ARG_FOR_PIN to Outcome.Continues,
        PIN_OUTSIDE_OF_MATCH to Outcome.Continues,
        INVALID_FUNCTION_CALL to Outcome.Continues,
        PARENS_MAP_LOOKUP to Outcome.Continues,
        UNBOUND_UNDERSCORE to Outcome.Continues,
        UNDEFINED_VAR to Outcome.Continues,
        UNDEFINED_VAR_PIN to Outcome.Continues,
        UNKNOWN_MATCH to Outcome.Continues,
        BAD_UNIT_ARGUMENT to Outcome.Continues,
        BITTYPE_MISMATCH_SPEC to Outcome.Continues,
        UNDEFINED_BITTYPE to Outcome.Continues,
        BITTYPE_MISMATCH_TYPE to Outcome.Continues,
        UNSIZED_BINARY_REQUIRED to Outcome.Continues,
        BITTYPE_LITERAL_BITSTRING to Outcome.Continues,
        BITTYPE_LITERAL_STRING to Outcome.Continues,
        BITTYPE_UTF to Outcome.Continues,
        BITTYPE_SIGNED to Outcome.Continues,
        BITTYPE_MISMATCH_UNIT to Outcome.Continues,
        BITTYPE_FLOAT_SIZE to Outcome.Crashes("Elixir.MatchError"),
        BITTYPE_UNIT to Outcome.Crashes("Elixir.MatchError"),
        UNSIZED_BINARY_NESTED to Outcome.Continues,
        UNALIGNED_BINARY to Outcome.Continues,
    )

    @Test
    fun `every site has an outcome here`() = assertEquals(entries.toSet(), outcomes.keys)

    @Test
    fun `inside a function from 1_15`() = assertEach("1.15.0-rc.0", inFunction) { outcomes.getValue(it) }

    @Test
    fun `every site raises inside a function before 1_15`() = assertEach("1.14.5", inFunction) { Outcome.Raises }

    @Test
    fun `every site raises in a module body`() =
        assertEach("1.15.0-rc.0", inFunction.copy(function = null)) { Outcome.Raises }

    private fun assertEach(version: String, env: Env, expected: (ErrorSite) -> Outcome) {
        val level = ElixirLanguageLevel.of(version)

        assertEquals(
            entries.joinToString("\n") { "$it: ${expected(it)}" },
            entries.joinToString("\n") { "$it: ${it.outcome(level, env)}" },
        )
    }
}
