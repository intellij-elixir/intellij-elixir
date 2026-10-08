package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * `defguard` and `defguardp`: the macro they define and the `@doc guard: true` before it, the guard expanded as a
 * guard, and each way they raise. The macro's body is `Kernel.Utils.defguard/2`'s, which isn't modelled past the
 * guard's own expansion.
 */
class DefguardMacroTest : ExpanderTestCase() {
    override val exports: Exports = AttributeFixtures.EXPORTS
    override val kernel: KernelImports = AttributeFixtures.KERNEL

    fun testADefguardDefinesAMacro() =
        assertEvery(
            "defmodule A do\n  defguard is_one(x) when x == 1\nend",
            "module Elixir.A stopped `defguard is_one(x) when x == 1` | defmacro is_one/1 line 2 clauses 1",
        )

    fun testADefguardpDefinesAPrivateMacro() =
        assertEvery(
            "defmodule A do\n  defguardp is_one(x) when x == 1\nend",
            "module Elixir.A stopped `defguardp is_one(x) when x == 1` | defmacrop is_one/1 line 2 clauses 1",
        )

    fun testTheDefinitionsAroundItAreStoredAroundIt() =
        assertEvery(
            "defmodule A do\n  def a, do: 1\n  defguard is_one(x) when x == 1\n  def b, do: 2\nend",
            "module Elixir.A stopped `defguard is_one(x) when x == 1` | def a/0 line 2 clauses 1 | " +
                "defmacro is_one/1 line 3 clauses 1 | def b/0 line 4 clauses 1",
        )

    fun testAGuardWithoutArgumentsIsAMacroOfNone() =
        assertEvery(
            "defmodule A do\n  defguard is_true when true\nend",
            "module Elixir.A stopped `defguard is_true when true` | defmacro is_true/0 line 2 clauses 1",
        )

    fun testTheNameMayBeAnUnquotedAtomFrom1_18() =
        assertSplit(
            "defmodule A do\n  defguard unquote(:is_one)(x) when x == 1\nend",
            "1.18.0-rc.0",
            "module Elixir.A raised invalid_defguard `defguard unquote(:is_one)(x) when x == 1`",
            "module Elixir.A stopped `defguard unquote(:is_one)(x) when x == 1` | defmacro is_one/1 line 2 clauses 1",
        )

    fun testAGuardWithoutABodyHasNoClauseAndFailsTheModule() =
        assertSplit(
            "defmodule A do\n  defguard is_three(x)\nend",
            "1.15.0-rc.0",
            "module Elixir.A raised function_head `defguard is_three(x)` | defmacro is_three/1 line 2 clauses 0",
            "module Elixir.A tainted | defmacro is_three/1 line 2 clauses 0",
        )

    fun testTwoWhensRaise() =
        assertEvery(
            "defmodule A do\n  defguard is_one(x) when x == 1 when x == 2\nend",
            "module Elixir.A raised defguard_two_whens `defguard is_one(x) when x == 1 when x == 2`",
        )

    fun testAHeadThatIsNotACallRaises() =
        assertEvery(
            "defmodule A do\n  defguard 1 when true\nend",
            "module Elixir.A raised invalid_defguard `defguard 1 when true`",
        )

    fun testAnArgumentThatIsNotAVariableRaises() =
        assertEvery(
            "defmodule A do\n  defguard is_one(1) when true\nend",
            "module Elixir.A raised invalid_defguard `defguard is_one(1) when true`",
        )

    fun testADefaultArgumentOfAVariableIsAllowed() =
        assertEvery(
            "defmodule A do\n  defguard is_one(x \\\\ 1) when x == 1\nend",
            "module Elixir.A stopped `defguard is_one(x \\\\ 1) when x == 1` | defmacro is_one/1 line 2 clauses 1 | " +
                "defmacro is_one/0 line 2 clauses 1",
        )

    fun testTheGuardIsExpandedAsAGuard() =
        assertEvery(
            "defmodule A do\n  defguard is_one(x) when System.halt() == x\nend",
            "module Elixir.A raised invalid_guard `System.halt()`",
        )

    fun testAGuardInsideAFunctionRaises() =
        assertEvery(
            "defmodule A do\n  def f, do: defguard(is_one(x) when x == 1)\nend",
            "module Elixir.A raised definer_inside_function `defguard(is_one(x) when x == 1)`",
        )

    fun testAGuardInTheGuardOfAFunctionRaises() =
        assertEvery(
            "defmodule A do\n  def f(x) when defguard(is_one(y) when y == 1), do: x\nend",
            "module Elixir.A raised definer_inside_function `defguard(is_one(y) when y == 1)`",
        )

    fun testAGuardInThePatternOfAFunctionRaises() =
        assertEvery(
            "defmodule A do\n  def f(defguard(is_one(y) when y == 1)), do: 1\nend",
            "module Elixir.A raised definer_inside_function `defguard(is_one(y) when y == 1)`",
        )

    fun testAGuardOutsideAModuleRaisesWhateverItsContext() {
        val level = ElixirLanguageLevel.of("1.20.4")

        for (code in listOf("defguard foo(x) when is_integer(x)", "case 1 do\n  _ when defguard(foo(x)) -> 1\nend")) {
            val top = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs).top

            assertEquals(code, "definer_outside_module", (top as Expansion.Error).kind)
        }
    }

    fun testTheDocIsDispatchedToTheModuleKernelNamesItIn() {
        val code = "defmodule A do\n  defguard is_one(x) when x == 1\nend"

        assertEquals(
            LEVELS.joinToString("\n") { "$it: " + if (isBefore(it, "1.14.0-rc.0")) BOOTSTRAP_AT else KERNEL_AT },
            LEVELS.joinToString("\n") { version -> "$version: " + events(code, version).single { it.endsWith("@/1") } },
        )
    }

    fun testTheGuardIsExpandedWithItsOwnEvents() {
        val code = "defmodule A do\n  defguard is_one(x) when x == 1\nend"
        val expected = listOf("remote_macro Elixir.Kernel.Utils.defguard/2", "imported_function erlang.==/2")

        assertEquals(
            LEVELS.joinToString("\n") { "$it: $expected" },
            LEVELS.joinToString("\n") { version ->
                "$version: " + events(code, version).dropWhile { !it.contains("Utils.defguard") }
            },
        )
    }

    private companion object {
        const val KERNEL_AT = "remote_macro Elixir.Kernel.@/1"
        const val BOOTSTRAP_AT = "remote_macro elixir_bootstrap.@/1"
    }

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

        return file.modules.joinToString("\n") { ModuleRendering.render(code, it) }
    }
}
