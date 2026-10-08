package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * `defoverridable`, `super` and `&super` over a module's definitions: a definition made overridable leaves the table
 * until it is redefined or the body ends, `super` stores it as a private definition, and each way they raise.
 */
class OverridableMacroTest : ExpanderTestCase() {
    override val exports: Exports = ModuleFixtures.EXPORTS
    override val kernel: KernelImports = ModuleFixtures.KERNEL

    fun testADefinitionMadeOverridableIsStoredBackAfterTheOnesAroundIt() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  defoverridable f: 1\n  def g, do: 1\nend",
            "module Elixir.A compiled | def g/0 line 4 clauses 1 | def f/1 line 2 clauses 1",
        )

    fun testADefinitionRedefinedIsTheNewOne() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  defoverridable f: 1\n  def f(x), do: x + 1\nend",
            "module Elixir.A compiled | def f/1 line 4 clauses 1",
        )

    fun testEveryDefinitionOfTheListIsTaken() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  def g, do: 1\n  defoverridable f: 1, g: 0\n  def h, do: 2\nend",
            "module Elixir.A compiled | def h/0 line 5 clauses 1 | def f/1 line 2 clauses 1 | def g/0 line 3 clauses 1",
        )

    fun testSuperStoresTheDefinitionItOverridesAsPrivate() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  defoverridable f: 1\n  def f(x), do: super(x)\nend",
            "module Elixir.A compiled | defp f (overridable 1)/1 line 2 clauses 1 | def f/1 line 4 clauses 1",
        )

    fun testSuperOfAMacroStoresAPrivateMacro() =
        assertEvery(
            "defmodule A do\n  defmacro m(x), do: x\n  defoverridable m: 1\n  defmacro m(x), do: super(x)\nend",
            "module Elixir.A compiled | defmacrop m (overridable 1)/1 line 2 clauses 1 | defmacro m/1 line 4 clauses 1",
        )

    fun testTheStoredDefinitionIsNamedForHowOftenItWasMadeOverridable() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  defoverridable f: 1\n  def f(x), do: super(x)\n" +
                "  defoverridable f: 1\n  def f(x), do: super(x)\nend",
            "module Elixir.A compiled | defp f (overridable 1)/1 line 2 clauses 1 | " +
                "defp f (overridable 2)/1 line 4 clauses 1 | def f/1 line 6 clauses 1",
        )

    fun testSuperIsTracedAsALocalFunctionFrom1_18_4() {
        val code = "defmodule A do\n  def f(x), do: x\n  defoverridable f: 1\n  def f(x), do: super(x)\nend"
        val traced = "local_function Elixir.A.f (overridable 1)/1"

        assertEquals(
            LEVELS.joinToString("\n") { "$it: " + if (isBefore(it, "1.18.4")) "" else traced },
            tracedLocals(code),
        )
    }

    fun testACaptureOfSuperByArityIsTracedAsALocalFunctionFrom1_18_4() {
        val code = "defmodule A do\n  def f(x), do: x\n  defoverridable f: 1\n  def f(x), do: (&super/1).(x)\nend"
        val traced = "local_function Elixir.A.f (overridable 1)/1"

        assertEquals(
            LEVELS.joinToString("\n") { "$it: " + if (isBefore(it, "1.18.4")) "" else traced },
            tracedLocals(code),
        )
    }

    /** Each leg's local-function events of [code], as `version: event; event`. */
    private fun tracedLocals(code: String) =
        LEVELS.joinToString("\n") { version ->
            "$version: " + events(code, version).filter { it.startsWith("local_function") }.joinToString("; ")
        }

    fun testASuperCallOfAnotherArityRaises() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  defoverridable f: 1\n  def f(x), do: super()\nend",
            "module Elixir.A raised wrong_number_of_args_for_super `super()`",
        )

    fun testSuperOfADefinitionNotMadeOverridableRaises() =
        assertEvery(
            "defmodule A do\n  def f(x), do: super(x)\nend",
            "module Elixir.A raised no_super `super(x)`",
        )

    fun testACaptureOfSuperCapturesTheStoredDefinition() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  defoverridable f: 1\n  def f(x), do: (&super/1).(x)\nend",
            "module Elixir.A compiled | defp f (overridable 1)/1 line 2 clauses 1 | def f/1 line 4 clauses 1",
        )

    fun testACaptureOfSuperWithArgumentsCapturesTheStoredDefinition() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  defoverridable f: 1\n  def f(x), do: (&super(&1)).(x)\nend",
            "module Elixir.A compiled | defp f (overridable 1)/1 line 2 clauses 1 | def f/1 line 4 clauses 1",
        )

    fun testACaptureOfSuperOfAnotherArityRaises() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  defoverridable f: 1\n  def f(x), do: &super/2\nend",
            "module Elixir.A raised wrong_number_of_args_for_super `&super/2`",
        )

    fun testAnUndefinedDefinitionCannotBeMadeOverridableAndEndsTheBody() =
        assertEvery(
            "defmodule A do\n  defoverridable f: 1\n  def g, do: 1\nend",
            "module Elixir.A raised overridable_not_defined `defoverridable f: 1`",
        )

    fun testAnErrorWhereTheBodyMayNotRunStopsTheModuleInsteadOfEndingIt() =
        assertEvery(
            "defmodule A do\n  if false do\n    defoverridable missing: 0\n  end\nend",
            "module Elixir.A stopped `defoverridable missing: 0`",
        )

    fun testTwoBranchesThatTakeTheSameDefinitionStopAtTheSecond() =
        assertEvery(
            "defmodule A do\n  def f, do: 1\n  case 1 do\n    1 -> defoverridable f: 0\n" +
                "    _ -> defoverridable f: 0\n  end\nend",
            "module Elixir.A stopped `defoverridable f: 0`",
        )

    fun testAStatementAfterACallThatMayNotRunStopsTheModuleInsteadOfEndingIt() =
        assertEvery(
            "defmodule A do\n  def f, do: 1\n  if false do\n    defoverridable f: 0\n  end\n  defoverridable f: 0\nend",
            "module Elixir.A stopped `defoverridable f: 0`",
        )

    fun testEachElementIsAFunctionNameAndArity() =
        assertEvery(
            "defmodule A do\n  defoverridable [:f]\nend",
            "module Elixir.A raised overridable_bad_element `defoverridable [:f]`",
        )

    fun testAnArityAbove255IsRefused() =
        assertEvery(
            "defmodule A do\n  defoverridable f: 256\nend",
            "module Elixir.A raised overridable_bad_element `defoverridable f: 256`",
        )

    fun testTheElementsBeforeTheBadOneAreTaken() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  defoverridable [{:f, 1}, :g]\nend",
            "module Elixir.A raised overridable_bad_element `defoverridable [{:f, 1}, :g]`",
        )

    fun testADefinitionRedefinedAsTheOtherKindCannotBeMadeOverridableAgain() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  defoverridable f: 1\n  defmacro f(x), do: x\n" +
                "  defoverridable f: 1\nend",
            "module Elixir.A raised bad_kind `defmacro f(x), do: x`",
        )

    fun testADefinitionRedefinedAsTheOtherKindIsRefusedWhenTheBodyEnds() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  defoverridable f: 1\n  defmacro f(x), do: x\nend",
            "module Elixir.A raised bad_kind `defmacro f(x), do: x` | defmacro f/1 line 4 clauses 1",
        )

    fun testAListNotKnownStopsTheModuleAndTakesNothing() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  defoverridable inspect(1)\nend",
            "module Elixir.A stopped `defoverridable inspect(1)` | def f/1 line 2 clauses 1",
        )

    fun testACallInAClauseMakesTheDefinitionsAfterItUnordered() {
        val code = "defmodule A do\n  def f(x), do: x\n  case 1 do\n    _ -> defoverridable f: 1\n  end\n  def g, do: 1\nend"
        val answer = { version: String ->
            val level = ElixirLanguageLevel.of(version)
            val module = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs).modules.single()
            val ordered = module.units.filter { it.owner is ExpansionResult.Owner.Definition }.map { it.ordered }

            "$ordered ${module.table.entries.keys.map { "${it.name}/${it.arity}" }}"
        }

        assertEquals(
            LEVELS.joinToString("\n") { "$it: [true, false] [g/0, f/1]" },
            LEVELS.joinToString("\n") { "$it: ${answer(it)}" },
        )
    }

    fun testTheCallInAFunctionRunsWhenTheFunctionDoes() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  def g, do: defoverridable(f: 1)\nend",
            "module Elixir.A compiled | def f/1 line 2 clauses 1 | def g/0 line 3 clauses 1",
        )

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

        return file.modules.joinToString("\n") { ModuleRendering.render(code, it) }
    }
}
