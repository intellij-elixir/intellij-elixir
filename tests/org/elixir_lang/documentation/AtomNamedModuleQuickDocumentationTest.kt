package org.elixir_lang.documentation

/** Quick Documentation for a definition in a module named with an atom names the module by that atom. */
class AtomNamedModuleQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testModuleHeaderNamesTheAtom() {
        myFixture.configureByText(
            "quick_doc.ex",
            """
            defmodule :"a.b" do
              @doc "Multiplies two numbers."
              def multiply(a, b) do
                a * b
              end
            end

            defmodule Caller do
              def run do
                :"a.b".mul<caret>tiply(2, 3)
              end
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation should be shown for a documented function", documentation)
        assertTrue(
            "Expected the module header to name the atom, got: $documentation",
            documentation!!.contains("<i>module</i> <b>:\"a.b\"</b>")
        )
    }

    fun testModuleHeaderKeepsTheElixirPrefixOfAnAtomThatIsNotAnAlias() {
        myFixture.configureByText(
            "quick_doc.ex",
            """
            defmodule :"Elixir.foo bar" do
              @doc "Multiplies two numbers."
              def multiply(a, b) do
                a * b
              end
            end

            defmodule Caller do
              def run do
                :"Elixir.foo bar".mul<caret>tiply(2, 3)
              end
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation should be shown for a documented function", documentation)
        assertTrue(
            "Expected the module header to name the whole atom, got: $documentation",
            documentation!!.contains("<i>module</i> <b>:\"Elixir.foo bar\"</b>")
        )
    }
}
