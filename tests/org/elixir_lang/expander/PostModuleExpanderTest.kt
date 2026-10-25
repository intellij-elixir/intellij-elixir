package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/**
 * Files expanded as Elixir compiles them, by the checks Elixir makes as each definition is stored and once the module
 * body has run: how each module ends, and the errors it reports.
 */
class PostModuleExpanderTest : ExpanderTestCase() {
    override val exports: Exports = AttributeFixtures.EXPORTS
    override val kernel: KernelImports = AttributeFixtures.KERNEL

    // The definition-time checks

    fun testAChangedKind() =
        assertEvery(
            """
            defmodule A do
              def f, do: 1
              defp f, do: 2
            end
            """.trimIndent(),
            "module Elixir.A raised changed_kind `defp f, do: 2`",
        )

    fun testAChangedKindAfterADefinitionThatMayNotRunStopsTheModule() =
        assertEvery(
            """
            defmodule A do
              case (receive do after 0 -> false end) do
                false -> def f(a), do: 1
                _ -> :ok
              end
              defmacro f(a), do: 1
            end
            """.trimIndent(),
            "module Elixir.A stopped `defmacro f(a), do: 1`",
        )

    /** A definition in a branch the `if` doesn't select never runs, so the kind after it is the first. */
    fun testAChangedKindAfterADefinitionInABranchNotSelectedCompiles() =
        assertEvery(
            "defmodule A do\n  if false do\n    def f(a), do: 1\n  end\n  defmacro f(a), do: 1\nend",
            "module Elixir.A compiled",
        )

    /** A definition in a branch the `if` doesn't select never runs, so it is no definition of the name. */
    fun testADefinitionInABranchNotSelectedCompiles() =
        assertEvery("defmodule A do\n  if false do\n    def __info__(a), do: a\n  end\nend", "module Elixir.A compiled")

    /** The module stops where the walk lost Elixir's path: at the `case` whose subject the evaluator can't produce. */
    fun testADefinitionThatMayNotRunStopsTheModuleAtItsBranch() =
        assertEvery(
            "defmodule A do\n  case (receive do after 0 -> false end) do\n    false -> def __info__(a), do: a\n    _ -> :ok\n  end\nend",
            "module Elixir.A stopped `case (receive do after 0 -> false end) do`",
        )

    /** A stop in a unit of a statement the walk didn't follow is that statement's, however deep the unit. */
    fun testADefinitionInANestedStatementOfOneTheWalkDidNotFollowStopsAtTheOuterStatement() =
        assertEvery(
            "defmodule A do\n  for n <- (receive do after 0 -> [:f] end) do\n    case n do\n      _ -> def unquote(n)(), do: 1\n    end\n  end\nend",
            "module Elixir.A stopped `for n <- (receive do after 0 -> [:f] end) do`",
        )

    fun testADefinitionTwoStatementsDownFromOneTheWalkDidNotFollowStopsAtTheOuterStatement() =
        assertEvery(
            "defmodule A do\n  case (receive do after 0 -> :a end) do\n    :a -> for n <- (receive do after 0 -> [:f] end), do: (case n do _ -> def unquote(n)(), do: 1 end)\n    _ -> :ok\n  end\nend",
            "module Elixir.A stopped `case (receive do after 0 -> :a end) do`",
        )

    /** Elixir expands the whole body before it stores a definition, so a body the expander can't finish may raise first. */
    fun testABodyThatIsNotExpandedHidesTheChangedKind() =
        assertEvery(
            """
            defmodule A do
              def f, do: 1
              defp f, do: if(true, [])
            end
            """.trimIndent(),
            "module Elixir.A raised invalid_if_keys `if(true, [])`",
        )

    fun testDuplicateDefaults() =
        assertEvery(
            """
            defmodule A do
              def f(a \\ 1), do: a
              def f(a \\ 2), do: a
            end
            """.trimIndent(),
            "module Elixir.A raised duplicate_defaults `def f(a \\\\ 2), do: a`",
        )

    fun testAnArityInAnEarlierDefinitionsDefaults() =
        assertEvery(
            """
            defmodule A do
              def f(a, b \\ 1), do: {a, b}
              def f(a), do: a
            end
            """.trimIndent(),
            "module Elixir.A raised defs_with_defaults `def f(a), do: a`",
        )

