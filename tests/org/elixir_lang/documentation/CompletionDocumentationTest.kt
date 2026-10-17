package org.elixir_lang.documentation

/** The documentation popup beside a completion item shows the `@doc` of the function the item completes. */
class CompletionDocumentationTest : QuickDocumentationTestCase() {
    fun testTheItemOfADocumentedFunctionShowsItsDoc() {
        myFixture.configureByText(
            "completing.ex",
            """
            defmodule Documented do
              @doc "Adds two numbers together."
              def add(a, b), do: a + b

              def address, do: :home
            end

            defmodule Caller do
              def calls, do: Documented.ad<caret>
            end
            """.trimIndent()
        )

        val add = myFixture.completeBasic().orEmpty().single { it.lookupString == "add" }
        val documentation = myFixture.quickDocumentationOf(project, add)

        assertTrue("Expected the @doc, got: $documentation", documentation?.contains("Adds two numbers together.") == true)
        assertTrue("Expected the module, got: $documentation", documentation?.contains("<b>Documented</b>") == true)
    }
}
