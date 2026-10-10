package org.elixir_lang.code_insight.completion.contributor

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementPresentation
import org.elixir_lang.PlatformTestCase

class DefaultsCompletionTest : PlatformTestCase() {
    /** With no bodiless head, the clause that declares the defaults speaks in the tail text, not the first. */
    fun testTheTailTextIsTheClauseThatDeclaresTheDefaults() {
        myFixture.configureByText(
            "defaults.ex",
            """
            defmodule Defaults do
              def walk([x | xs], acc), do: walk(xs, [x | acc])
              def walk([], acc \\ []), do: acc
              def walker(a), do: a
            end

            defmodule User do
              def run, do: Defaults.wal<caret>
            end
            """.trimIndent()
        )
        myFixture.complete(CompletionType.BASIC, 1)

        val walk = myFixture.lookupElements.orEmpty().first { lookupElement ->
            LookupElementPresentation().also(lookupElement::renderElement).itemText == "walk"
        }

        val tailText = LookupElementPresentation().also(walk::renderElement).tailText.orEmpty()

        assertTrue("Expected `([], acc \\\\ [])` in the tail, got: $tailText", tailText.startsWith("([], acc \\\\ [])"))
    }
}
