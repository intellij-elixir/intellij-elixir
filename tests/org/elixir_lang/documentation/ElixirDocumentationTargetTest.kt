package org.elixir_lang.documentation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.FileContentUtil

/** Elixir documentation survives the platform's pointer round trip, including an edit between creating and dereferencing it. */
class ElixirDocumentationTargetTest : QuickDocumentationTestCase() {
    private fun configure() {
        myFixture.configureByText(
            "target.ex",
            """
            defmodule Documented do
              @doc "Adds."
              def add(a, b), do: a + b
            end

            defmodule Caller do
              def calls, do: Documented.ad<caret>d(1, 2)
            end
            """.trimIndent()
        )
    }

    fun testACallShowsTheFunctionsDoc() {
        configure()

        val rendered = myFixture.quickDocumentationAtCaret(project)

        assertTrue("Expected the function's doc, got: $rendered", rendered?.contains("Adds.") == true)
    }

    fun testTheTargetRendersTheSameAfterItsPointerIsDereferenced() {
        configure()

        val target = myFixture.documentationTargetAtCaret(project)!!
        val rendered = renderedAfterPointerRoundTrip(target)

        assertTrue("Expected the function's doc, got: $rendered", rendered?.contains("Adds.") == true)
        assertEquals(myFixture.quickDocumentationAtCaret(project), rendered)
    }

    fun testTheTargetRendersTheSameAfterAnEditAboveItBetweenPointerAndDereference() {
        configure()

        val before = myFixture.quickDocumentationAtCaret(project)
        val target = myFixture.documentationTargetAtCaret(project)!!
        val after = renderedAfterPointerRoundTrip(target) {
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.insertString(0, "# A comment above everything.\n\n")
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
        }

        assertTrue("Expected the function's doc, got: $after", after?.contains("Adds.") == true)
        assertEquals(before, after)
    }

    /** A pointer dereferences to the file's current PSI, which a reparse replaces. */
    fun testTheTargetIsOnTheNewPsiAfterItsFileIsReparsedBetweenPointerAndDereference() {
        configure()

        val target = myFixture.documentationTargetAtCaret(project)!!
        val file = myFixture.file
        val rendered = renderedAfterPointerRoundTrip(target) {
            FileContentUtil.reparseFiles(project, listOf(file.virtualFile), true)
        }

        assertFalse("Expected the reparse to replace the file's PSI", file.isValid)
        assertTrue("Expected the function's doc, got: $rendered", rendered?.contains("Adds.") == true)
    }
}
