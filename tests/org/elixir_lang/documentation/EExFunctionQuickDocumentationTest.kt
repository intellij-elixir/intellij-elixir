package org.elixir_lang.documentation

import org.elixir_lang.expander.ExpanderSourceRows.withExpanderSwitch

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
        assertTrue("Expected the signature at the list's size, got: $documentation", documentation?.contains("render(assigns, extra)") == true)
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
        assertTrue("Expected the signature, got: $documentation", "render(assigns)" in documentation)
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
        assertTrue("Expected the signature, got: $documentation", "page_text()" in documentation)
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
        assertTrue("Expected the signature, got: $documentation", documentation?.contains("page_template(assigns)") == true)
    }

    private fun configureEExBesideADef(caller: String) {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "beside.ex",
            """
            defmodule Beside do
              require EEx

              @doc "Renders with one."
              EEx.function_from_string(:def, :render, "<%= @a %>", [:assigns])

              @doc "Renders with two."
              def render(assigns, extra), do: {assigns, extra}

              $caller
            end
            """.trimIndent()
        )
    }

    /** An EEx function is one arity of its name, listed beside a `def` of that name at another. */
    fun testACallListsAnEExFunctionBesideADefOfTheSameName() {
        configureEExBesideADef("def page(assigns), do: ren<caret>der(assigns)")

        assertBothArities(quickDocumentationAtCaret().orEmpty())
    }

    /** A call that lands on a `def` lists the EEx function beside it, as one that lands on the EEx function lists the `def`. */
    fun testACallToADefListsAnEExFunctionBesideIt() {
        configureEExBesideADef("def page(assigns), do: ren<caret>der(assigns, assigns)")

        assertBothArities(quickDocumentationAtCaret().orEmpty())
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

    fun testAnSdkEExFunctionShowsItsEntryAtALocalUseWithTheExpanderOn() = assertSdkEExEntry(local = "def calls(x), do: pa<caret>ge(x, x)")

    fun testAnSdkEExFunctionShowsItsEntryAtARemoteUseWithTheExpanderOn() = assertSdkEExEntry("def calls(x), do: Pages.pa<caret>ge(x, x)")

    fun testAnSdkEExFunctionShowsItsEntryAtAnApplyWithTheExpanderOn() = assertSdkEExEntry("def calls(x), do: apply(Pages, :pa<caret>ge, [x, x])")

    fun testAnSdkEExFunctionShowsItsEntryAtAnMfaWithTheExpanderOn() = assertSdkEExEntry("def calls(x), do: {{Pages, :pa<caret>ge, 2}, x}")

    /** Each arity the SDK's `EEx` makes of the name is its own entry, as Elixir lists each. */
    fun testAnSdkEExFunctionListsEachArityOfItsNameWithTheExpanderOn() {
        val documentation = documentationWithTheExpanderOn(
            "pages.ex",
            """
            defmodule Pages do
              require EEx

              @doc "One."
              EEx.function_from_string(:def, :page, "<%= a %>", [:a])

              @doc "Two."
              EEx.function_from_string(:def, :page, "<%= a %><%= b %>", [:a, :b])

              def calls(x), do: pa<caret>ge(x, x)
            end
            """.trimIndent()
        )

        assertTrue("Expected page/1's entry, got: $documentation", "One." in documentation)
        assertTrue("Expected page/2's entry, got: $documentation", "Two." in documentation)
    }

    fun testAnSdkEExFunctionShowsItsEntryAtItsNameWithTheExpanderOn() {
        val documentation = documentationWithTheExpanderOn("pages.ex", sdkEExPages().replace(":page,", ":pa<caret>ge,"))

        assertTrue("Expected the @doc, got: $documentation", "Renders." in documentation)
        assertTrue("Expected the signature, got: $documentation", "page(a, b)" in documentation)
    }

    fun testTheDocAboveAnSdkEExFunctionIsNotTheNextDefinitionsWithTheExpanderOn() {
        val documentation = documentationWithTheExpanderOn(
            "pages.ex",
            sdkEExPages(local = "def other(x), do: x\n\n  def calls(x), do: oth<caret>er(x)")
        )

        assertTrue("Expected other/1's entry, got: $documentation", "other(x)" in documentation)
        assertFalse("Expected no @doc, got: $documentation", "Renders." in documentation)
    }

    /**
     * With the expander, a call of a project's own `EEx.function_from_string` whose macro defines no function declares
     * nothing, so the `@doc` above it is the next definition's.
     */
    fun testTheDocAboveASourceEExMacroThatDefinesNothingIsTheNextDefinitionsWithTheExpanderOn() {
        val documentation = documentationWithTheExpanderOn("render.ex", sourceEExViews().replace("do: other(assigns)", "do: oth<caret>er(assigns)"))

        assertTrue("Expected the @doc on other/1, got: $documentation", "Renders the page." in documentation)
    }

    /** With the expander, the name a project's own `EEx.function_from_string` is given, whose macro defines no function, documents nothing. */
    fun testTheNameASourceEExMacroThatDefinesNothingIsGivenDocumentsNothingWithTheExpanderOn() {
        val documentation = documentationWithTheExpanderOn("render.ex", sourceEExViews().replace(":render,", ":ren<caret>der,"))

        assertFalse("Expected no @doc, got: $documentation", "Renders the page." in documentation)
    }

    /** A user's macro shaped like `EEx.function_from_string` declares what it injects, so the name its call writes is not titled as an EEx function. */
    fun testTheNameAMacroShapedLikeEExWritesIsNotTitledAsAnEExFunctionWithTheExpanderOn() {
        val documentation = documentationWithTheExpanderOn("mine.ex", myGenMine().replace(":mine,", ":mi<caret>ne,"))

        assertFalse("Expected no EEx title, got: $documentation", "mine(a, b)" in documentation)
    }

    /** A user's macro shaped like `EEx.function_from_string` that injects a `def` keeps the `@doc` above its call from the next definition. */
    fun testTheDocAboveAMacroShapedLikeEExThatInjectsADefIsNotTheNextDefinitionsWithTheExpanderOn() {
        val documentation = documentationWithTheExpanderOn("mine.ex", myGenMine().replace("do: other(a)", "do: oth<caret>er(a)"))

        assertTrue("Expected other/1's entry, got: $documentation", "other(a)" in documentation)
        assertFalse("Expected no @doc on other/1, got: $documentation", "Mine." in documentation)
    }

    /** A user's macro shaped like `EEx.function_from_string` is not listed as an EEx function beside the module's other arities. */
    fun testAMacroShapedLikeEExIsNotListedBesideTheOtherAritiesWithTheExpanderOn() {
        val documentation = documentationWithTheExpanderOn(
            "mine.ex",
            myGenMine().replace(
                "def calls(a), do: mine(a)",
                "@doc \"Three.\"\n  def mine(a, b, c), do: {a, b, c}\n\n  def calls(a), do: mi<caret>ne(a, a, a)"
            )
        )

        assertTrue("Expected mine/3's @doc, got: $documentation", "Three." in documentation)
        assertFalse("Expected no entry with the macro call's @doc, got: $documentation", "Mine." in documentation)
    }

    /** Without the expander the SDK's `EEx`, read from its `.beam`, is not known as `EEx`, so the walk declares nothing. */
    fun testAnSdkEExFunctionShowsNothingFromTheWalk() {
        legSdk()
        myFixture.configureByText("pages.ex", sdkEExPages(local = "def calls(x), do: pa<caret>ge(x, x)"))

        assertNull(quickDocumentationAtCaret())
    }

    fun testAnSdkEmbedShowsItsEntryWithTheExpanderOn() {
        expanderOn()
        myFixture.configureByText(
            "embeds.ex",
            """
            defmodule Embeds do
              require Mix.Generator

              @doc "Embedded."
              Mix.Generator.embed_template(:page, "<%= @a %>")

              def calls(x), do: page_temp<caret>late(x)
            end
            """.trimIndent()
        )

        val documentation = withExpanderSwitch { quickDocumentationAtCaret() }.orEmpty()

        assertTrue("Expected the @doc, got: $documentation", "Embedded." in documentation)
        assertTrue("Expected the signature, got: $documentation", "page_template(assigns)" in documentation)
    }

    /** Quick Documentation at the caret in [text], configured as [fileName], with the expander on. */
    private fun documentationWithTheExpanderOn(fileName: String, text: String): String {
        expanderOn()
        myFixture.configureByText(fileName, text)

        return quickDocumentationAtCaret().orEmpty()
    }

    /**
     * With the expander, Quick Documentation at [caller] in another module or [local] in `Pages`, a use of `Pages.page/2` that the SDK's `EEx` makes, shows the
     * function's entry, its `@doc` and Elixir's signature, not `EEx.function_from_string`'s.
     */
    private fun assertSdkEExEntry(caller: String = "", local: String = "") {
        expanderOn()
        myFixture.configureByText("pages.ex", sdkEExPages(caller, local))

        val documentation = withExpanderSwitch { quickDocumentationAtCaret() }.orEmpty()

        assertTrue("Expected the @doc, got: $documentation", "Renders." in documentation)
        assertTrue("Expected the signature, got: $documentation", "page(a, b)" in documentation)
        assertFalse("Expected none of EEx's own doc, got: $documentation", "function_from_string" in documentation)
    }

    /**
     * A project's own `EEx`, whose `function_from_string` expands to `nil`, then `Views`, which calls it under a `@doc`,
     * then defines `other/1` and `page/1`, which calls it.
     */
    private fun sourceEExViews(): String =
        """
        defmodule EEx do
          defmacro function_from_string(_kind, _name, _source, _args \\ [], _options \\ []), do: nil
        end

        defmodule Views do
          require EEx

          @doc "Renders the page."
          EEx.function_from_string(:def, :render, "<%= @a %>", [:assigns])

          def other(assigns), do: assigns

          def page(assigns), do: other(assigns)
        end
        """.trimIndent()

    /** `Mine`, whose `@doc` is above a call of `MyGen`'s macro shaped like `EEx.function_from_string`, which defines `mine/1` with a `def`. */
    private fun myGenMine(): String =
        """
        defmodule MyGen do
          defmacro function_from_string(kind, name, template, args, options) do
            quote do
              def unquote(name)(a), do: {unquote(kind), unquote(template), unquote(args), unquote(options), a}
            end
          end
        end

        defmodule Mine do
          require MyGen

          @doc "Mine."
          MyGen.function_from_string(:def, :mine, "x", [:a, :b], [])

          def other(a), do: a

          def calls(a), do: mine(a)
          def calls_other(a), do: other(a)
        end
        """.trimIndent()

    /** `Pages`, whose `page/2` the SDK's `EEx` makes and whose [local] uses it, then a module whose [caller] uses it. */
    private fun sdkEExPages(caller: String = "", local: String = ""): String =
        """
        defmodule Pages do
          require EEx

          @doc "Renders."
          EEx.function_from_string(:def, :page, "<%= a %><%= b %>", [:a, :b])

          $local
        end

        defmodule Caller do
          $caller
        end
        """.trimIndent()
}
