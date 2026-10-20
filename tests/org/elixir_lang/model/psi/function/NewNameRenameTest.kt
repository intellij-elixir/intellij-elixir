package org.elixir_lang.model.psi.function

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.renameTargetAtCaret

/**
 * A function, protocol function or callback is renamed only to a name `def name(...)` takes unquoted: a reserved
 * word, an alias or a name with a character no identifier has is refused with a reason, and nothing is edited.
 */
class NewNameRenameTest : PlatformTestCase() {
    fun testFunctionRefusesANameThatNeedsQuotes() = assertEachRefused(FUNCTION)

    fun testProtocolFunctionRefusesANameThatNeedsQuotes() = assertEachRefused(PROTOCOL_FUNCTION)

    fun testCallbackRefusesANameThatNeedsQuotes() = assertEachRefused(CALLBACK)

    fun testFunctionRefusesAnEmptyName() {
        myFixture.configureByText("m.ex", FUNCTION)
        val before = myFixture.file.text
        val failure = runCatching { myFixture.renameTargetAtCaret("") }.exceptionOrNull()

        assertTrue(
            "Renaming to nothing should be refused, but " + (failure?.let { "failed with $it" } ?: "renamed it"),
            generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("A function name can't be empty") }
        )
        assertEquals(before, myFixture.file.text)
    }

    fun testFunctionAcceptsAnIdentifier() = assertEachAccepted(FUNCTION)

    fun testProtocolFunctionAcceptsAnIdentifier() = assertEachAccepted(PROTOCOL_FUNCTION)

    fun testCallbackAcceptsAnIdentifier() = assertEachAccepted(CALLBACK)

    private fun assertEachRefused(text: String) {
        for ((index, newName) in listOf("do", "Snoc", "g-h").withIndex()) {
            myFixture.configureByText("refused$index.ex", text)
            val before = myFixture.file.text
            val failure = runCatching { myFixture.renameTargetAtCaret(newName) }.exceptionOrNull()

            assertTrue(
                "Renaming to $newName should be refused, but " + (failure?.let { "failed with $it" } ?: "renamed it"),
                generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("$newName is not a function name") }
            )
            assertEquals(before, myFixture.file.text)
        }
    }

    private fun assertEachAccepted(text: String) {
        for ((index, newName) in listOf("snoc?", "_x").withIndex()) {
            myFixture.configureByText("accepted$index.ex", text)
            myFixture.renameTargetAtCaret(newName)

            assertEquals(newName, text.replace("<caret>", "").replace("snoc", newName), myFixture.file.text)
        }
    }

    private companion object {
        const val FUNCTION = "defmodule M do\n  def sn<caret>oc(q), do: q\n  def run, do: snoc(1)\nend\n"
        const val PROTOCOL_FUNCTION =
            "defprotocol P do\n  def sn<caret>oc(t)\nend\n\ndefmodule C do\n  def run(x), do: P.snoc(x)\nend\n"
        const val CALLBACK = "defmodule B do\n  @callback sn<caret>oc(term) :: term\nend\n"
    }
}
