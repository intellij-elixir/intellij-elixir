package org.elixir_lang.expander

import org.elixir_lang.code_insight.destinationLine
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.declaration.ExpanderSettingTestCase

/**
 * With "Run the expander" on, a call of the `message/1` a `defexception` may define reaches it as Elixir defines it:
 * only where the struct has a `:message` field (`Kernel.defexception/1`).
 */
class DefexceptionGotoDeclarationTest : ExpanderSettingTestCase() {
    override fun setUp() {
        super.setUp()
        expanderOn()
    }

    fun testAMessageCallOfAnExceptionWithoutAMessageFieldLandsNowhere() {
        configureMessageCall("[:reason]")

        assertEquals(emptyList<String>(), destinationLines())
    }

    fun testAMessageCallOfAnExceptionWithAMessageFieldLandsOnTheDefexception() {
        configureMessageCall("[:message]")

        assertEquals(listOf("defexception [:message]"), destinationLines())
    }

    /** `A`, whose `defexception` takes [fields], and a call of `A.message/1` from another module, the caret on it. */
    private fun configureMessageCall(fields: String) {
        myFixture.configureByText(
            "a.ex",
            "defmodule A do\n  defexception $fields\nend\n\ndefmodule B do\n  def f(e), do: A.mess<caret>age(e)\nend\n",
        )
    }

    /** The trimmed line of each place Go To Declaration lands from the caret. */
    private fun destinationLines(): List<String> = myFixture.gotoDeclarationTargetsAtCaret().orEmpty().map { it.destinationLine().text }
}
