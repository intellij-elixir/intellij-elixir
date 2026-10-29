package org.elixir_lang.documentation

/** A link that names nothing opens nothing. */
class UnresolvedLinkQuickDocumentationTest : QuickDocumentationTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.configureByText(
            "docs.ex",
            """
            defmodule Do<caret>cs do
              @moduledoc "Documented."
            end
            """.trimIndent()
        )
    }

    fun testAFunctionThatDoesNotExist() {
        assertNull(myFixture.followedLinkAtCaret(project, "psi_element://Docs.missing/9"))
    }

    fun testALinkToAMissingNameOpensNoLongerName() {
        myFixture.configureByText(
            "docs_longer.ex",
            """
            defmodule Do<caret>cs do
              @moduledoc "Documented."

              @doc "Longer."
              def missing_thing(a), do: a
            end
            """.trimIndent()
        )

        assertNull(myFixture.followedLinkAtCaret(project, "psi_element://Docs.missing/1"))
    }

    fun testAnArityTooBigForAnInteger() {
        assertNull(myFixture.followedLinkAtCaret(project, "psi_element://Docs.missing/99999999999"))
    }

    fun testAModuleThatDoesNotExist() {
        assertNull(myFixture.followedLinkAtCaret(project, "psi_element://Missing"))
    }

    fun testALinkThatIsNotAPsiElement() {
        assertNull(myFixture.followedLinkAtCaret(project, "https://elixir-lang.org"))
    }

    fun testALinkWithAnAnchorStillOpensTheModule() {
        assertTrue(myFixture.followedLinkAtCaret(project, "psi_element://Docs###start")?.contains("Documented.") == true)
    }
}
