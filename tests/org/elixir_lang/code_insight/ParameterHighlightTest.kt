package org.elixir_lang.code_insight

import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.eachExpander
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
        assertHighlighted("a", definer(withDoBlock("f(y<caret>)")), index = 0)

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

    /** The argument at the caret is the one about to be typed, so it is the parameter it fills once typed. */
    fun testAnEmptyCallOfOptionalParametersHighlightsTheFirst() =
        assertHighlighted("a \\\\ 1", definer("o(<caret>)"), index = 0)

    fun testAnEmptyCallOfOptionalParametersLeavesTheSignatureEnabled() =
        assertDisabled(false, definer("o(<caret>)"), index = 0)

    fun testTheNextArgumentOfOptionalParametersIsTheNextParameter() =
        assertHighlighted("b \\\\ 2", definer("o(y, <caret>)"), index = 1)

    fun testTheNextArgumentOfOptionalParametersLeavesTheSignatureEnabled() =
        assertDisabled(false, definer("o(y, <caret>)"), index = 1)

    fun testAnEmptyPipedCallOfOptionalParametersHighlightsTheOneAfterThePipe() =
        assertHighlighted("b \\\\ 2", definer("x |> o(<caret>)"), index = 0)

    /** Typed, `h(x, y)` binds `a` and `c`, so the second argument about to be typed is `c`. */
    fun testTheNextArgumentIsTheParameterTheDefaultsLeaveOnceItIsTyped() =
        assertHighlighted("c", definer("h(x, <caret>)"), index = 1)

    fun testTheNextArgumentPastTheLastParameterDisablesTheSignature() =
        assertDisabled(true, definer("o(x, y, <caret>)"), index = 2)

    /** A `do` block is the last argument, so the argument typed before it fills the parameter before the block's. */
    fun testAnEmptyCallWithADoBlockHighlightsTheParameterTheTypedArgumentFills() =
        assertHighlighted("a \\\\ 1", definer(withDoBlock("d(<caret>)")), index = 0)

    fun testAWrittenArgumentBeforeADoBlockHighlightsTheParameterItFills() =
        assertHighlighted("a \\\\ 1", definer(withDoBlock("d(x<caret>)")), index = 0)

    fun testAnArgumentTypedBetweenAPipeAndADoBlockPastTheLastParameterDisablesTheSignature() =
        assertDisabled(true, definer(withDoBlock("x |> d(<caret>)")), index = 0)

    /** The `do` block fills the last parameter, so an argument before it past the parameters fills none. */
    fun testAWrittenArgumentBeforeADoBlockPastTheLastParameterDisablesTheSignature() =
        assertDisabled(true, definer(withDoBlock("d(x, y<caret>)")), index = 1)

    /** An `unquote` in a typespec runs ordinary code, so a call in its argument calls a function. */
    fun testACallUnquotedInATypespecHasItsParameterHighlighted() =
        assertHighlighted(
            "a",
            """
            defmodule Unquoted do
              def helper(a), do: a

              defmacro typed do
                quote do
                  @type t :: unquote(helper(1<caret>))
                end
              end
            end
            """.trimIndent(),
            index = 0
        )

    /** A bitstring segment's `size(8)` is one of its options, not a call of the module's `size/1`. */
    fun testABitstringSegmentOptionHasNoParameterInfo() =
        assertNoSignature(
            """
            defmodule Segmented do
              def size(n), do: n

              def calls(x), do: <<x::size(8<caret>)>>
            end
            """.trimIndent()
        )

    /** Ecto's `assoc` in a join names an association; it calls no `assoc/2` the module defines. */
    fun testAnEctoJoinAssocHasNoParameterInfo() =
        assertNoSignature(
            """
            defmodule Joined do
              def assoc(struct, name), do: {struct, name}

              def calls(p), do: from(c in Comment, join: a in assoc(p<caret>, :author))
            end
            """.trimIndent()
        )

    /** A spec's head states its parameters' types itself. */
    fun testASpecHeadHasNoParameterInfo() =
        assertNoSignature(
            """
            defmodule Specced do
              @spec f(integer<caret>, atom) :: :ok
              def f(first, second), do: {first, second}
            end
            """.trimIndent()
        )

    /** A definition's head declares its parameters, so even a later clause's head shows none of the earlier one's. */
    fun testADefinitionHeadHasNoParameterInfo() =
        assertNoSignature(
            """
            defmodule Headed do
              def f(a, b), do: {a, b}
              def f(x<caret>, y), do: {x, y}
            end
            """.trimIndent()
        )

    private fun assertNoSignature(text: String) = eachExpander(module, testRootDisposable) {
        assertEmpty(myFixture.parameterInfoSignaturesIn("highlight.ex", text))
    }

    private fun assertHighlighted(expected: String, text: String, index: Int) = eachExpander(module, testRootDisposable) {
        assertEquals(expected, highlighted(text, index))
    }

    private fun assertDisabled(disabled: Boolean, text: String, index: Int) = eachExpander(module, testRootDisposable) {
        assertEquals(disabled, presented(text, index).isTextDisabled)
    }

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

    /** [call] with a `do` block, indented as [definer] indents its call. */
    private fun withDoBlock(call: String) = "$call do\n      :ok\n    end"

    /** [call] in a function body, beside the functions it calls. */
    private fun definer(call: String) =
        """
        defmodule Definer do
          def f(a, b), do: {a, b}
          def g(a), do: fn b -> {a, b} end
          def h(a, b \\ 1, c, d \\ 2), do: {a, b, c, d}
          def o(a \\ 1, b \\ 2), do: {a, b}
          def d(a \\ 1, b), do: {a, b}

          def calls(x, y, z, w, xs) do
            CALL
          end
        end
        """.trimIndent().replace("CALL", call)
}
