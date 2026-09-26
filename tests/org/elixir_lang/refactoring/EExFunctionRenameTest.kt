package org.elixir_lang.refactoring

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.renameTargetAtCaret

/** Renaming an EEx function rewrites the name inside its name atom and keeps the `:`, as it does its calls. */
class EExFunctionRenameTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    fun testRenamingAnEExFunctionKeepsItsNameAnAtom() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "eex_rename.ex",
            """
            defmodule Templates do
              require EEx

              EEx.function_from_string(:def, :rendered, "", [:a])

              def caller(x), do: rende<caret>red(x)
            end
            """.trimIndent()
        )

        myFixture.renameTargetAtCaret("shown")

        assertEquals(
            """
            defmodule Templates do
              require EEx

              EEx.function_from_string(:def, :shown, "", [:a])

              def caller(x), do: shown(x)
            end
            """.trimIndent(),
            myFixture.editor.document.text
        )
    }
}
