package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * A macro whose body reaches a value the evaluator can't hold as the VM does stops its caller's module at the call, and
 * nothing escapes the expansion.
 */
class UnheldValueTest : ExpanderTestCase() {
    override val exports: Exports = legExports
    override val kernel: KernelImports = legKernel
    override val structs: Structs = legStructs

    /** A float literal past a double's range, which Elixir's tokenizer refuses, ordered as a map key. */
    fun testAFloatPastADoublesRangeStopsAtTheCall() =
        assertStopsAtTheCall("defmacro m, do: Macro.escape(%{1.0e999 => 1, 2.0 => 2})")

    /** A binary of 250 MB, which the VM builds and the evaluator won't. */
    fun testABinaryPastTheBoundStopsAtTheCall() =
        assertStopsAtTheCall("defmacro m, do: <<0::size(2_000_000_000)>>")

    /**
     * An integer segment matched past the VM's largest integer, 65,535 words. From OTP 26 the VM aborts on it (seen on
     * 26.2.5.21, 27.3.4.18, 28.4.3 and 29.0.6), and before, its limit depends on the patch release.
     */
    fun testAnIntegerSegmentMatchedPastTheVmsLargestStopsAtTheCall() =
        assertStopsAtTheCall(
            "defmacro m, do: (n = 4_800_000; case <<-1::size(n)>> do <<x::size(n)>> -> rem(x, 2); _ -> :no end)",
        )

    /** A module name joined from 200 copies of a 12.5 MB binary, which the VM builds before it refuses the atom. */
    fun testAConcatPastTheBoundStopsAtTheCall() =
        assertStopsAtTheCall(
            "defmacro m, do: (b = <<0::size(100_000_000)>>; Module.concat([${List(200) { "b" }.joinToString()}]))",
        )

    /**
     * An alias of 250 characters, which with `Elixir.` is past an atom's 255, as a macro's body: `elixir_aliases:expand/4`
     * raises `system_limit` on it, so its module stops there.
     */
    fun testAnAliasPastAnAtomsLengthStopsAtTheAlias() {
        val alias = "A".repeat(250)
        val code = "defmodule A do\n  defmacro m, do: $alias\nend\n"
        val ended = expand(code).modules.single { it.module == "Elixir.A" }.ended

        assertTrue("$ended", ended is ExpansionResult.Ended.Stopped)
        assertEquals(alias, (ended as ExpansionResult.Ended.Stopped).at.meta.origin.substring(code))
    }

    /** A module named past an atom's length, which `elixir_aliases:expand/4` raises `system_limit` on, stops at its name. */
    fun testAModuleNamedPastAnAtomsLengthStopsAtItsName() {
        val module = "A".repeat(250)
        val code = "defmodule $module do\n  defmacro m, do: __MODULE__\nend\n"
        val top = expand(code).top

        assertTrue("$top", top is Expansion.Unported)
        assertEquals(module, (top as Expansion.Unported).at.meta.origin.substring(code))
    }

    /** A macro's output that is an alias past an atom's length, which raises `system_limit` where it expands. */
    fun testAnOutputAliasPastAnAtomsLengthStopsAtTheCall() =
        assertStopsAtTheCall("defmacro m, do: {:__aliases__, [], [:${"A".repeat(250)}]}")

    /** `B`'s `def g, do: A.m()`, with `A` defining [macro], stops at the call. */
    private fun assertStopsAtTheCall(macro: String) {
        val code = "defmodule A do\n  $macro\nend\n\ndefmodule B do\n  require A\n  def g, do: A.m()\nend\n"
        val ended = expand(code).modules.single { it.module == "Elixir.B" }.ended

        assertTrue("$ended", ended is ExpansionResult.Ended.Stopped)
        assertEquals("A.m()", (ended as ExpansionResult.Ended.Stopped).at.meta.origin.substring(code))
    }

    private fun expand(code: String): FileExpansion {
        val level = ElixirLanguageLevel.of("1.20.4")

        return Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)
    }
}
