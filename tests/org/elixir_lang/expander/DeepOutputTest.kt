package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * Code nested deeper than a thread's stack can expand, which Elixir compiles, declines: a macro's output stops its module
 * at the call, a source form at its module's body, and nothing escapes the expansion.
 */
class DeepOutputTest : ExpanderTestCase() {
    override val exports: Exports = legExports
    override val kernel: KernelImports = legKernel
    override val structs: Structs = legStructs

    /** `def g, do: f(f(...))`, written by a macro in the module body: its body expands with the module's. */
    fun testADefinitionTooDeepToExpandStopsItsModuleAtTheCall() =
        assertStopsAtTheCall(
            { depth -> "defmacro m, do: (c = 1; ${nested(depth)}{:def, [], [{:g, [], []}, [do: c]]})" },
            "A.m()\n  def f(x), do: x",
        )

    /** `f(f(...))`, the output of a macro called in a function body: it expands in place of the call. */
    fun testAnOutputTooDeepToExpandStopsItsModuleAtTheCall() =
        assertStopsAtTheCall({ depth -> "defmacro m, do: (c = 1; ${nested(depth)}c)" }, "def f(x), do: x\n  def g, do: A.m()")

    /**
     * `@x 1 + 1 + ...`, written in the source: its module stops at its body, as at a macro's call, or at its `defmodule`
     * where the walk's evaluation of the attribute is too deep before its expansion is.
     */
    fun testAnAttributeTooDeepToExpandStopsItsModuleAtItsBody() {
        val level = ElixirLanguageLevel.of("1.20.4")
        var depth = FIRST_DEPTH

        while (true) {
            val body = "def y, do: 1\n  @x ${"1 + ".repeat(depth)}1\n  def x, do: @x"
            val code = "defmodule B do\n  $body\nend\n"
            val ended = expandedOnADefaultStack(code, level, depth).modules.single { it.module == "Elixir.B" }.ended

            if (ended is ExpansionResult.Ended.Stopped) {
                val at = ended.at.meta.origin.substring(code)

                assertTrue("at depth $depth: $at", at == body || at == code.trimEnd())

                return
            }

            assertTrue("no stop by depth $depth: $ended", depth < LAST_DEPTH)

            depth += depth / 4
        }
    }

    /** `x = 1 + 1 + ...`, written in the source outside any module: the file's top level stops, as a module's body does. */
    fun testAFormTooDeepToExpandOutsideAModuleStopsTheFile() {
        val level = ElixirLanguageLevel.of("1.20.4")
        var depth = FIRST_DEPTH

        while (true) {
            val code = "x = ${"1 + ".repeat(depth)}1\n"
            val top = expandedOnADefaultStack(code, level, depth).top

            if (top is Expansion.Unported) return

            assertTrue("no stop by depth $depth: $top", depth < LAST_DEPTH)

            depth += depth / 4
        }
    }

    /**
     * A macro's output defining a chain of shallow functions longer than a thread's stack holds, each calling the next:
     * the checks once the body has run walk the chain, and `B` compiles, as in Elixir.
     */
    fun testAChainOfGeneratedFunctionsLongerThanTheStackCompiles() {
        val definitions = listOf("{:def, [], [{:f0, [], []}, [do: {:f1, [], []}]]}") +
            (1 until LAST_DEPTH).map { "{:defp, [], [{:f$it, [], []}, [do: {:f${it + 1}, [], []}]]}" } +
            "{:defp, [], [{:f$LAST_DEPTH, [], []}, [do: :ok]]}"
        val code = "defmodule A do\n  defmacro m, do: {:__block__, [], [${definitions.joinToString()}]}\nend\n\n" +
            "defmodule B do\n  require A\n  A.m()\nend\n"
        val ended = expandedOnADefaultStack(code, ElixirLanguageLevel.of("1.20.4"), LAST_DEPTH)
            .modules.single { it.module == "Elixir.B" }.ended

        assertTrue(
            "not compiled: ${(ended as? ExpansionResult.Ended.Stopped)?.at?.meta?.origin?.substring(code) ?: ended}",
            ended is ExpansionResult.Ended.Compiled,
        )
    }

    /** A `@before_compile` callback that writes a module with the same callback nests modules without end: `B` stops. */
    fun testACallbackNestingModulesWithoutEndStopsItsModule() {
        val code = "defmodule CB do\n  defmacro __before_compile__(_) do\n" +
            "    quote(do: defmodule(Inner, do: @before_compile(CB)))\n  end\nend\n\n" +
            "defmodule B do\n  @before_compile CB\nend\n"
        val ended = expandedOnADefaultStack(code, ElixirLanguageLevel.of("1.20.4"), depth = 0)
            .modules.single { it.module == "Elixir.B" }.ended

        assertTrue("not stopped: $ended", ended is ExpansionResult.Ended.Stopped)
    }

    /** [code], expanded on a thread with the default stack, with nothing thrown. */
    private fun expandedOnADefaultStack(code: String, level: ElixirLanguageLevel, depth: Int): FileExpansion {
        val forms = lower(code, level)
        var expansion: FileExpansion? = null
        var escaped: Throwable? = null
        val thread = Thread(
            null,
            {
                try {
                    expansion = Expander.expandFile(forms, Env.empty(level, kernel), level, exports, structs)
                } catch (e: Throwable) {
                    escaped = e
                }
            },
            "default stack",
            0,
        )

        thread.start()
        thread.join()

        assertNull("escaped at depth $depth: $escaped", escaped)

        return expansion!!
    }

    /**
     * `B`, which calls `A`'s [macros] by [call], expanded on a thread with the default stack, first stops at `A.m()` as
     * the output deepens, with nothing thrown at any depth. How deep a thread's stack reaches moves with the JIT, so the
     * depth grows by a quarter at a time: the output overflows at a depth some way short of the one where the body does.
     */
    private fun assertStopsAtTheCall(macros: (Int) -> String, call: String) {
        val level = ElixirLanguageLevel.of("1.20.4")
        var depth = FIRST_DEPTH

        while (true) {
            val code = "defmodule A do\n  ${macros(depth)}\nend\n\ndefmodule B do\n  require A\n  $call\nend\n"
            val ended = expandedOnADefaultStack(code, level, depth).modules.single { it.module == "Elixir.B" }.ended

            if (ended is ExpansionResult.Ended.Stopped) {
                assertEquals("at depth $depth", "A.m()", ended.at.meta.origin.substring(code))

                return
            }

            assertTrue("no stop by depth $depth: $ended", depth < LAST_DEPTH)

            depth += depth / 4
        }
    }

    private companion object {
        /** Shallow enough to expand on any thread. */
        const val FIRST_DEPTH = 256

        /** Deep enough to overflow any thread's default stack. */
        const val LAST_DEPTH = 1 shl 14

        /** `c` nested in `f(...)` [depth] times. */
        fun nested(depth: Int): String = "c = {:f, [], [c]}; ".repeat(depth)
    }
}
