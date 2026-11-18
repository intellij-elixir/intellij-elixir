package org.elixir_lang.documentation

/**
 * A function's title is the signature Elixir records from every clause the module defines for it, in the order it
 * defines them, a clause a `use` injects included, and its entries are the functions the module's expansion defines.
 */
class InjectedClauseQuickDocumentationTest : QuickDocumentationTestCase() {
    /** The `use`'s clause comes first and names no variable, so the local clause after it names the parameter. */
    fun testALocalClauseAfterAUseNamesWhatTheInjectedClauseDoesNot() =
        assertTitle("f(b)", injected("def f(:x), do: :x", local = "use Injecting\n\n  def f(b), do: b"))

    fun testALocalClauseAfterAUseNamesWhatTheInjectedClauseDoesNotWithTheExpanderOn() =
        withTheExpanderOn(::testALocalClauseAfterAUseNamesWhatTheInjectedClauseDoesNot)

    /** The local clause comes first and names no variable, so the clause the `use` injects after it names the parameter. */
    fun testAnInjectedClauseAfterALocalOneNamesWhatTheLocalClauseDoesNot() =
        assertTitle("f(a)", injected("def f(a), do: a", local = "def f(:x), do: :x\n\n  use Injecting"))

    fun testAnInjectedClauseAfterALocalOneNamesWhatTheLocalClauseDoesNotWithTheExpanderOn() =
        withTheExpanderOn(::testAnInjectedClauseAfterALocalOneNamesWhatTheLocalClauseDoesNot)

    /** A `def` in a branch the module body never runs defines nothing, so it is no entry. */
    fun testADefUnderIfFalseIsNotListedWithTheExpanderOn() = withTheExpanderOn {
        val documentation = quickDocumentationIn(
            "injected.ex",
            """
            defmodule Branching do
              def f(a), do: a

              if false do
                def f(a, b), do: {a, b}
              end
            end

            defmodule Caller do
              def calls(x), do: Branching.<caret>f(x)
            end
            """.trimIndent()
        )

        assertTrue("Expected f/1, got: $documentation", "f(a)" in documentation)
        assertFalse("Expected no f/2, got: $documentation", "f(a, b)" in documentation)
    }

    private fun injected(clause: String, local: String): String = injectedUse(clause, local, call = "Using.<caret>f(x)")

    private fun assertTitle(title: String, text: String) {
        val documentation = quickDocumentationIn("injected.ex", text)

        assertEquals(documentation, title, titleOf(documentation))
    }
}
