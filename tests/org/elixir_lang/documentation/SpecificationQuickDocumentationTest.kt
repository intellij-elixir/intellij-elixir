package org.elixir_lang.documentation

/** The name at the head of a `@spec` documents the function it specifies. */
class SpecificationQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testAtASpecHead() {
        myFixture.configureByText(
            "spec.ex",
            """
            defmodule Spec do
              @spec ad<caret>d(integer, integer) :: integer
              @doc "Adds two numbers together."
              def add(a, b), do: a + b
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the @doc, got: $documentation", documentation?.contains("Adds two numbers together.") == true)
    }
}
