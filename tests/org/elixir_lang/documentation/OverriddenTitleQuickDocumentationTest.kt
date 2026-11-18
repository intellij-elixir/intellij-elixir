package org.elixir_lang.documentation

/** A function a module overrides keeps the signature Elixir recorded from the clauses it overrides. */
class OverriddenTitleQuickDocumentationTest : QuickDocumentationTestCase() {
    /**
     * `GenServer.__using__` defines `handle_call/3` first and makes it overridable, so Elixir names its parameters from
     * that clause: `msg, from, state`. A compiled `__using__` quote writes no head the plugin can read, so the title
     * stays what the module's own clause gives.
     */
    fun testAGenServerCallbackIsTitledByTheModulesOwnClauseWithTheExpanderOn() = withTheExpanderOn {
        assertTitle(
            "handle_call(atom, from, state)",
            """
            defmodule Server do
              use GenServer

              def handle_call(:get, _from, state), do: {:reply, state, state}
            end

            defmodule Caller do
              def calls(x), do: Server.<caret>handle_call(x, x, x)
            end
            """.trimIndent()
        )
    }

    /** The clause the `use` defines and makes overridable comes first, so its head names the parameter. */
    fun testAnOverriddenClauseNamesTheTitleWithTheExpanderOn() = withTheExpanderOn {
        assertTitle(
            "f(a)",
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
              def calls(x), do: Using.<caret>f(x)
            end
            """.trimIndent()
        )
    }

    private fun assertTitle(title: String, text: String) {
        val documentation = quickDocumentationIn("overridden.ex", text)

        assertEquals(documentation, title, titleOf(documentation))
    }
}
