package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * A macro's result can give a variable any term as its `counter`, and Elixir makes that term the variable's context. A
 * counter that is neither an integer nor `{module, n}` stops its caller's module at the call, and nothing escapes the
 * expansion.
 */
class ResultCounterTest : ExpanderTestCase() {
    override val exports: Exports = legExports
    override val kernel: KernelImports = legKernel
    override val structs: Structs = legStructs

    fun testAnEmptyTupleCounterStopsAtTheCall() = assertStopsAtTheCall("{}")

    fun testAPairWithNoModuleCounterStopsAtTheCall() = assertStopsAtTheCall("{1, 2}")

    fun testAnAtomCounterStopsAtTheCall() = assertStopsAtTheCall(":c")

    fun testAListCounterStopsAtTheCall() = assertStopsAtTheCall("[1]")

    private fun assertStopsAtTheCall(counter: String) {
        val level = ElixirLanguageLevel.of("1.20.4")
        val code = """
            defmodule A do
              defmacro m, do: {:=, [], [{:x, [counter: $counter], nil}, 1]}
            end

            defmodule B do
              require A
              def g, do: (A.m(); x)
            end
        """.trimIndent() + "\n"
        val expansion = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs, NO_APP_ENV)
        val ended = expansion.modules.single { it.module == "Elixir.B" }.ended

        assertTrue("$ended", ended is ExpansionResult.Ended.Stopped)
        assertEquals("A.m()", (ended as ExpansionResult.Ended.Stopped).at.meta.origin.substring(code))
    }
}
