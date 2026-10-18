package org.elixir_lang.documentation

/** A function defined under a compile-time `if` is documented by the `@doc` written above it. */
class ConditionalDefinitionQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testAFunctionUnderAnIfIsDocumented() = assertDocumented("Snocs.") {
        """
        @doc "Snocs."
        def snoc(q, x), do: {q, x}
        """
    }

    fun testAFunctionUnderAnUnlessAndAnElseIsDocumented() {
        myFixture.configureByText(
            "documented.ex",
            """
            defmodule Documented do
              unless Code.ensure_loaded?(Kernel) do
                def snoc(q), do: q
              else
                @doc "Else snocs."
                def snoc(q, x), do: {q, x}
              end
            end

            defmodule Caller do
              def calls(a, b), do: Documented.sn<caret>oc(a, b)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the else branch's docs, got: $documentation", documentation?.contains("Else snocs.") == true)
    }

    private fun assertDocumented(doc: String, definition: () -> String) {
        myFixture.configureByText(
            "documented.ex",
            """
            defmodule Documented do
              if Code.ensure_loaded?(Kernel) do
            ${definition().trimIndent().prependIndent("    ")}
              end
            end

            defmodule Caller do
              def calls(a, b), do: Documented.sn<caret>oc(a, b)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected `$doc`, got: $documentation", documentation?.contains(doc) == true)
    }
}
