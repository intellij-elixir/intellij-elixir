package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * A macro whose body builds a segment with a unit outside `1..256`, which Erlang's compiler refuses, stops its caller's
 * module at the call, and nothing escapes the expansion.
 */
class UnitOutOfRangeTest : ExpanderTestCase() {
    override val exports: Exports = legExports
    override val kernel: KernelImports = legKernel
    override val structs: Structs = legStructs

    fun testAZeroUnitStopsAtTheCall() = assertStopsAtTheCall("0")

    fun testAUnitPastTheCompilersStopsAtTheCall() = assertStopsAtTheCall("257")

    /** A unit that is a multiple of 2^32, which an `Int` holds as `0`. */
    fun testAUnitPastAnIntStopsAtTheCall() = assertStopsAtTheCall("4294967296")

    private fun assertStopsAtTheCall(unit: String) {
        val level = ElixirLanguageLevel.of("1.20.4")
        val code = """
            defmodule A do
              defmacro m, do: (b = ""; <<b::binary-unit($unit)>>)
            end

            defmodule B do
              require A
              def g, do: A.m()
            end
        """.trimIndent() + "\n"
        val expansion = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs, NO_APP_ENV)
        val ended = expansion.modules.single { it.module == "Elixir.B" }.ended

        assertTrue("$ended", ended is ExpansionResult.Ended.Stopped)
        assertEquals("A.m()", (ended as ExpansionResult.Ended.Stopped).at.meta.origin.substring(code))
    }
}
