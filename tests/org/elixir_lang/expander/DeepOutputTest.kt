package org.elixir_lang.expander

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
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
            val ended = expandedOnAFixedStack(code, level, depth).modules.single { it.module == "Elixir.B" }.ended

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
            val top = expandedOnAFixedStack(code, level, depth).top

            if (top is Expansion.Unported) return

            assertTrue("no stop by depth $depth: $top", depth < LAST_DEPTH)

            depth += depth / 4
        }
    }

    /**
     * `x = f.(f, 100_000)`, a recursion the evaluator runs deeper than the thread's stack: its module stops at the match,
     * and what the match binds is unknown there, as where its right side declines, so a module nested after it defines
     * nothing from the value `x` held before.
     */
    fun testAMatchTooDeepToRunLeavesWhatItBindsUnknown() {
        val match = "x = f.(f, $RECURSION)"
        val code = "defmodule O do\n  x = :a\n  ${recursiveFn("  ")}\n  $match\n\n  defmodule N do\n    def unquote(x)(), do: 1\n  end\nend\n"
        val outer = expandedOnAFixedStack(code, ElixirLanguageLevel.of("1.20.4"), RECURSION).modules.single { it.module == "Elixir.O" }

        assertStoppedAt(code, match, outer.ended)
        assertDefinesNoA(outer.nested.single { it.module == "Elixir.O.N" })
    }

    /** `x = f.(f, 100_000)` outside any module: the file stops at the match, and a module after it reads `x` as unknown. */
    fun testAMatchTooDeepToRunOutsideAModuleLeavesWhatItBindsUnknown() {
        val match = "x = f.(f, $RECURSION)"
        val code = "x = :a\n${recursiveFn("")}\n$match\n\ndefmodule T do\n  def unquote(x)(), do: 1\nend\n"
        val expansion = expandedOnAFixedStack(code, ElixirLanguageLevel.of("1.20.4"), RECURSION)
        val top = expansion.top

        assertTrue("top: $top", top is Expansion.Unported && top.at.meta.origin.substring(code) == match)
        assertDefinesNoA(expansion.modules.single { it.module == "Elixir.T" })
    }

    /**
     * `def unquote(f.(f, 100_000))()`: a fragment the evaluator runs deeper than the thread's stack is unknown, as one
     * it declines is, so the definition is stored unnamed, its module stops at the fragment, and the definition after it
     * is stored.
     */
    fun testADefinitionFragmentTooDeepToRunStoresTheDefinitionUnnamed() {
        val fragment = "unquote(f.(f, $RECURSION))"
        val code = "defmodule O do\n  ${recursiveFn("  ")}\n  def $fragment(), do: 1\n  def g, do: 1\nend\n"
        val result = expandedOnAFixedStack(code, ElixirLanguageLevel.of("1.20.4"), RECURSION).modules.single { it.module == "Elixir.O" }

        assertStoppedAt(code, fragment, result.ended)
        assertEquals("${result.table.unnamed}", 1, result.table.unnamed.size)
        assertTrue("${result.table.entries.keys}", result.table.entries.keys.any { it.name == "g" && it.arity == 0 })
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
        val ended = expandedOnAFixedStack(code, ElixirLanguageLevel.of("1.20.4"), LAST_DEPTH)
            .modules.single { it.module == "Elixir.B" }.ended

        assertTrue(
            "not compiled: ${(ended as? ExpansionResult.Ended.Stopped)?.at?.meta?.origin?.substring(code) ?: ended}",
            ended is ExpansionResult.Ended.Compiled,
        )
    }

    /** `loop(n), do: loop(n)`, a tail loop that never ends, stops as one too deep to run does ([assertLoopStops]). */
    fun testATailLoopThatNeverEndsStopsAsTooDeep() = assertLoopStops("def loop(n), do: loop(n)", "", "H.loop(0)")

    /** `loop(n), do: loop(n + 1)`, a tail loop that never ends and gives each call a new value, stops as one too deep to run does. */
    fun testAnAccumulatingTailLoopStopsAsTooDeep() = assertLoopStops("def loop(n), do: loop(n + 1)", "", "H.loop(0)")

    /** `f.(f)`, where `f = fn f -> f.(f) end`, a tail loop of an `fn` that never ends, stops as one too deep to run does. */
    fun testATailFnLoopThatNeverEndsStopsAsTooDeep() = assertLoopStops("", "f = fn f -> f.(f) end", "f.(f)")

    /** A tail loop that never ends, cancelled, ends with the cancel. */
    fun testATailLoopEndsWhenCancelled() {
        val escaped = endedOnAFixedStack(calledFromAMacro("def loop(n), do: loop(n)", "", "H.loop(0)"), ElixirLanguageLevel.of("1.20.4"), checks = 1_000).second

        assertTrue("escaped: $escaped", escaped is ProcessCanceledException)
    }

    /** A `@before_compile` callback that writes a module with the same callback nests modules without end: `B` stops. */
    fun testACallbackNestingModulesWithoutEndStopsItsModule() {
        val code = "defmodule CB do\n  defmacro __before_compile__(_) do\n" +
            "    quote(do: defmodule(Inner, do: @before_compile(CB)))\n  end\nend\n\n" +
            "defmodule B do\n  @before_compile CB\nend\n"
        val ended = expandedOnAFixedStack(code, ElixirLanguageLevel.of("1.20.4"), depth = 0)
            .modules.single { it.module == "Elixir.B" }.ended

        assertTrue("not stopped: $ended", ended is ExpansionResult.Ended.Stopped)
    }

    /**
     * The loop [call] makes, after [setup], of [definitions], `H`'s where there are any, run from a macro called in a module body and from a
     * module-body match, each under a cancel well past the checks it makes before it stops, ends as an overflow does at
     * that site: its module stops at the call, or at the match, where what the match binds is unknown.
     */
    private fun assertLoopStops(definitions: String, setup: String, call: String) {
        val level = ElixirLanguageLevel.of("1.20.4")
        val macroCode = calledFromAMacro(definitions, setup, call)
        val fromMacro = expandedOnAFixedStack(macroCode, level, depth = 0, checks = LOOP_CHECKS)

        assertStoppedAt(macroCode, "A.m()", fromMacro.modules.single { it.module == "Elixir.B" }.ended)

        val match = "x = $call"
        val code = helper(definitions) +
            "defmodule O do\n  x = :a\n  $setup\n  $match\n\n  defmodule N do\n    def unquote(x)(), do: 1\n  end\nend\n"
        val outer = expandedOnAFixedStack(code, level, depth = 0, checks = LOOP_CHECKS).modules.single { it.module == "Elixir.O" }

        assertStoppedAt(code, match, outer.ended)
        assertDefinesNoA(outer.nested.single { it.module == "Elixir.O.N" })
    }

    /** `B`, which calls `A.m()`, a macro whose body makes [call], after [setup], of [definitions], `H`'s. */
    private fun calledFromAMacro(definitions: String, setup: String, call: String): String =
        helper(definitions) +
            "defmodule A do\n  defmacro m do\n    $setup\n    $call\n  end\nend\n\n" +
            "defmodule B do\n  require A\n  A.m()\nend\n"

    /** [code], expanded on a thread of [FIXED_STACK] bytes, cancelled at its [checks]th check there, with nothing thrown. */
    private fun expandedOnAFixedStack(code: String, level: ElixirLanguageLevel, depth: Int, checks: Int? = null): FileExpansion {
        val (expansion, escaped) = endedOnAFixedStack(code, level, checks)

        assertNull("escaped at depth $depth: $escaped", escaped)

        return expansion!!
    }

    /**
     * [code], expanded on a thread of [FIXED_STACK] bytes, cancelled at its [checks]th check there where they are
     * given: its expansion, or what escaped it.
     */
    private fun endedOnAFixedStack(code: String, level: ElixirLanguageLevel, checks: Int?): Pair<FileExpansion?, Throwable?> {
        val forms = lower(code, level)
        var expansion: FileExpansion? = null
        var escaped: Throwable? = null
        val expand = { expansion = Expander.expandFile(forms, Env.empty(level, kernel), level, exports, structs, NO_APP_ENV) }
        val thread = Thread(
            null,
            {
                try {
                    if (checks == null) {
                        expand()
                    } else {
                        val indicator = CancelAt(checks)

                        whileCheckingIndicators { ProgressManager.getInstance().runProcess(expand, indicator) }
                    }
                } catch (e: Throwable) {
                    escaped = e
                }
            },
            "fixed stack",
            FIXED_STACK,
        )

        thread.start()
        thread.join()

        return expansion to escaped
    }

    /**
     * `B`, which calls `A`'s [macros] by [call], expanded on a thread of [FIXED_STACK] bytes, first stops at `A.m()` as
     * the output deepens, with nothing thrown at any depth. How deep a thread's stack reaches moves with the JIT, so the
     * depth grows by a quarter at a time: the output overflows at a depth some way short of the one where the body does.
     */
    private fun assertStopsAtTheCall(macros: (Int) -> String, call: String) {
        val level = ElixirLanguageLevel.of("1.20.4")
        var depth = FIRST_DEPTH

        while (true) {
            val code = "defmodule A do\n  ${macros(depth)}\nend\n\ndefmodule B do\n  require A\n  $call\nend\n"
            val ended = expandedOnAFixedStack(code, level, depth).modules.single { it.module == "Elixir.B" }.ended

            if (ended is ExpansionResult.Ended.Stopped) {
                assertEquals("at depth $depth", "A.m()", ended.at.meta.origin.substring(code))

                return
            }

            assertTrue("no stop by depth $depth: $ended", depth < LAST_DEPTH)

            depth += depth / 4
        }
    }

    /** `H`, which defines [definitions], where there are any. */
    private fun helper(definitions: String): String =
        if (definitions.isEmpty()) "" else "defmodule H do\n  $definitions\nend\n\n"

    private fun assertStoppedAt(code: String, source: String, ended: ExpansionResult.Ended) {
        assertTrue("not stopped: $ended", ended is ExpansionResult.Ended.Stopped)
        assertEquals(source, (ended as ExpansionResult.Ended.Stopped).at.meta.origin.substring(code))
    }

    /** [result]'s module defines no `a`, the value `x` held before the match that stopped. */
    private fun assertDefinesNoA(result: ExpansionResult) {
        assertFalse("${result.module} defines ${result.table.entries.keys}", result.table.entries.keys.any { it.name == "a" })
    }

    private companion object {
        /** Shallow enough to expand on any thread. */
        const val FIRST_DEPTH = 256

        /** Deeper than the evaluator runs a recursion on [FIXED_STACK]. */
        const val RECURSION = 100_000

        /** `f`, which calls itself `n` times, not in tail position, written at [indent]. */
        fun recursiveFn(indent: String): String = "f = fn\n$indent  _, 0 -> :b\n$indent  f, n -> hd([f.(f, n - 1)])\n${indent}end"

        /** Well past the checks these tail loops make before they stop as too deep to run, at most 16 a call. */
        const val LOOP_CHECKS = 50_000_000

        /** Deep enough to overflow [FIXED_STACK]. */
        const val LAST_DEPTH = 1 shl 14

        /** `c` nested in `f(...)` [depth] times. */
        fun nested(depth: Int): String = "c = {:f, [], [c]}; ".repeat(depth)
    }
}
