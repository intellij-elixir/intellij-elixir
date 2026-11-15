package org.elixir_lang.documentation

import org.elixir_lang.expander.ExpanderSourceRows.withExpanderSwitch

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

    fun testACallbackLinkOpensTheCallbackAtAnotherArityBesideAFunctionAtTheLinkedArity() = assertCallbackBesideAFunction()

    fun testACallbackLinkOpensTheCallbackAtAnotherArityBesideAFunctionAtTheLinkedArityWithTheExpanderOn() {
        expanderOn()
        withExpanderSwitch { assertCallbackBesideAFunction() }
    }

    /** A `c:` link names only a callback, so a `def` at the linked arity leaves it opening the callback at another. */
    private fun assertCallbackBesideAFunction() {
        myFixture.configureByText(
            "docs.ex",
            """
            defmodule Do<caret>cs do
              @moduledoc "Documented."

              @doc "Two arguments."
              @callback foo(integer, integer) :: term

              @doc "One argument."
              def foo(a), do: a
            end
            """.trimIndent()
        )

        val opened = myFixture.followedLinkAtCaret(project, "psi_element://c:Docs.foo/1").orEmpty()

        assertTrue("Expected the callback's doc, got: $opened", "Two arguments." in opened)
        assertFalse("Expected no def's doc, got: $opened", "One argument." in opened)
    }

    fun testACallbackLinkToANameAtAnotherArityOpensItNotALongerName() = assertCallbackAtAnotherArity()

    fun testACallbackLinkToANameAtAnotherArityOpensItNotALongerNameWithTheExpanderOn() {
        expanderOn()
        withExpanderSwitch { assertCallbackAtAnotherArity() }
    }

    private fun assertCallbackAtAnotherArity() {
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
