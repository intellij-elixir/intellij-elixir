package org.elixir_lang.code_insight

import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.Arguments

/** The parameter Parameter Info highlights for an argument is the one the call binds it to, with defaults left out. */
class ParameterHighlightTest : PlatformTestCase() {
    private val headWithMiddleDefaults = """
        defmodule Definer do
          def h(a, b \\ 1, c, d \\ 2), do: {a, b, c, d}

          def calls(x, y, z, w), do: CALL
        end
    """.trimIndent()

    /** Elixir fills the last defaults first, so `h(x, y)` binds `a` and `c`, and the second argument is `c`. */
    fun testTheSecondArgumentOfATwoArgumentCallIsTheParameterTheDefaultsLeave() =
        assertEquals("c", highlighted(headWithMiddleDefaults.replace("CALL", "h(x, y<caret>)"), index = 1))

    fun testAnArgumentOfACallWithAllTheParametersIsTheParameterAtItsPosition() =
        assertEquals("b \\\\ 1", highlighted(headWithMiddleDefaults.replace("CALL", "h(x, y<caret>, z, w)"), index = 1))

    private fun highlighted(text: String, index: Int): String {
        myFixture.configureByText("highlight.ex", text)

        val handler = ParameterInfo()
        val context = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val arguments = handler.findElementForParameterInfo(context)!!

        handler.showParameterInfo(arguments, context)

        val ui = MockParameterInfoUIContext<Arguments>(arguments).also { it.currentParameterIndex = index }

        handler.updateUI(context.itemsToShow!!.single() as Signature, ui)

        return ui.text.substring(ui.highlightStart, ui.highlightEnd)
    }
}
