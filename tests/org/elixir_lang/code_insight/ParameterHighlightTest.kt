package org.elixir_lang.code_insight

import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.Arguments

/** The parameter Parameter Info highlights for an argument is the one the call binds it to, with defaults left out. */
class ParameterHighlightTest : PlatformTestCase() {
    /** Elixir fills the last defaults first, so `h(x, y)` binds `a` and `c`, and the second argument is `c`. */
    fun testTheSecondArgumentOfATwoArgumentCallIsTheParameterTheDefaultsLeave() =
        assertHighlighted("c", definer("h(x, y<caret>)"), index = 1)

    fun testAnArgumentOfACallWithAllTheParametersIsTheParameterAtItsPosition() =
        assertHighlighted("b \\\\ 1", definer("h(x, y<caret>, z, w)"), index = 1)

    /**
     * A default declared after a clause without one is still a default (Elixir warns), though the signature Elixir
     * records, `m(a, b, c)`, drops it: `m(x, y)` still binds `a` and `c`.
     */
    fun testAnArgumentIsBoundByADefaultTheRecordedSignatureDrops() =
        assertHighlighted(
            "c",
            """
            defmodule Definer do
              def m(a, b, c), do: {a, b, c}
              def m(a, b \\ 0, c), do: {a, b, c}

              def calls(x, y), do: m(x, y<caret>)
            end
            """.trimIndent(),
            index = 1
        )

    /** A bare head declares no default, but a clause below it may (Elixir warns): `m(x, y)` still binds `a` and `c`. */
    fun testAnArgumentIsBoundByADefaultAClauseBelowABareHeadDeclares() =
        assertHighlighted(
            "c",
            """
            defmodule Definer do
              def m(a, b, c)
              def m(a, b \\ 0, c), do: {a, b, c}

              def calls(x, y), do: m(x, y<caret>)
            end
            """.trimIndent(),
            index = 1
        )

    /** The pipe fills the first parameter, so the first argument in the parentheses is the second. */
    fun testAnArgumentOfAPipedCallIsTheParameterAfterTheOneThePipeFills() =
        assertHighlighted("b", definer("x |> f(y<caret>)"), index = 0)

    /** A pipe fills the outermost parentheses, so the primary arguments of `g(y)(z)` keep their positions. */
    fun testAnArgumentOfAPipedCallWithSecondaryArgumentsIsAtItsPosition() =
        assertHighlighted("a", definer("x |> g(y<caret>)(z)"), index = 0)

    fun testADoBlockFillsTheLastParameter() =
        assertHighlighted("a", definer("f(y<caret>) do\n      :ok\n    end"), index = 0)

    fun testATrailingKeywordListIsTheParameterItReaches() =
        assertHighlighted("b", definer("f(y, b: z<caret>)"), index = 1)

    /** How many arguments a splice gives is unknown, so no argument's parameter is known. */
    fun testNoParameterIsHighlightedAfterASplice() =
        assertHighlighted("", definer("quote do\n      f(unquote_splicing(xs), y<caret>)\n    end"), index = 1)

    fun testAnArgumentPastTheLastParameterDisablesTheSignature() =
        assertDisabled(true, definer("f(x, y, z<caret>)"), index = 2)

    fun testAPipedArgumentPastTheLastParameterDisablesTheSignature() =
        assertDisabled(true, definer("x |> f(y, z<caret>)"), index = 1)

    fun testAnArgumentAtAParameterLeavesTheSignatureEnabled() =
        assertDisabled(false, definer("x |> f(y<caret>)"), index = 0)

    private fun assertHighlighted(expected: String, text: String, index: Int) = assertEquals(expected, highlighted(text, index))

    private fun assertDisabled(disabled: Boolean, text: String, index: Int) =
        assertEquals(disabled, presented(text, index).isTextDisabled)

    private fun highlighted(text: String, index: Int): String =
        presented(text, index).let { ui -> ui.text.substring(ui.highlightStart, ui.highlightEnd) }

    private fun presented(text: String, index: Int): MockParameterInfoUIContext<Arguments> {
        myFixture.configureByText("highlight.ex", text)

        val handler = ParameterInfo()
        val context = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val arguments = handler.findElementForParameterInfo(context)!!

        handler.showParameterInfo(arguments, context)

        val ui = MockParameterInfoUIContext(arguments).also { it.currentParameterIndex = index }

        handler.updateUI(context.itemsToShow!!.single() as Signature, ui)

        return ui
    }

    /** [call] in a function body, beside the functions it calls. */
    private fun definer(call: String) =
        """
        defmodule Definer do
          def f(a, b), do: {a, b}
          def g(a), do: fn b -> {a, b} end
          def h(a, b \\ 1, c, d \\ 2), do: {a, b, c, d}

          def calls(x, y, z, w, xs) do
            CALL
          end
        end
        """.trimIndent().replace("CALL", call)
}