    fun testADefaultArityOfAnotherKind() =
        assertEvery(
            """
            defmodule A do
              def f(a), do: a
              defp f(a, b \\ 1), do: {a, b}
            end
            """.trimIndent(),
            "module Elixir.A raised changed_kind `defp f(a, b \\\\ 1), do: {a, b}`",
        )

    fun testDefaultsAreCheckedBeforeTheKind() =
        assertEvery(
            """
            defmodule A do
              def f(a, b \\ 1), do: {a, b}
              defp f(a), do: a
            end
            """.trimIndent(),
            "module Elixir.A raised defs_with_defaults `defp f(a), do: a`",
        )

    /** The definition's own body is expanded before it is stored, so from 1.15 its error is reported first. */
    fun testTheDefinitionsOwnBodyThenDefsWithDefaults() =
        assertSplit(
            """
            defmodule A do
              def f(a, b \\ 1), do: {a, b}
              def f(a) do
                __STACKTRACE__
              end
            end
            """.trimIndent(),
            "1.15.0-rc.0",
            "module Elixir.A raised stacktrace_not_allowed `__STACKTRACE__`",
            """
            module Elixir.A raised defs_with_defaults `def f(a) do`
              reported stacktrace_not_allowed `__STACKTRACE__`
            """.trimIndent(),
        )

    // `function_head`

    fun testAFunctionHead() =
        assertLevels(
            """
            defmodule A do
              def f(a)
            end
            """.trimIndent(),
            LEVELS,
        ) { version -> ending(version, "function_head `def f(a)`") }

    fun testAFunctionHeadIsAtItsFirstHead() =
        assertLevels(
            """
            defmodule A do
              def f(a)
              def f(b)
            end
            """.trimIndent(),
            LEVELS,
        ) { version -> ending(version, "function_head `def f(a)`") }

