package org.elixir_lang.code_insight

import org.elixir_lang.declaration.ExpanderSettingTestCase

/** A function a module overrides keeps the parameters Elixir recorded from the clauses it overrides. */
class OverriddenParameterInfoTest : ExpanderSettingTestCase() {
    /**
     * `GenServer.__using__` defines `handle_call/3` first and makes it overridable, so Elixir names its parameters from
     * that clause: `msg, from, state`. A compiled `__using__` quote writes no head the plugin can read, so the names
     * stay what the module's own clause gives.
     */
    fun testAGenServerCallbackIsNamedByTheModulesOwnClauseWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("atom, from, state"),
            shown(
                """
                defmodule Server do
                  use GenServer

                  def handle_call(:get, _from, state), do: {:reply, state, state}
                end

                defmodule Caller do
                  def calls(x), do: Server.handle_call(x<caret>, x, x)
                end
                """.trimIndent()
            )
        )
    }

    /** The clause the `use` defines and makes overridable comes first, so its head names the parameter. */
    fun testAnOverriddenClauseNamesTheParametersWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("a"),
            shown(
                """
                defmodule Injecting do
                  defmacro __using__(_) do
                    quote do
                      def f(a), do: a
                      defoverridable f: 1
                    end
                  end
                end

                defmodule Using do
                  use Injecting

                  def f(b), do: b
                end

                defmodule Caller do
                  def calls(x), do: Using.f(x<caret>)
                end
                """.trimIndent()
            )
        )
    }

    private fun shown(text: String): List<String> = myFixture.parameterInfoSignaturesIn("overridden.ex", text)
}
