package org.elixir_lang.intellij_elixir.refactoring.function

import org.elixir_lang.intellij_elixir.refactoring.InPlaceSymbolRenameTestCase

/**
 * Shift+F6 renames a `defdelegate` or EEx function from its declaration or an `apply/3` atom, as it does a `def`: the
 * symbol survives the inline template replacing its name, and a delegation keeps its target with `as:`.
 */
class DeclarationFormInPlaceRenameTest : InPlaceSymbolRenameTestCase() {
    private val source = """
        defmodule Delegator do
          require EEx

          defdelegate lost(q, x), to: Missing
          EEx.function_from_string(:def, :rendered, "<%= q %>", [:q])
        end

        defmodule Caller do
          def calls(a, b), do: {Delegator.lost(a, b), apply(Delegator, :lost, [a, b]), Delegator.rendered(a)}
        end
    """.trimIndent()

    fun testFromADelegationHead() = assertRenamed("defdelegate lo<caret>st(q", "lost", "moved", pinsTarget = true)

    fun testFromAnApplyAtomOfADelegation() = assertRenamed(":lo<caret>st, [a, b]", "lost", "moved", pinsTarget = true)

    fun testFromAnEExFunctionNameAtom() = assertRenamed(":def, :ren<caret>dered", "rendered", "shown")

    fun testFromAnEExFunctionCall() = assertRenamed("Delegator.ren<caret>dered(a)", "rendered", "shown")

    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    private fun assertRenamed(caretAt: String, oldName: String, newName: String, pinsTarget: Boolean = false) {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText("in_place.ex", source.replace(caretAt.replace("<caret>", ""), caretAt))

        inPlaceRenameAtCaret(newName)

        val renamed = source.replace(oldName, newName)
        // The delegation pins what it delegated to before the rename.
        val expected = if (pinsTarget) renamed.replace("to: Missing", "to: Missing, as: :$oldName") else renamed
        assertEquals(expected, myFixture.editor.document.text)
    }
}
