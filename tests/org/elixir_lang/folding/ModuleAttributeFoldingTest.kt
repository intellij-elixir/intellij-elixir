package org.elixir_lang.folding

import com.intellij.codeInsight.folding.CodeFoldingManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

class ModuleAttributeFoldingTest : PlatformTestCase() {
    fun testAttributeFoldsToValue() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Timeouts do
                  @timeout 5000

                  def timeout, do: @timeout
                end
            """.trimIndent()
        )

        assertEquals("5000", attributePlaceholder("@timeout"))
    }

    fun testForFoldsToForModule() {
        myFixture.configureByText(
            "test.ex",
            """
                defimpl Inspect, for: Tuple do
                  def inspect(_, _), do: @for
                end
            """.trimIndent()
        )

        assertEquals("Tuple", attributePlaceholder("@for"))
    }

    fun testForWithoutForFoldsToEnclosingModule() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Outer do
                  defimpl Inspect do
                    def inspect(_, _), do: @for
                  end
                end
            """.trimIndent()
        )

        assertEquals("Outer", attributePlaceholder("@for"))
    }

    fun testForAsOperandFoldsToForModule() {
        myFixture.configureByText(
            "test.ex",
            """
                defimpl Inspect, for: Tuple do
                  def inspect(term, _) do
                    if term == @for do
                      "{}"
                    end
                  end
                end
            """.trimIndent()
        )

        assertEquals("Tuple", attributePlaceholder("@for"))
    }

    fun testForWithForListFoldsToList() {
        myFixture.configureByText(
            "test.ex",
            """
                defimpl Inspect, for: [Tuple, List] do
                  def inspect(_, _), do: @for
                end
            """.trimIndent()
        )

        assertEquals("[Tuple, List]", attributePlaceholder("@for"))
    }

    fun testProtocolFoldsToProtocol() {
        myFixture.configureByText(
            "test.ex",
            """
                defimpl Inspect, for: Tuple do
                  def inspect(_, _), do: @protocol
                end
            """.trimIndent()
        )

        assertEquals("Inspect", attributePlaceholder("@protocol"))
    }

    fun testDocumentationFoldsToElision() = assertDocumentationFoldsToElision("@doc")
    fun testDocumentationWithSpaceAfterAtFoldsToElision() = assertDocumentationFoldsToElision("@ doc")

    private fun assertDocumentationFoldsToElision(attribute: String) {
        myFixture.configureByText(
            "test.ex",
            "defmodule Documented do\n  $attribute \"\"\"\n  Says hello.\n  \"\"\"\n  def hello, do: :ok\nend\n"
        )
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)

        val elisions = myFixture.editor.foldingModel.allFoldRegions.filter { it.placeholderText == "\"...\"" }

        assertEquals(
            "regions: " + myFixture.editor.foldingModel.allFoldRegions.map { "${it.placeholderText} ${it.startOffset}" },
            1,
            elisions.size
        )
    }

    fun testReadsWithAndWithoutSpaceAfterAtFoldAsOneGroup() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Timeouts do
                  @timeout 5000

                  def one, do: @timeout
                  def two, do: @ timeout
                  def three, do: @(timeout)
                end
            """.trimIndent()
        )

        assertEquals(1, readGroups().size)
    }

    fun testReadsOfTheMicroSignFoldAsOneGroup() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.14.0"))
        myFixture.configureByText(
            "test.ex",
            "defmodule Timeouts do\n  @\u00b5 5000\n\n  def one, do: @\u00b5\n  def two, do: @\u00b5\nend\n"
        )

        assertEquals(1, readGroups().size)
    }

    fun testReadsOfTheMicroSignFoldAsOneGroupBefore1_14() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.13.0"))
        myFixture.configureByText(
            "test.ex",
            "defmodule Timeouts do\n  @\u00b5 5000\n\n  def one, do: @\u00b5\n  def two, do: @\u00b5\nend\n"
        )

        assertEquals(1, readGroups().size)
    }

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The folding groups of the reads, which fold to the declaration's value. */
    private fun readGroups(): Set<Any> {
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
        val reads = myFixture.editor.foldingModel.allFoldRegions.filter { it.placeholderText == "5000" }

        assertTrue("fewer than two reads folded to the value", reads.size >= 2)
        assertTrue("a read folded without a group", reads.all { it.group != null })

        return reads.mapNotNull { it.group }.toSet()
    }

    private fun attributePlaceholder(attribute: String): String? {
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
        val start = myFixture.file.text.lastIndexOf(attribute)
        val regions = myFixture.editor.foldingModel.allFoldRegions
        assertTrue("The folding pass built no regions at all", regions.isNotEmpty())

        return regions
            .singleOrNull { it.startOffset == start && it.endOffset == start + attribute.length }
            ?.placeholderText
    }
}
