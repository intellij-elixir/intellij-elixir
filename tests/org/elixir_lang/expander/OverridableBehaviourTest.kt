package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.ModuleExports.Behaviour
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * `defoverridable` of a behaviour: the module's definitions that are the behaviour's callbacks, after the three checks
 * `Module.make_overridable/2` makes of the behaviour.
 */
class OverridableBehaviourTest : ExpanderTestCase() {
    override val kernel: KernelImports = AttributeFixtures.KERNEL

    override val exports: Exports = Exports { module ->
        when (module) {
            "Elixir.Beh" -> behaviour(Behaviour.Callbacks(listOf(NameArity("f", 1), NameArity("MACRO-m", 2))))
            "Elixir.NoCallbacks" -> behaviour(Behaviour.None)
            "Elixir.Unreadable" -> behaviour(Behaviour.Unreadable)
            "Elixir.Gone" -> ModuleExports.Absent
            else -> AttributeFixtures.EXPORTS.of(module)
        }
    }

    fun testTheDefinitionsThatAreCallbacksAreTaken() =
        assertEvery(
            "defmodule A do\n  @behaviour Beh\n  def f(x), do: x\n  def g, do: 1\n  defmacro m(x), do: x\n" +
                "  defoverridable Beh\n  def f(x), do: 1\n  def g, do: 2\n  defmacro m(x), do: 3\nend",
            "module Elixir.A compiled | def g/0 line 4 clauses 2 | def f/1 line 7 clauses 1 | " +
                "defmacro m/1 line 9 clauses 1",
        )

    fun testAMacroCallbackIsTakenAsTheMacro() =
        assertEvery(
            "defmodule A do\n  @behaviour Beh\n  defmacro m(x), do: x\n  defoverridable Beh\n  def g, do: 1\nend",
            "module Elixir.A compiled | def g/0 line 5 clauses 1 | defmacro m/1 line 3 clauses 1",
        )

    fun testABehaviourTheModuleDoesNotHaveRaises() =
        assertEvery(
            "defmodule A do\n  def f(x), do: x\n  defoverridable Beh\nend",
            "module Elixir.A raised overridable_missing_behaviour `defoverridable Beh` | def f/1 line 2 clauses 1",
        )

    fun testABehaviourThatIsNotLoadedRaises() =
        assertEvery(
            "defmodule A do\n  @behaviour Beh\n  defoverridable Gone\nend",
            "module Elixir.A raised overridable_undefined_behaviour `defoverridable Gone`",
        )

    fun testNilIsNotLoaded() =
        assertEvery(
            "defmodule A do\n  defoverridable nil\nend",
            "module Elixir.A raised overridable_undefined_behaviour `defoverridable nil`",
        )

    fun testAModuleWithNoCallbacksRaises() =
        assertEvery(
            "defmodule A do\n  @behaviour NoCallbacks\n  defoverridable NoCallbacks\nend",
            "module Elixir.A raised overridable_not_a_behaviour `defoverridable NoCallbacks`",
        )

    fun testTheChecksRunInOrder() =
        assertEvery(
            "defmodule A do\n  defoverridable NoCallbacks\nend",
            "module Elixir.A raised overridable_not_a_behaviour `defoverridable NoCallbacks`",
        )

    fun testACallbacksListThatCannotBeReadStopsTheModule() =
        assertEvery(
            "defmodule A do\n  @behaviour Unreadable\n  def f(x), do: x\n  defoverridable Unreadable\nend",
            "module Elixir.A stopped `defoverridable Unreadable` | def f/1 line 3 clauses 1",
        )

    fun testABehaviourDefinedInTheSameFileHasCallbacksNotYetReadable() =
        assertEvery(
            "defmodule B do\n  @callback f(integer) :: integer\nend\ndefmodule A do\n  @behaviour B\n  def f(x), do: x\n" +
                "  defoverridable B\nend",
            "module Elixir.B compiled\nmodule Elixir.A stopped `defoverridable B` | def f/1 line 6 clauses 1",
        )

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val file = Expander.expandFile(lower(code, level), Env.empty(level, kernel), level, exports, structs)

        return file.modules.joinToString("\n") { ModuleRendering.render(code, it) }
    }

    private fun behaviour(answer: Behaviour) = ModuleExports.Present(emptyList(), emptyList(), hasInfo = true, answer)
}
