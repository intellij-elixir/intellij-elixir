package org.elixir_lang.documentation

import com.intellij.openapi.application.ReadAction
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.testFramework.PlatformTestUtil

/** Quick Documentation at the name a declaration writes shows that declaration's documentation. */
class DeclarationQuickDocumentationTest : QuickDocumentationTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    fun testADefHeadShowsItsDoc() {
        assertDocumented(
            "Pushes.",
            """
            defmodule Stack do
              @doc "Pushes."
              def pu<caret>sh(a), do: a
            end
            """.trimIndent()
        )
    }

    /** Ctrl+Q passes the caret's offset as it is, so a caret just after the name lands on the next leaf. */
    fun testADefHeadShowsItsDocWithTheCaretAfterItsName() {
        assertDocumented(
            "Pushes.",
            """
            defmodule Stack do
              @doc "Pushes."
              def push<caret>(a), do: a
            end
            """.trimIndent()
        )
    }

    fun testADefpHeadShowsItsDoc() {
        assertDocumented(
            "Pushes privately.",
            """
            defmodule Stack do
              @doc "Pushes privately."
              defp pu<caret>sh(a), do: a
            end
            """.trimIndent()
        )
    }

    fun testADefmacroHeadShowsItsDoc() {
        assertDocumented(
            "Pushes at compile time.",
            """
            defmodule Stack do
              @doc "Pushes at compile time."
              defmacro pu<caret>sh(a), do: a
            end
            """.trimIndent()
        )
    }

    fun testADefguardHeadShowsItsDoc() {
        assertDocumented(
            "Is the number big.",
            """
            defmodule Sizes do
              @doc "Is the number big."
              defguard is_<caret>big(n) when n > 10
            end
            """.trimIndent()
        )
    }

    fun testAHeadWithAGuardShowsItsDoc() {
        assertDocumented(
            "Pushes a number.",
            """
            defmodule Stack do
              @doc "Pushes a number."
              def pu<caret>sh(a) when is_number(a), do: a
            end
            """.trimIndent()
        )
    }

    fun testAnEExFunctionNameShowsTheDocAboveIt() {
        myFixture.copyFileToProject("eex.ex")

        assertDocumented(
            "Renders the page.",
            """
            defmodule Views do
              require EEx

              @doc "Renders the page."
              EEx.function_from_string(:def, :ren<caret>der, "<%= @a %>", [:assigns])
            end
            """.trimIndent()
        )
    }

    fun testAnEmbeddedTemplateNameShowsTheDocAboveIt() {
        myFixture.copyFileToProject("mix_generator.ex")

        assertDocumented(
            "Embeds the page.",
            """
            defmodule Views do
              require Mix.Generator

              @doc "Embeds the page."
              Mix.Generator.embed_template(:pa<caret>ge, "<%= @a %>")
            end
            """.trimIndent()
        )
    }

    /** An EEx function is one more arity of its name: its name and a `def` of that name beside it each list both. */
    fun testAnEExFunctionNameListsADefBesideIt() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText("eex_beside.ex", eexBesideADef(eexName = "ren<caret>der", defName = "render"))

        assertBothArities(quickDocumentationAtCaret().orEmpty())
    }

    fun testADefNameListsAnEExFunctionBesideIt() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText("def_beside.ex", eexBesideADef(eexName = "render", defName = "ren<caret>der"))

        assertBothArities(quickDocumentationAtCaret().orEmpty())
    }

    private fun eexBesideADef(eexName: String, defName: String): String =
        """
        defmodule Beside do
          require EEx

          @doc "Renders with one."
          EEx.function_from_string(:def, :$eexName, "<%= @a %>", [:assigns])

          @doc "Renders with two."
          def $defName(assigns, extra), do: {assigns, extra}
        end
        """.trimIndent()

    /** A name in a call only shaped like EEx's is not a declaration, so it documents what the platform's chain finds. */
    fun testACallOnlyShapedLikeEExDocumentsNoDeclaration() {
        myFixture.configureByText(
            "other.ex",
            """
            defmodule Views do
              @doc "Not a function's."
              Other.function_from_string(:def, :sam<caret>ple, "")
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertFalse("Expected no declaration's doc, got: $documentation", "Not a function&#39;s." in documentation || "Not a function's." in documentation)
    }

    /** A call shaped like EEx's that is not EEx's macro, here a function of a module named `EEx`, declares nothing. */
    fun testACallShapedLikeEExThatIsNotItsMacroDocumentsNoDeclaration() {
        myFixture.configureByText(
            "shaped.ex",
            """
            defmodule EEx do
              def function_from_string(kind, name, source), do: {kind, name, source}
            end

            defmodule Views do
              @doc "Not a function's."
              EEx.function_from_string(:def, :sam<caret>ple, "")
            end
            """.trimIndent()
        )

        val targets = ReadAction.compute<List<DocumentationTarget>, RuntimeException> {
            ElixirDeclarationDocumentationTargetProvider().documentationTargets(myFixture.file, myFixture.caretOffset)
        }

        assertEquals("Expected no declaration's target at a call that is not EEx's macro", emptyList<DocumentationTarget>(), targets)
    }

    /** The keyword and a head's parameters are not the declared name, so they keep what the platform's chain finds. */
    fun testTheKeywordAndAParameterAreNotTheDeclaredName() {
        for (caret in listOf("de<caret>f push(a), do: a", "def push(<caret>a), do: a")) {
            myFixture.configureByText(
                "stack.ex",
                "defmodule Stack do\n  @doc \"Pushes.\"\n  $caret\nend\n"
            )

            val documentation = quickDocumentationAtCaret().orEmpty()

            assertFalse("Expected not the function's doc at `$caret`, got: $documentation", "Pushes." in documentation)
        }
    }

    fun testEveryArityIsShownAtAHeadAndOpensAtItsArity() {
        myFixture.configureByText(
            "arities.ex",
            """
            defmodule Arities do
              @doc "One."
              def push(a), do: a

              @doc "Two."
              def pu<caret>sh(a, b), do: {a, b}

              @doc "Three."
              def push(a, b, c), do: {a, b, c}
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()
        val anchor = myFixture.documentationAnchorAtCaret(project)

        assertTrue("Expected every arity, got: $documentation", listOf("One.", "Two.", "Three.").all { it in documentation })
        assertNotNull("Expected an anchor", anchor)
        assertTrue(
            "Expected the anchor ($anchor) on the entry for push/2, got: $documentation",
            documentation.substringAfter("""id="$anchor"""").substringBefore("id=\"").contains("Two.")
        )
    }

    /** A `defdelegate` head is documented by its target, which is another module, so the target's private arities are not listed. */
    fun testADelegationHeadListsNoPrivateOrHiddenArityOfItsTarget() {
        myFixture.addFileToProject(
            "other.ex",
            """
            defmodule Other do
              @doc "One."
              def push(a), do: a

              @doc false
              def push(a, b), do: {a, b}

              defp push(a, b, c), do: {a, b, c}

              @doc "Four."
              def push(a, b, c, d), do: push(a, b, c) && d
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "delegator.ex",
            """
            defmodule Delegator do
              defdelegate pu<caret>sh(a), to: Other
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the visible arities, got: $documentation", "One." in documentation && "Four." in documentation)
        assertFalse("Expected no entry for push(a, b, c), got: $documentation", "push(a, b, c)" in documentation)
        assertFalse("Expected no entry for push(a, b), got: $documentation", "push(a, b)" in documentation)
    }

    /** A head in a `quote` is in the module that uses it, which is unknown here, and never in a module named for the `quote`. */
    fun testAHeadInsideAQuoteNamesNoModule() {
        myFixture.configureByText(
            "injector.ex",
            """
            defmodule Injector do
              defmacro __using__(_) do
                quote do
                  @doc "Injected."
                  def sn<caret>oc(q), do: q
                end
              end
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the doc, got: $documentation", "Injected." in documentation)
        assertFalse("Expected no module named for the quote, got: $documentation", "quote" in documentation || "<i>module</i>" in documentation)
    }

    /** The card names a function as Elixir does: an identifier typed with a combining mark reads as its composed spelling. */
    fun testAHeadWrittenWithACombiningMarkShowsTheNameElixirGivesIt() {
        myFixture.configureByText(
            "marked.ex",
            "defmodule Marked do\n  @doc \"Marked.\"\n  def sn<caret>oc\u0301(q, x), do: {q, x}\nend\n"
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the composed name, got: $documentation", "snoć(q, x)" in documentation)
    }

    /** A function named by a quoted atom shows the atom, not the `unquote` that spells it. */
    fun testAHeadWrittenAsAnUnquotedAtomShowsTheAtom() {
        myFixture.configureByText(
            "unquoted.ex",
            "defmodule Unquoted do\n  @doc \"Unquoted.\"\n  def unq<caret>uote(:\"snoc_x\")(q, x), do: {q, x}\nend\n"
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the atom's name, got: $documentation", "snoc_x(q, x)" in documentation)
        assertFalse("Expected no unquote, got: $documentation", "unquote" in documentation)
    }

    private fun assertDocumented(doc: String, text: String) {
        myFixture.configureByText("declaration.ex", text)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected \"$doc\", got: $documentation", documentation?.contains(doc) == true)
    }
}
