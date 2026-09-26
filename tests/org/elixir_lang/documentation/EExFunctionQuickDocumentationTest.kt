package org.elixir_lang.documentation

/** An EEx `function_from_*` declares a function, and the `@doc` written above it documents that function. */
class EExFunctionQuickDocumentationTest : QuickDocumentationTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    fun testAnEExFunctionShowsTheDocWrittenAboveIt() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "render.ex",
            """
            defmodule Views do
              require EEx

              @doc "Renders the page."
              EEx.function_from_string(:def, :render, "<%= @a %>", [:assigns])

              def page(assigns), do: ren<caret>der(assigns)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the @doc, got: $documentation", documentation?.contains("Renders the page.") == true)
    }

    /** A function's docs are its own clauses': a macro call of the same shape beside it, with its own `@doc`, is not one. */
    fun testAFunctionsDocIsNotMergedWithASameShapedMacroCalls() {
        myFixture.configureByText(
            "cached.ex",
            """
            defmodule Cached do
              @doc "The function's."
              def snoc(q), do: q

              @doc "The macro's."
              defcached snoc(q)

              def calls(q), do: sn<caret>oc(q)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the function's @doc, got: $documentation", "The function&#39;s." in documentation || "The function's." in documentation)
        assertFalse("Expected no macro @doc, got: $documentation", "The macro" in documentation)
    }
}
