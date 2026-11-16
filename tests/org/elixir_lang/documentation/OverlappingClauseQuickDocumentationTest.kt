package org.elixir_lang.documentation

/**
 * A `def` whose defaults cover an arity another `def` of its name already defines. Elixir keys each by its name and
 * full arity, so they are two functions, `snoc/1` and `snoc/2`, each with its own entry.
 */
class OverlappingClauseQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testQuickDocsAtTheLowerArityShowEachFunctionOnItsOwn() =
        assertEachFunctionOnItsOwn(quickDocumentationAtCaret(OVERLAPPING))

    fun testQuickDocsAtTheLowerArityShowEachFunctionOnItsOwnWithTheExpanderOn() = withTheExpanderOn {
        assertEachFunctionOnItsOwn(quickDocumentationAtCaret(OVERLAPPING))
    }

    private fun quickDocumentationAtCaret(text: String): String {
        myFixture.configureByText("overlapping.ex", text)

        return quickDocumentationAtCaret().orEmpty()
    }

    private fun assertEachFunctionOnItsOwn(documentation: String) {
        assertEquals(
            "Expected a link per function, ascending, got: $documentation",
            listOf("1" to "p0", "2" to """q, x \\ nil"""),
            linkedHeads(documentation, "snoc")
        )
        assertTrue("Expected snoc/2's @doc, got: $documentation", "Appends an element." in documentation)
    }

    companion object {
        private val OVERLAPPING = """
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
    }
}
