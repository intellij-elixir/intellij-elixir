package org.elixir_lang.documentation

/** A followed function link opens what a call of the name documents. */
class LinkTargetQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testALinkToAnOverriddenExceptionMessageOpensTheUsersDef() = assertOverriddenMessage()

    fun testALinkToAnOverriddenExceptionMessageOpensTheUsersDefWithTheExpanderOn() = withTheExpanderOn(::assertOverriddenMessage)

    fun testALinkToAnUndocumentedDelegationDocumentsItsTarget() = assertDelegationTarget()

    fun testALinkToAnUndocumentedDelegationDocumentsItsTargetWithTheExpanderOn() = withTheExpanderOn(::assertDelegationTarget)

    fun testALinkThroughAChainOfUndocumentedDelegationsDocumentsTheLastTarget() = assertChainTarget()

    fun testALinkThroughAChainOfUndocumentedDelegationsDocumentsTheLastTargetWithTheExpanderOn() = withTheExpanderOn(::assertChainTarget)

    fun testALinkToADelegationWithDefaultsOpensTheTargetsEntryForTheArityItPasses() = assertDefaultsArity()

    fun testALinkToADelegationWithDefaultsOpensTheTargetsEntryForTheArityItPassesWithTheExpanderOn() =
        withTheExpanderOn(::assertDefaultsArity)

    fun testALinkInACompiledModulesDocsToAnotherCompiledModuleOpensItsFunction() = assertCompiledToCompiled()

    fun testALinkInACompiledModulesDocsToAnotherCompiledModuleOpensItsFunctionWithTheExpanderOn() =
        withTheExpanderOn(::assertCompiledToCompiled)

    /** `defexception [:message]` defines `message/1` as overridable, so the user's `def message/1` is the one a link opens. */
    private fun assertOverriddenMessage() {
        myFixture.configureByText(
            "my_error.ex",
            """
            defmodule My<caret>Error do
              @moduledoc "Documented."

              defexception [:message]

              @impl true
              @doc "Custom message."
              def message(exception), do: "custom: " <> exception.message
            end
            """.trimIndent()
        )

        val opened = myFixture.followedLinkAtCaret(project, "psi_element://MyError.message/1").orEmpty()

        assertTrue("Expected the user's def, got: $opened", "Custom message." in opened)
    }

    /** A delegation with no `@doc` of its own documents what it delegates to, as a call of it does. */
    private fun assertDelegationTarget() {
        myFixture.configureByText(
            "delegator.ex",
            """
            defmodule Target do
              @doc "Runs the target."
              def run(x), do: x
            end

            defmodule Dele<caret>gator do
              @moduledoc "Documented."

              defdelegate run(x), to: Target
            end
            """.trimIndent()
        )

        val opened = myFixture.followedLinkAtCaret(project, "psi_element://Delegator.run/1").orEmpty()

        assertTrue("Expected the target's doc, got: $opened", "Runs the target." in opened)
    }

    /** Past each delegation with no `@doc`, the link documents the definition at the end of the chain, as a call does. */
    private fun assertChainTarget() {
        myFixture.configureByText(
            "chain.ex",
            """
            defmodule Chain.End do
              @doc "End."
              def f(x), do: x
            end

            defmodule Chain.Middle do
              defdelegate f(x), to: Chain.End
            end

            defmodule Ch<caret>ain do
              @moduledoc "Documented."

              defdelegate f(x), to: Chain.Middle
            end
            """.trimIndent()
        )

        val opened = myFixture.followedLinkAtCaret(project, "psi_element://Chain.f/1").orEmpty()

        assertTrue("Expected the last target's doc, got: $opened", "End." in opened)
    }

    /** A delegation's default fills in its argument, so the link opens at the target's entry for the arity passed on. */
    private fun assertDefaultsArity() {
        myFixture.addFileToProject(
            "target_opens.ex",
            "defmodule Opens.Target do\n  @doc \"One.\"\n  def snoc(q), do: q\n\n  @doc \"Two.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.configureByText(
            "delegator_opens.ex",
            "defmodule Op<caret>ens do\n  @moduledoc \"Documented.\"\n\n  defdelegate snoc(q, x \\\\ nil), to: Opens.Target\nend\n"
        )

        val opened = myFixture.followedLinkAtCaret(project, "psi_element://Opens.snoc/1").orEmpty()
        val anchor = myFixture.followedAnchorAtCaret(project, "psi_element://Opens.snoc/1")

        assertNotNull("Expected an anchor, got: $opened", anchor)
        assertTrue(
            "Expected the anchor ($anchor) on the target's entry for snoc/2, got: $opened",
            opened.substringAfter("""id="$anchor"""").substringBefore("id=\"").contains("Two.")
        )
    }

    /** A link in one `.beam`'s docs to a function of another `.beam`'s module opens that function. */
    private fun assertCompiledToCompiled() {
        myFixture.addCompiledLibrary(
            """
            defmodule Compiled do
              @doc "See `LinkedTarget.f/1`."
              def g(a), do: a

              @doc "Target's f."
              def f(a), do: a
            end
            """.trimIndent(),
            CompiledBuild("LinkedFrom"),
            CompiledBuild("LinkedTarget")
        )
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  def calls(a), do: LinkedFrom.<caret>g(a)\nend\n")

        val opened = myFixture.followedLinkAtCaret(project, "psi_element://LinkedTarget.f/1").orEmpty()

        assertTrue("Expected LinkedTarget.f/1's doc, got: $opened", "Target's f." in opened)
        assertTrue("Expected LinkedTarget as the defining module, got: $opened", "<b>LinkedTarget</b>" in opened)
    }
}
