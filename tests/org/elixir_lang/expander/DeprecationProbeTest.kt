package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.APPLICATION_ENV_IN_BODY
import org.elixir_lang.language_level.ElixirLanguageFeature.LOCAL_MACRO_CHECKED_FOR_DEPRECATION
import org.elixir_lang.language_level.ElixirLanguageFeature.UNREQUIRED_MACRO_CALLED_AS_FUNCTION
import org.elixir_lang.language_level.ElixirLanguageFeature.UNREQUIRED_MACRO_SEEN_ONLY_WHEN_LOADED

/**
 * The deprecation warnings Elixir gives while it expands a file, on the leg's Elixir, equal the expander's. Elixir
 * compiles the file with the dispatch-time checks only, so a deprecated function called in a function body, which the
 * module checker reports after the module has compiled, is silent on both sides.
 */
class DeprecationProbeTest : ExpanderTestCase() {
    override val kernel = legKernel
    override val exports = legExports
    override val structs = legStructs

    /** A function is checked where the runtime pass can't see it: in a module body, not in a function. */
    fun testAFunctionIsCheckedOnlyInAModuleBody() = assertWarnsAsTheCompilerDoes(
        listOf(
            "defmodule User do\n  Dep.old()\nend",
            "defmodule User do\n  def f, do: Dep.old()\nend",
            "defmodule User do\n  _ = fn -> Dep.old() end\nend",
            "defmodule User do\n  @a Dep.old()\nend",
            "defmodule User do\n  Dep.old(1)\n  Dep.old(1, 2)\nend",
            "defmodule User do\n  Dep.old(\n    Dep.old(1, 2)\n  )\nend",
            "defmodule User do\n  Dep.soft()\nend",
        ),
    )

    /** A macro is checked wherever it is called from 1.12.2, and only in a module body before. */
    fun testAMacroIsCheckedInAFunctionFrom1_12_2() = assertWarnsAsTheCompilerDoes(
        listOf(
            "defmodule User do\n  require Dep\n  Dep.mac()\nend",
            "defmodule User do\n  require Dep\n  def f, do: Dep.mac()\nend",
            "defmodule User do\n  import Dep\n  mac()\nend",
            "defmodule User do\n  import Dep\n  def f, do: mac()\nend",
            "defmodule User do\n  require Dep\n  _ = &Dep.mac/0\nend",
        ),
    )

    /** An imported function and a capture are checked on every release, though by different routes. */
    fun testAnImportedFunctionAndACaptureAreChecked() = assertWarnsAsTheCompilerDoes(
        listOf(
            "defmodule User do\n  import Dep\n  old()\nend",
            "defmodule User do\n  import Dep\n  def f, do: old()\nend",
            "defmodule User do\n  _ = &Dep.old/0\nend",
            "defmodule User do\n  import Dep\n  _ = &old/0\nend",
            "defmodule User do\n  def f, do: &Dep.old/0\nend",
        ),
    )

    fun testUseOfADeprecatedUsingMacroIsChecked() =
        assertWarnsAsTheCompilerDoes(listOf("defmodule User do\n  use DepU\nend"))

    /** `Application`'s environment functions are discouraged in a module body from 1.14, and before it warn of nothing. */
    fun testApplicationEnvIsDiscouragedInAModuleBody() = assertWarnsAsTheCompilerDoes(
        warns = APPLICATION_ENV_IN_BODY.isSufficient(legLevel()),
        bodies = listOf(
            "defmodule User do\n  Application.get_env(:a, :b)\nend",
            "defmodule User do\n  Application.get_env(:a, :b, 1)\nend",
            "defmodule User do\n  Application.fetch_env(:a, :b)\nend",
            "defmodule User do\n  Application.fetch_env!(:a, :b)\nend",
            "defmodule User do\n  Application.get_all_env(:a)\nend",
            "defmodule User do\n  def f, do: Application.get_env(:a, :b)\nend",
            "defmodule User do\n  defmodule Inner do\n    Application.get_env(:a, :b)\n  end\nend",
            "Application.get_env(:a, :b)",
        ),
    )

    /** A module of the leg's Elixir is read from its `.beam`. */
    fun testDeprecatedCompiled() = assertWarnsAsTheCompilerDoes(
        listOf(
            "defmodule User do\n  Enum.chunk([1, 2], 1)\nend",
            "defmodule User do\n  def f, do: Enum.chunk([1, 2], 1)\nend",
            "defmodule User do\n  use GenEvent\nend",
        ),
    )

    /** Elixir reads a module's deprecations once it has compiled, so a module's own body and its enclosing body don't. */
    fun testAModuleIsReadOnceItHasCompiled() = assertWarnsAsTheCompilerDoes(
        listOf(
            "defmodule Own do\n  @deprecated \"x\"\n  def g, do: 1\n  Own.g()\nend",
            """
            defmodule Outer do
              defmodule DepF do
                @deprecated "use new"
                def old, do: 1
              end
              DepF.old()
              defmodule Sibling do
                Outer.DepF.old()
              end
            end
            """.trimIndent(),
        ),
    )

