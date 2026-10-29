package org.elixir_lang.documentation

import com.intellij.openapi.util.text.StringUtil

/** A link to an EEx function whose arity the plugin cannot know is linked wherever a call of it would resolve. */
class OpaqueArityLinkTest : QuickDocumentationTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    fun testALinkToAnEExFunctionWhoseBindingsAreNotAListIsLinked() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "views.ex",
            """
            defmodule Views do
              require EEx
              @bindings [:a]

              @doc "Renders the page."
              EEx.function_from_string(:def, :page, "<%= a %>", @bindings)
            end

            defmodule Re<caret>ader do
              @moduledoc "See `Views.page/1`."
            end
            """.trimIndent()
        )

        assertEquals(listOf("psi_element://Views.page/1"), hrefs())
        assertTrue(
            myFixture.followedLinkAtCaret(project, "psi_element://Views.page/1")?.contains("Renders the page.") == true
        )
    }

    fun testALinkToAnEExFunctionWhoseBindingsHaveAnOpenTailIsLinked() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "views.ex",
            """
            defmodule Views do
              require EEx
              @rest [:b]

              @doc "Renders a sample."
              EEx.function_from_string(:def, :sample, "<%= a %>", [:a | @rest])
            end

            defmodule Re<caret>ader do
              @moduledoc "See `Views.sample/0`, `Views.sample/1` and `Views.sample/2`."
            end
            """.trimIndent()
        )

        assertEquals(listOf("psi_element://Views.sample/1", "psi_element://Views.sample/2"), hrefs())
        assertTrue(
            myFixture.followedLinkAtCaret(project, "psi_element://Views.sample/2")?.contains("Renders a sample.") == true
        )
    }

    fun testALinkOpensTheDefinitionAtItsArityNotAnEExFunctionOfUnknownArityBeforeIt() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "views.ex",
            """
            defmodule Views do
              require EEx
              @bindings [:a, :b]

              @doc "Renders from a template."
              EEx.function_from_string(:def, :page, "<%= a %>", @bindings)

              @doc "Renders the page."
              def page(x), do: x
            end

            defmodule Re<caret>ader do
              @moduledoc "See `Views.page/1`."
            end
            """.trimIndent()
        )

        val documentation = myFixture.followedLinkAtCaret(project, "psi_element://Views.page/1")

        assertTrue(documentation, documentation?.contains("Renders the page.") == true)
    }

    fun testALinkOpensNoneOfSeveralEExFunctionsOfUnknownArity() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "views.ex",
            """
            defmodule Views do
              require EEx
              @first [:a]
              @second [:a, :b]

              @doc "Renders from a template."
              EEx.function_from_string(:def, :page, "<%= a %>", @first)

              @doc "Renders from another template."
              EEx.function_from_string(:def, :page, "<%= a %>", @second)
            end

            defmodule Re<caret>ader do
              @moduledoc "See `Views.page/1`."
            end
            """.trimIndent()
        )

        assertEquals(listOf("psi_element://Views.page/1"), hrefs())
        assertNull(myFixture.followedLinkAtCaret(project, "psi_element://Views.page/1"))
    }

    private fun hrefs(): List<String> =
        Regex("""href="([^"]*)"""")
            .findAll(quickDocumentationAtCaret().orEmpty())
            .map { StringUtil.unescapeXmlEntities(it.groupValues[1]) }
            .toList()
}
