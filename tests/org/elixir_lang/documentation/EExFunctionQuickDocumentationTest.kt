package org.elixir_lang.documentation

/** An EEx `function_from_*` declares a function, and the `@doc` written above it documents that function. */
class EExFunctionQuickDocumentationTest : QuickDocumentationTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    fun testAnEExFunctionShowsTheDocWrittenAboveIt() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "render.ex",
            """
            defmodule Views do
              require EEx

              @doc "Renders the page."
              EEx.function_from_string(:def, :render, "<%= @a %>", [:assigns])

              def page(assigns), do: ren<caret>der(assigns)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the @doc, got: $documentation", documentation?.contains("Renders the page.") == true)
    }

    /** The `@doc` above an EEx function is that function's, so the definition after it has none. */
    fun testTheDocAboveAnEExFunctionIsNotTheNextDefinitions() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "render.ex",
            """
            defmodule Views do
              require EEx

              @doc "Renders the page."
              EEx.function_from_string(:def, :render, "<%= @a %>", [:assigns])

              def other(assigns), do: assigns

              def page(assigns), do: oth<caret>er(assigns)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertFalse("Expected no @doc, got: $documentation", "Renders the page." in documentation)
    }

    /** An `args` element that is not a literal atom still counts as a parameter, so the function keeps its `@doc`. */
    fun testAnEExFunctionWithANonLiteralArgumentShowsItsDoc() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "render.ex",
            """
            defmodule Views do
              require EEx

              @extra :extra

              @doc "Renders the page."
              EEx.function_from_string(:def, :render, "<%= @a %>", [:assigns, @extra])

              def page(assigns), do: ren<caret>der(assigns, nil)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the @doc, got: $documentation", documentation?.contains("Renders the page.") == true)
        assertTrue("Expected the head at the list's size, got: $documentation", documentation?.contains("render/2") == true)
    }

    fun testAnEExFunctionFromAFileShowsTheDocWrittenAboveIt() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "render.ex",
            """
            defmodule Views do
              require EEx

              @doc "Renders the page from a file."
              EEx.function_from_file(:def, :render, "page.eex", [:assigns])

              def page(assigns), do: ren<caret>der(assigns)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the @doc, got: $documentation", "Renders the page from a file." in documentation)
        assertTrue("Expected the head, got: $documentation", "render/1" in documentation)
    }

    /** `embed_text(:page, ...)` defines `page_text/0`. */
    fun testAnEmbeddedTextShowsTheDocWrittenAboveItWithNoArgument() {
        myFixture.copyFileToProject("mix_generator.ex")
        myFixture.configureByText(
            "page.ex",
            """
            defmodule Views do
              require Mix.Generator

              @doc "Embeds the text."
              Mix.Generator.embed_text(:page, "text")

              def show, do: page_te<caret>xt()
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the @doc, got: $documentation", "Embeds the text." in documentation)
        assertTrue("Expected the head, got: $documentation", "page_text/0" in documentation)
    }

    /** The arity comes from a literal list; a list built elsewhere has no known arity, so the head claims none. */
    fun testAnEExFunctionWhoseArgumentsAreNotAListShowsNoArity() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "render.ex",
            """
            defmodule Views do
              require EEx

              @args [:assigns]

              @doc "Renders the page."
              EEx.function_from_string(:def, :render, "<%= @a %>", @args)

              def page(assigns), do: ren<caret>der(assigns)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the @doc, got: $documentation", "Renders the page." in documentation)
        assertFalse("Expected no arity, got: $documentation", "render/" in documentation)
        assertFalse("Expected no empty parameter list, got: $documentation", "render()" in documentation)
        assertTrue("Expected the name, got: $documentation", "render" in documentation)
    }

    /** `embed_template(:page, ...)` defines `page_template/0` and `page_template/1`. */
    fun testAnEmbeddedTemplateShowsTheDocWrittenAboveIt() {
        myFixture.copyFileToProject("mix_generator.ex")
        myFixture.configureByText(
            "page.ex",
            """
            defmodule Views do
              require Mix.Generator

              @doc "Embeds the page."
              Mix.Generator.embed_template(:page, "<%= @a %>")

              def show(assigns), do: page_temp<caret>late(assigns)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the @doc, got: $documentation", documentation?.contains("Embeds the page.") == true)
        assertTrue("Expected the head, got: $documentation", documentation?.contains("page_template/0..1") == true)
    }

    /** A function's docs are its own clauses': a macro call of the same shape beside it, with its own `@doc`, is not one. */
    fun testAFunctionsDocIsNotMergedWithASameShapedMacroCalls() {
        myFixture.configureByText(
            "cached.ex",
            """
            defmodule Cached do
              @doc "The function's."
              def snoc(q), do: q

              @doc "The macro's."
              defcached snoc(q)

              def calls(q), do: sn<caret>oc(q)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the function's @doc, got: $documentation", "The function&#39;s." in documentation || "The function's." in documentation)
        assertFalse("Expected no macro @doc, got: $documentation", "The macro" in documentation)
    }
}
