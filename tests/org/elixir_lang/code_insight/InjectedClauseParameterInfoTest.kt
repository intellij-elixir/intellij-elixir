package org.elixir_lang.code_insight

import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.documentation.injectedUse

/**
 * A function's parameters are those of the signature Elixir records from every clause the module defines for it, in
 * the order it defines them, a clause a `use` injects included.
 */
class InjectedClauseParameterInfoTest : ExpanderSettingTestCase() {
    /** The `use`'s clause comes first and names no variable, so the local clause after it names the parameter. */
    fun testALocalClauseAfterAUseNamesWhatTheInjectedClauseDoesNot() =
        assertEquals(listOf("b"), shown(injected("def f(:x), do: :x", local = "use Injecting\n\n  def f(b), do: b")))

    fun testALocalClauseAfterAUseNamesWhatTheInjectedClauseDoesNotWithTheExpanderOn() =
        withTheExpanderOn(::testALocalClauseAfterAUseNamesWhatTheInjectedClauseDoesNot)

    /** The local clause comes first and names no variable, so the clause the `use` injects after it names the parameter. */
    fun testAnInjectedClauseAfterALocalOneNamesWhatTheLocalClauseDoesNot() =
        assertEquals(listOf("a"), shown(injected("def f(a), do: a", local = "def f(:x), do: :x\n\n  use Injecting")))

    fun testAnInjectedClauseAfterALocalOneNamesWhatTheLocalClauseDoesNotWithTheExpanderOn() =
        withTheExpanderOn(::testAnInjectedClauseAfterALocalOneNamesWhatTheLocalClauseDoesNot)

    /** A `def` in a branch the module body never runs defines nothing, so it has no signature. */
    fun testADefUnderIfFalseIsNotShownWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("a"),
            shown(
                """
                defmodule Branching do
                  def f(a), do: a

                  if false do
                    def f(a, b), do: {a, b}
                  end
                end

                defmodule Caller do
                  def calls(x), do: Branching.f(x<caret>)
                end
                """.trimIndent()
            )
        )
    }

    private fun injected(clause: String, local: String): String = injectedUse(clause, local, call = "Using.f(x<caret>)")

    private fun shown(text: String): List<String> = myFixture.parameterInfoSignaturesIn("injected.ex", text)
}
