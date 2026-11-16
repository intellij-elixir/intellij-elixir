package org.elixir_lang.documentation

/** A documentation link naming a module by an atom reaches the module that atom names, however it is written. */
class AtomNamedModuleLinkTest : QuickDocumentationTestCase() {
    fun testQuotedAtomLinkReachesModule() = assertLinkReaches(":\"a.b\"", "defmodule :\"a.b\" do")

    fun testQuotedAtomLinkReachesModuleWrittenUnquoted() =
        assertLinkReaches(":\"plain\"", "defmodule :plain do")

    fun testQuotedAtomFunctionLinkReachesFunction() = assertLinkReaches(":\"a.b\".f/0", "def f, do: :a")

    fun testQuotedAtomFunctionLinkReachesFunctionInModuleWrittenUnquoted() =
        assertLinkReaches(":\"plain\".g/0", "def g, do: :p")

    private fun assertLinkReaches(link: String, expectedTextStart: String) {
        myFixture.configureByText(
            "link.ex",
            """
            defmodule :"a.b" do
              def f, do: :a
            end

            defmodule :plain do
              def g, do: :p
            end
            """.trimIndent()
        )

        val element = ElixirDocumentationProvider().getDocumentationElementForLink(psiManager, link, myFixture.file)

        assertNotNull("$link reached nothing", element)
        assertTrue("$link reached ${element!!.text}", element.text.startsWith(expectedTextStart))
    }
}
