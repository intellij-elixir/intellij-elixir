package org.elixir_lang.documentation

import org.elixir_lang.ElixirFileType

/**
 * Following a `c:Module.name/arity` link in rendered documentation shows the callback's `@doc`, for
 * `@macrocallback` as well as `@callback`.
 */
class CallbackQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testCallbackLinkShowsAtDoc() {
        assertLinkShowsAtDoc("callback")
    }

    fun testMacroCallbackLinkShowsAtDoc() {
        assertLinkShowsAtDoc("macrocallback")
    }

    /** A macro that builds its `@callback` with no `quote` writes no `@callback` call, so the expander places it at the macro. */
    fun testACallbackLinkOpensACallbackAMacroBuildsWithTheExpanderOn() = withTheExpanderOn {
        myFixture.configureByText(
            ElixirFileType.INSTANCE,
            """
            defmodule Behaviour do
              defmacro __using__(_) do
                {:@, [], [{:callback, [], [{:"::", [], [{:built, [], [{:integer, [], []}]}, {:term, [], []}]}]}]}
              end
            end

            defmodule Basics do
              use Behaviour
            end
            """.trimIndent()
        )

        val element = ElixirDocumentationProvider().getDocumentationElementForLink(psiManager, "c:Basics.built/1", myFixture.file)

        assertEquals(
            "c:Basics.built/1 should open the callback `use Behaviour` injects, at the macro that builds it",
            "defmacro __using__(_)",
            element?.text?.substringBefore(" do")
        )
    }

    private fun assertLinkShowsAtDoc(attributeName: String) {
        myFixture.configureByText(
            ElixirFileType.INSTANCE,
            """
            defmodule Behaviour do
              @doc "Handles one message."
              @$attributeName handle(term()) :: :ok
            end
            """.trimIndent()
        )

        val provider = ElixirDocumentationProvider()
        val element = provider.getDocumentationElementForLink(psiManager, "c:Behaviour.handle/1", myFixture.file)

        assertNotNull("c:Behaviour.handle/1 should resolve to the @$attributeName", element)

        val documentation = provider.generateDoc(element!!, null)

        assertNotNull("Documentation should be shown for a documented @$attributeName", documentation)
        assertTrue(
            "Expected the @doc body in the documentation, got: $documentation",
            documentation!!.contains("Handles one message.")
        )
    }
}