    /** `Macro.expand/2` checks a remote function it expands once, before `Macro.Env` takes the checks over in 1.17. */
    fun testMacroExpandChecksARemoteFunctionBefore1_17() =
        assertWarnsAsTheCompilerDoes(listOf("defmodule User do\n  _ = 1 in Dep.list()\nend"))

    /**
     * `Macro.expand/2` checks a macro it expands wherever it is called from 1.12.2, and only in a module body before. In
     * a guard, `in/2` writes only the expansion, so the macro is checked once, not again as its own call.
     */
    fun testMacroExpandChecksAMacro() = assertWarnsAsTheCompilerDoes(
        listOf(
            "defmodule User do\n  require Dep\n  case 1 do\n    x when x in Dep.macl() -> x\n  end\nend",
            "defmodule User do\n  require Dep\n  def f(x) when x in Dep.macl(), do: x\nend",
        ),
    )

    /**
     * A macro the module has not required is checked from 1.13 as the function it is called as, and before 1.12.2
     * first. In between, a macro that is seen raises `unrequired_module` before it is checked.
     */
    fun testAnUnrequiredMacroIsChecked() = assertWarnsAsTheCompilerDoes(
        warns = !UNREQUIRED_MACRO_SEEN_ONLY_WHEN_LOADED.isSufficient(legLevel()) ||
            UNREQUIRED_MACRO_CALLED_AS_FUNCTION.isSufficient(legLevel()),
        bodies = listOf("defmodule User do\n  Dep.mac()\nend", "defmodule User do\n  def f, do: Dep.mac()\nend"),
    )

    /** A local macro is checked from 1.17, when a module of its name compiled earlier in the file has deprecated it. */
    fun testALocalMacroIsCheckedFrom1_17() = assertWarnsAsTheCompilerDoes(
        warns = LOCAL_MACRO_CHECKED_FOR_DEPRECATION.isSufficient(legLevel()),
        bodies = listOf(
            """
            defmodule Local do
              @deprecated "x"
              defmacro m, do: 1
            end
            defmodule Local do
              defmacro m, do: 1
              def f, do: m()
            end
            """.trimIndent(),
            """
            defmodule Local do
              @deprecated "x"
              defmacro m, do: [1]
            end
            defmodule Local do
              defmacro m, do: [1]
              def f(x) when x in m(), do: x
            end
            """.trimIndent(),
        ),
    )

    /** `__info__(:deprecated)` keeps one reason for each clause, and the lowest sorting is the one a call reports. */
    fun testTheLowestSortingClausesReasonIsReported() = assertWarnsAsTheCompilerDoes(
        listOf("a" to "b", "b" to "a", "é" to "z", "z" to "é").map { (first, second) ->
            """
            defmodule Clauses do
              @deprecated "$first"
              def old(1), do: 1
              @deprecated "$second"
              def old(2), do: 2
            end
            defmodule User do
              Clauses.old(1)
            end
            """.trimIndent()
        },
    )

    /** Each of [bodies], after the deprecated definitions, warns as it does in Elixir; with [warns], at least one does. */
    private fun assertWarnsAsTheCompilerDoes(bodies: List<String>, warns: Boolean = true) {
        val sources = bodies.map { DEP + it }
        val expected = sources.map(::compilerWarnings)
        val actual = sources.map(::expanderWarnings)

        if (warns) assertTrue("no case warns", expected.any { it.isNotEmpty() })
        assertEquals(show(bodies, expected), show(bodies, actual))
    }

    private fun show(bodies: List<String>, warnings: List<List<String>>) =
        bodies.indices.joinToString("\n\n") { "${bodies[it]}\n  => ${warnings[it].ifEmpty { listOf("none") }}" }

    private fun expanderWarnings(source: String): List<String> {
        val level = legLevel()

        return DeprecationWarnings.of(Expander.expandFile(lower(source, level), Env.empty(level, kernel), level, exports, structs, NO_APP_ENV))
    }

    /** The deprecation warnings [source] gives when Elixir compiles it, with the line each is for. */
    private fun compilerWarnings(source: String): List<String> = DeprecationWarnings.compiled(listOf("probe.ex" to source))

    private companion object {
        /** The definitions the cases call. */
        const val DEP =
            """
            defmodule Dep do
              @deprecated "use new"
              def old, do: 1
              @deprecated "defaults"
              def old(a, b \\ 1), do: a
              @deprecated "use new_m"
              defmacro mac, do: 1
              @deprecated "use new_l"
              def list, do: [1]
              @deprecated "use new_ml"
              defmacro macl, do: [1]
              @doc deprecated: "soft"
              def soft, do: 1
            end
            defmodule DepU do
              @deprecated "gone"
              defmacro __using__(_opts), do: 1
            end

            """
    }
}
