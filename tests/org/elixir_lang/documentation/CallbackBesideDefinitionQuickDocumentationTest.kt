package org.elixir_lang.documentation

/** A call documents the function it calls, not a `@callback` of the same name the module also declares. */
class CallbackBesideDefinitionQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testQuickDocAtACallShowsTheDefinitionsDocOverTheCallbacks() {
        myFixture.configureByText(
            "callback_beside_definition.ex",
            """
            defmodule Fetcher do
              @doc "The callback's contract."
              @callback fetch(term, term) :: term

              @doc "The definition's docs."
              def fetch(c, k), do: {c, k}

              def run(x, k), do: fe<caret>tch(x, k)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the definition's @doc, got: $documentation", documentation?.contains("definition") == true)
    }
}
