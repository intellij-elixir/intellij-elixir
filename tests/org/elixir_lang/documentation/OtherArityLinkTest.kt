package org.elixir_lang.documentation

/** A link to a name at another arity opens that name, not a longer name that begins with it. */
class OtherArityLinkTest : QuickDocumentationTestCase() {
    fun testALinkToANameAtAnotherArityOpensItNotALongerName() {
        myFixture.configureByText(
            "docs.ex",
            """
            defmodule Do<caret>cs do
              @moduledoc "Documented."

              @doc "Longer name."
              def foo_bar(x), do: x

              @doc "Two arguments."
              def foo(a, b), do: {a, b}
            end
            """.trimIndent()
        )

        val opened = myFixture.followedLinkAtCaret(project, "psi_element://Docs.foo/1")

        assertTrue("Expected foo/2's doc, got: $opened", opened?.contains("Two arguments.") == true)
    }

    fun testALinkToANameAtAnotherArityOpensItWhateverTheWrittenOrder() {
        myFixture.configureByText(
            "docs.ex",
            """
            defmodule Do<caret>cs do
              @moduledoc "Documented."

              @doc "Two arguments."
              def foo(a, b), do: {a, b}

              @doc "Longer name."
              def foo_bar(x), do: x
            end
            """.trimIndent()
        )

        val opened = myFixture.followedLinkAtCaret(project, "psi_element://Docs.foo/1")

        assertTrue("Expected foo/2's doc, got: $opened", opened?.contains("Two arguments.") == true)
    }

    fun testACallbackLinkToANameAtAnotherArityOpensItNotALongerName() {
        myFixture.configureByText(
            "docs.ex",
            """
            defmodule Do<caret>cs do
              @moduledoc "Documented."

              @doc "Longer name."
              @callback foo_bar(integer) :: term

              @doc "Two arguments."
              @callback foo(integer, integer) :: term
            end
            """.trimIndent()
        )

        val opened = myFixture.followedLinkAtCaret(project, "psi_element://c:Docs.foo/1")

        assertTrue("Expected foo/2's doc, got: $opened", opened?.contains("Two arguments.") == true)
    }
}