    /** 1.11 skips a bodiless definition whose last head has an `unquote`. */
    fun testAGeneratedHeadLast() =
        assertLevels(
            """
            defmodule A do
              def f(a)
              def unquote(:f)(a)
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            if (isBefore(version, "1.12.0-rc.1")) {
                "module Elixir.A compiled"
            } else {
                ending(version, "function_head `def f(a)`")
            }
        }

    fun testAGeneratedHeadFirst() =
        assertLevels(
            """
            defmodule A do
              def unquote(:f)(a)
              def f(a)
            end
            """.trimIndent(),
            LEVELS,
        ) { version -> ending(version, "function_head `def unquote(:f)(a)`") }

    // The import conflict

    fun testAnImportUsedInAFunction() =
        assertLevels(
            """
            defmodule A do
              import List
              def f(l), do: first(l)
              def first(x), do: x
            end
            """.trimIndent(),
            LEVELS,
        ) { version -> ending(version, "import_conflict `def first(x), do: x`") }

    fun testAnImportCapturedInAFunction() =
        assertLevels(
            """
            defmodule A do
              import List
              def f, do: &first/1
              def first(x), do: x
            end
            """.trimIndent(),
            LEVELS,
        ) { version -> ending(version, "import_conflict `def first(x), do: x`") }

    fun testAnImportedMacroUsedInAFunction() =
        assertLevels(
            """
            defmodule A do
              def f, do: alias!(B)
              def alias!(x), do: x
            end
            """.trimIndent(),
            LEVELS,
        ) { version -> ending(version, "import_conflict `def alias!(x), do: x`") }

    /** A spec `validate_spec/2` doesn't know is expanded with `Macro.expand/2`, which records the import it calls. */
    fun testAnImportCalledInABitstringSpec() =
        assertLevels(
            """
            defmodule A do
              import List
              def f(x), do: <<x::first([1])>>
              def first(y), do: y
            end
            """.trimIndent(),
            LEVELS,
        ) { version -> ending(version, "undefined_bittype `x::first([1])`", "import_conflict `def first(y), do: y`") }

    /** `Kernel.+/1`'s fold expands its argument once, which records the import it calls. */
    fun testAnImportCalledInASignedBitstringSpec() =
        assertLevels(
            """
            defmodule A do
              import List
              def f(x), do: <<x::+first([8])>>
              def first(y), do: y
            end
            """.trimIndent(),
            LEVELS,
        ) { version -> ending(version, "undefined_bittype `x::+first([8])`", "import_conflict `def first(y), do: y`") }

    fun testAnImportUsedInTheModuleBody() =
        assertEvery(
            """
            defmodule A do
              import List
              _ = first([1])
              def first(x), do: x
            end
            """.trimIndent(),
            "module Elixir.A compiled",
        )

    /** Before 1.18 the local checks come first; from 1.18 the conflict comes first, and stops them. */
    fun testAnImportConflictThenAnUndefinedLocal() =
        assertLevels(
            """
            defmodule A do
              import List
              def f(l), do: first(l)
              def first(x), do: x
              def g, do: nope()
            end
            """.trimIndent(),
            LEVELS,
        ) { version ->
            if (isBefore(version, "1.18.0-rc.0")) {
                ending(version, "undefined_function `nope()`", "import_conflict `def first(x), do: x`")
            } else {
                ending(version, "import_conflict `def first(x), do: x`")
            }
        }

    // The attribute checks

    fun testAnUndefinedOnLoadFunction() =
        assertLevels(
            """
            defmodule A do
              @on_load :init
              def f, do: 1
            end
            """.trimIndent(),
            LEVELS,
        ) { version -> ending(version, "undefined_attribute_function `defmodule A do`") }

    /** From 1.12 `@on_load` may name a private function. */
    fun testAPrivateOnLoadFunction() =
        assertSplit(
            """
            defmodule A do
              @on_load :init
              defp init, do: :ok
            end
            """.trimIndent(),
            "1.12.0-rc.0",
            "module Elixir.A raised wrong_kind_attribute_function `defmodule A do`",
            "module Elixir.A compiled",
        )

    /** A value the expander has no term for, written to an attribute Elixir checks, stops the module at the write. */
    fun testAnUncheckedWrite() =
        assertEvery(
            """
            defmodule A do
              @impl %{a: 1}
              def f, do: 1
            end
            """.trimIndent(),
            "module Elixir.A stopped `@impl %{a: 1}`",
        )

    /** The write in the clause the `case` selects is a statement, so the second write raises, as Elixir's does. */
    fun testASecondOnLoadAfterOneInASelectedClauseRaises() =
        assertEvery(
            """
            defmodule A do
              case 1 do
                _ -> @on_load :a
              end
              @on_load :b
              def a, do: :ok
              def b, do: :ok
            end
            """.trimIndent(),
            "module Elixir.A raised invalid_attribute_value `@on_load :b`",
        )

    fun testAnOnLoadInAClauseTheEvaluatorCannotSelectStopsTheModule() =
        assertEvery(
            "defmodule A do\n  case (receive do after 0 -> 1 end) do\n    _ -> @on_load :a\n  end\n  @on_load :b\n  def a, do: :ok\n  def b, do: :ok\nend",
            "module Elixir.A stopped `case (receive do after 0 -> 1 end) do`",
        )

    /** A value the expander has no term for stops the checks that read it. */
    fun testAnUnknownCompileValue() =
        assertEvery(
            """
            defmodule A do
              @compile %{a: 1}
              def f, do: 1
            end
            """.trimIndent(),
            "module Elixir.A stopped `defmodule A do`",
        )

    // Rendering

    /** Before 1.15 the first of [errors] raises; from 1.15 each is reported, in order, and the module is tainted. */
    private fun ending(version: String, vararg errors: String): String =
        if (isBefore(version, "1.15.0-rc.0")) {
            "module Elixir.A raised ${errors.first()}"
        } else {
            (listOf("module Elixir.A tainted") + errors.map { "  reported $it" }).joinToString("\n")
        }

    /** Each module's ending and its reported errors, each node as the first line of its source. */
    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

        return file.modules.flatMap { result ->
            val ended = when (val ended = result.ended) {
                ExpansionResult.Ended.Compiled -> "compiled"
                ExpansionResult.Ended.Tainted -> "tainted"
                is ExpansionResult.Ended.Raised -> "raised ${ended.error.kind} `${source(code, ended.error.at)}`"
                is ExpansionResult.Ended.Crashed ->
                    "crashed ${ended.error.kind} `${source(code, ended.error.at)}` ${ended.exception}"
                is ExpansionResult.Ended.Stopped -> "stopped `${source(code, ended.at)}`"
            }

            listOf("module ${result.module} $ended") +
                result.errors.map { "  reported ${it.kind} `${source(code, it.at)}`" }
        }.joinToString("\n")
    }

    private fun source(code: String, node: ElixirAst) = node.meta.origin.substring(code).lines().first()
}
