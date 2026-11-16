package org.elixir_lang.code_insight

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementPresentation
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.expander.ExpanderSourceRows.withExpanderSwitch

/**
 * A head a macro's output placed, with nothing written declaring it, borrows nothing from the macro that wrote it.
 * `use Application` defines `stop/1`, whose head its debug info places at the macro's clause, so it gets the stub.
 * Completion reads the expander only behind the harness's switch.
 */
class PlacedHeadCompletionTest : ExpanderSettingTestCase() {
    fun testAHeadAMacroPlacedRendersTheStubWithTheExpanderBehindCompletion() = withTheExpanderBehindCompletion {
        val stop = completedStop().let { LookupElementPresentation().also(it::renderElement) }

        assertEquals("(arg1)", stop.tailText)
    }

    fun testAHeadAMacroPlacedIsInsertedWithTheStubsParametersWithTheExpanderBehindCompletion() = withTheExpanderBehindCompletion {
        val stop = completedStop()
        myFixture.lookup.currentItem = stop
        myFixture.finishLookup('\n')

        assertTrue("Expected `stop(arg1)`, got: ${myFixture.editor.document.text}", "stop(arg1)" in myFixture.editor.document.text)
    }

    /** [test] with the expander behind completion too, and a lone lookup shown rather than inserted. */
    private fun withTheExpanderBehindCompletion(test: () -> Unit) = withTheExpanderOn {
        CodeInsightSettings.runWithTemporarySettings<Unit, Throwable> { settings ->
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
            withExpanderSwitch(test)
        }
    }

    /** The `stop` lookup completion offers at `sto` in a module that uses `Application`. */
    private fun completedStop() = run {
        myFixture.configureByText(
            "placed.ex",
            """
            defmodule Placed do
              use Application

              def calls, do: sto<caret>
            end
            """.trimIndent()
        )
        myFixture.complete(CompletionType.BASIC, 1)

        val stop = myFixture.lookupElements.orEmpty().firstOrNull { LookupElementPresentation().also(it::renderElement).itemText == "stop" }
        assertNotNull("Expected `stop` among the lookups, got ${myFixture.lookupElementStrings} in ${myFixture.editor.document.text}", stop)

        stop!!
    }
}
