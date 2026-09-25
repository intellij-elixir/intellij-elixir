package org.elixir_lang.documentation

/**
 * A clause at an arity another clause's defaults already cover is part of that function: Elixir rejects the pair as two
 * functions, and a decompiled module whose docs give the defaults only at the higher arity writes the lower one this way.
 */
class OverlappingClauseQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testQuickDocsAtTheLowerArityShowTheFunctionWithItsDefaults() {
        myFixture.configureByText(
            "overlapping.ex",
            """
            defmodule Overlapping do
              def snoc(p0) do
                p0
              end

              @doc "Appends an element."
              def snoc(q, x \\ nil) do
                {q, x}
              end

              def calls(a), do: sn<caret>oc(a)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the function's @doc, got: $documentation", "Appends an element." in documentation)
        assertTrue("Expected the head with its defaults, got: $documentation", "x \\\\ nil" in documentation)
    }
}
