package org.elixir_lang.folding

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.util.TextRange
import org.elixir_lang.PlatformTestCase

class ModuleDirectiveGroupFoldingTest : PlatformTestCase() {
    fun testOneLineAliasDoesNotFold() = assertOneLineDirectiveDoesNotFold("alias")
    fun testOneLineImportDoesNotFold() = assertOneLineDirectiveDoesNotFold("import")
    fun testOneLineRequireDoesNotFold() = assertOneLineDirectiveDoesNotFold("require")
    fun testOneLineUseDoesNotFold() = assertOneLineDirectiveDoesNotFold("use")

    fun testTwoDirectivesOnOneLineDoNotFold() {
        configure(
            """
            defmodule Directives do
              alias Foo.Bar; alias Foo.Baz

              def name, do: :ok
            end
            """
        )

        assertEquals(emptyList<TextRange>(), directiveGroupFolds())
    }

    fun testOneLineGroupsOfDifferentDirectivesDoNotFold() {
        configure(
            """
            defmodule Directives do
              alias Foo.Bar
              import Foo.Baz
              require Logger
              use GenServer

              def name, do: :ok
            end
            """
        )

        assertEquals(emptyList<TextRange>(), directiveGroupFolds())
    }

    fun testMultiLineGroupFolds() {
        configure(
            """
            defmodule Directives do
              alias Foo.Bar
              alias Foo.Baz

              def name, do: :ok
            end
            """
        )

        assertEquals(listOf(rangeOf("Foo.Bar\n  alias Foo.Baz")), directiveGroupFolds())
    }

    fun testOneDirectiveSpanningLinesFolds() {
        configure(
            """
            defmodule Directives do
              alias Foo.{
                Bar,
                Baz
              }

              def name, do: :ok
            end
            """
        )

        assertEquals(listOf(rangeOf("Foo.{\n    Bar,\n    Baz\n  }")), directiveGroupFolds())
    }

    private fun assertOneLineDirectiveDoesNotFold(directive: String) {
        configure(
            """
            defmodule Directives do
              $directive Foo.Bar

              def name, do: :ok
            end
            """
        )

        assertEquals(emptyList<TextRange>(), directiveGroupFolds())
    }

    private fun configure(text: String) {
        myFixture.configureByText("directives.ex", text.trimIndent())
    }

    private fun rangeOf(text: String): TextRange {
        val start = myFixture.file.text.indexOf(text)
        assertTrue("`$text` is not in the file", start >= 0)

        return TextRange(start, start + text.length)
    }

    /** The ranges of the folds with a directive group's placeholder. */
    private fun directiveGroupFolds(): List<TextRange> {
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
        val regions = myFixture.editor.foldingModel.allFoldRegions
        assertTrue("The folding pass built no regions at all", regions.isNotEmpty())

        return regions.filter { it.placeholderText == "..." }.map { it.textRange }
    }
}
