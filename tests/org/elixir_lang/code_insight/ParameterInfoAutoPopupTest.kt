package org.elixir_lang.code_insight

import org.elixir_lang.declaration.ExpanderSettingTestCase

/**
 * The parameter-info popup as the user meets it: typed into, and appearing on its own or not.
 *
 * [ParameterInfoTest] pins which signatures the handler resolves, against a context whose `showHint` is a
 * no-op. That is a different question from whether anything is shown - the handler resolves the same
 * signature for `add(<caret>)` and `add(1,<caret>)`, yet only one of the two puts a popup on screen. These
 * tests drive the real typed-handler chain through [parameterInfoPopupAfterTyping] and assert on what
 * appears.
 */
class ParameterInfoAutoPopupTest : ExpanderSettingTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/code_insight/parameter_info"

    /** Typing the comma that begins the next argument pops the hint up. This is what #3934 restored. */
    fun testCommaPopsUpTheHint() {
        myFixture.configureByFile("auto_popup_comma.ex")

        val popup = myFixture.parameterInfoPopupAfterTyping(',')

        assertNotNull("Typing a comma should pop up the parameter hint", popup)
        assertEquals(listOf("augend, addend"), popup!!.signatures)
        assertEquals("The caret is on the second parameter", 1, popup.currentParameterIndex)
    }

    /**
     * Typing the opening parenthesis should pop the hint up on the first parameter, the same way the comma
     * does on the second - the platform's typed handler auto-popups for `(` and `,` alike.
     */
    fun testOpeningParenthesisPopsUpTheHint() {
        myFixture.configureByFile("auto_popup_opening_parenthesis.ex")

        val popup = myFixture.parameterInfoPopupAfterTyping('(')

        assertNotNull("Typing an opening parenthesis should pop up the parameter hint", popup)
        assertEquals(listOf("augend, addend"), popup!!.signatures)
        assertEquals("The caret is on the first parameter", 0, popup.currentParameterIndex)
    }

    /** Several arities means several signatures, which is the shape `Enum.reduce` presents. */
    fun testCommaPopsUpTheHintWithSeveralSignatures() {
        myFixture.configureByFile("auto_popup_many_arities_comma.ex")

        val popup = myFixture.parameterInfoPopupAfterTyping(',')

        assertNotNull("Typing a comma should pop up the parameter hint", popup)
        assertEquals(3, popup!!.signatures.size)
        assertEquals(1, popup.currentParameterIndex)
    }

    fun testOpeningParenthesisPopsUpTheHintWithSeveralSignatures() {
        myFixture.configureByFile("auto_popup_many_arities_opening_parenthesis.ex")

        val popup = myFixture.parameterInfoPopupAfterTyping('(')

        assertNotNull("Typing an opening parenthesis should pop up the parameter hint", popup)
        assertEquals(3, popup!!.signatures.size)
        assertEquals(0, popup.currentParameterIndex)
    }

    /** A call resolved from another file, which is the shape a call into a dependency or the SDK takes. */
    fun testCommaPopsUpTheHintForACallResolvedInAnotherFile() {
        myFixture.configureByFiles("auto_popup_remote_comma.ex", "auto_popup_remote_declaration.ex")

        val popup = myFixture.parameterInfoPopupAfterTyping(',')

        assertNotNull("Typing a comma should pop up the parameter hint", popup)
        assertEquals(2, popup!!.signatures.size)
        assertEquals(1, popup.currentParameterIndex)
    }

    fun testOpeningParenthesisPopsUpTheHintForACallResolvedInAnotherFile() {
        myFixture.configureByFiles("auto_popup_remote_opening_parenthesis.ex", "auto_popup_remote_declaration.ex")

        val popup = myFixture.parameterInfoPopupAfterTyping('(')

        assertNotNull("Typing an opening parenthesis should pop up the parameter hint", popup)
        assertEquals(2, popup!!.signatures.size)
        assertEquals(0, popup.currentParameterIndex)
    }

    /**
     * Accepting a completion leaves the caret inside the parameter placeholders the insert handler
     * added, and the hint appears there without the user typing `(` themselves.
     *
     * The `Tab` that accepts the completion also advances the freshly-started placeholder template by
     * one stop - the same `Tab` a user pressed to finish "reduce" carries them onto the template's
     * first parameter, landing on the second declared parameter (`fun`, index 1), not the first.
     */
    fun testAcceptingACompletionPopsUpTheHint() {
        myFixture.configureByFiles("auto_popup_completion.ex", "auto_popup_completion_declaration.ex")

        val popup = myFixture.parameterInfoPopupAfterAcceptingCompletion("reduce")

        assertEquals(
            "The insert handler puts the parameters in as placeholders",
            "ParameterInfo.CompletionRemote.reduce(enumerable, fun)",
            myFixture.file.text.lines().first { it.contains("reduce(") }.trim()
        )
        assertNotNull("Accepting a completion should pop up the parameter hint", popup)
        /* `reduce_while` is not offered: the references resolve as incomplete code, which returns every
           function the name is a prefix of, and only the function being called is kept. */
        assertEquals(listOf("enumerable, fun"), popup!!.signatures)
        assertEquals(
            "The Tab that accepted the completion also advanced the template to the second parameter",
            1,
            popup.currentParameterIndex
        )
    }

    /**
     * Leaving the argument list and coming back shows nothing. Pinned as the platform's own behaviour, not
     * as a defect: no character is typed, so nothing asks for a popup, and Java does the same - the hint is
     * recalled with Ctrl+P.
     */
    fun testReturningToTheArgumentListShowsNothing() {
        myFixture.configureByFile("auto_popup_caret_returns.ex")

        assertNull(
            "Returning the caret to an argument list should not pop the hint up on its own",
            myFixture.parameterInfoPopupAfterLeavingAndReturning()
        )
    }
}
