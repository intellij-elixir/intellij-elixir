package org.elixir_lang.documentation

/** A call whose function name is computed names no function, so it documents none. */
class UnknownNameQuickDocumentationTest : QuickDocumentationTestCase() {
    private fun inQuote(call: String): String {
        myFixture.addFileToProject(
            "m.ex",
            "defmodule M do\n  @doc \"First.\"\n  def first(a), do: a\n\n  @doc \"Second.\"\n  def second(a), do: a\nend\n"
        )
        myFixture.configureByText(
            "quoted.ex",
            "defmodule Quoted do\n  defmacro build(name) do\n    quote do\n      $call\n    end\n  end\nend\n"
        )

        return quickDocumentationAtCaret().orEmpty()
    }

    fun testAQualifiedCallOfAComputedNameDocumentsNothing() {
        assertEquals("", inQuote("M.unq<caret>uote(name)(1)"))
    }

    /** A known name in the same `quote` still documents its function. */
    fun testAQualifiedCallOfAKnownNameStillDocumentsItsFunction() {
        val documentation = inQuote("M.fir<caret>st(1)")

        assertTrue("Expected first's docs, got: $documentation", "First." in documentation)
    }
}
