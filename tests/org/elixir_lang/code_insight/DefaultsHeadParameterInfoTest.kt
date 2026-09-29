package org.elixir_lang.code_insight

import org.elixir_lang.PlatformTestCase

/**
 * A bodiless head that declares defaults and the clauses after it are one function, so parameter info shows the head's
 * signature alone, however the call reaches it.
 */
class DefaultsHeadParameterInfoTest : PlatformTestCase() {
    private val definer = """
        defmodule Definer do
          def snoc(a \\ nil, b \\ nil)
          def snoc(a, b) when is_nil(a), do: {a, b}
          def snoc(a, b), do: {a, b}

          def local(a, b), do: snoc(LOCAL)
        end
    """.trimIndent()

    fun testALocalCallShowsTheHeadAlone() =
        assertEquals(listOf("a \\\\ nil, b \\\\ nil"), shown(definer.replace("snoc(LOCAL)", "snoc(a<caret>, b)")))

    fun testACallThroughImportExceptShowsTheHeadAlone() =
        assertEquals(
            listOf("a \\\\ nil, b \\\\ nil"),
            shown(
                definer.replace("snoc(LOCAL)", "snoc(a, b)") +
                    "\n\ndefmodule Caller do\n  import Definer, except: [snoc: 1]\n\n  def calls(a), do: snoc(a<caret>)\nend\n"
            )
        )

    /** A head whose arity is open shares no fixed arity, so it is not the function of the one its minimum names. */
    fun testAnOpenHeadIsNotTheFunctionItsMinimumNames() =
        assertEquals(
            2,
            shown(
                """
                defmodule Open do
                  def f(a), do: a
                  def f(a, unquote_splicing(rest)), do: [a | rest]

                  def calls(a), do: f(a<caret>)
                end
                """.trimIndent()
            ).size
        )

    private fun shown(text: String): List<String> {
        myFixture.configureByText("defaults_head.ex", text)

        return myFixture.parameterInfoSignaturesAtCaret()
    }
}
