package org.elixir_lang.folding

import com.intellij.codeInsight.folding.CodeFoldingManager
import org.elixir_lang.PlatformTestCase

class ModuleAttributeFoldingTest : PlatformTestCase() {
    fun testDocumentationFoldsToElision() = assertDocumentationFoldsToElision("@doc")
    fun testDocumentationWithSpaceAfterAtFoldsToElision() = assertDocumentationFoldsToElision("@ doc")

    private fun assertDocumentationFoldsToElision(attribute: String) {
        myFixture.configureByText(
            "test.ex",
            "defmodule Documented do\n  $attribute \"\"\"\n  Says hello.\n  \"\"\"\n  def hello, do: :ok\nend\n"
        )

        assertElisions(1)
    }

    fun testOneLineDocumentationDoesNotFold() {
        myFixture.configureByText("test.ex", "defmodule Documented do\n  @doc \"Says hello.\"\n  def hello, do: :ok\nend\n")

        assertElisions(0)
    }

    /** Asserts the file folds [count] documentation bodies to `"..."`. */
    private fun assertElisions(count: Int) {
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
        val regions = myFixture.editor.foldingModel.allFoldRegions

        assertEquals(
            "regions: " + regions.map { "${it.placeholderText} ${it.startOffset}" },
            count,
            regions.count { it.placeholderText == "\"...\"" }
        )
    }

    fun testTypeFoldsItsDefinition() = assertTypeFoldsItsDefinition("@type t ::\n  $TYPE_DEFINITION")

    /** `@type (t :: ...)` is quoted as `@type t :: ...`, so it folds the same operand. */
    fun testParenthesizedTypeFoldsItsDefinition() {
        assertTypeFoldsItsDefinition("@type (t ::\n  $TYPE_DEFINITION)")
        assertTypeFoldsItsDefinition("@type ((t ::\n  $TYPE_DEFINITION))")
    }

    fun testOneLineTypeDoesNotFold() {
        myFixture.configureByText("test.ex", "defmodule Typed do\n  @type t :: :a | :b\nend\n")

        assertEquals(emptyList<String>(), definitionFolds(":a | :b"))
    }

    private fun assertTypeFoldsItsDefinition(attribute: String) {
        myFixture.configureByText("test.ex", "defmodule Typed do\n  $attribute\nend\n")

        assertEquals("`$attribute`", listOf("..."), definitionFolds(TYPE_DEFINITION))
    }

    /** The placeholders of the folds over exactly [definition]. */
    private fun definitionFolds(definition: String): List<String> {
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)

        val start = myFixture.file.text.indexOf(definition)
        assertTrue("`$definition` is not in the file", start >= 0)

        return myFixture.editor.foldingModel.allFoldRegions
            .filter { it.startOffset == start && it.endOffset == start + definition.length }
            .map { it.placeholderText }
    }
}

/** A type definition that spans lines, so it folds. */
private const val TYPE_DEFINITION = ":a |\n    :b"
