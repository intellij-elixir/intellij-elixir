package org.elixir_lang.documentation

/**
 * `@doc nil` writes no doc, where `@doc false` hides the function: Elixir's docs row is `:none`, an override keeps the
 * doc it replaced, and a `defdelegate` is documented by its target (`Module.compile_doc/6`).
 */
class NilDocQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testAnOverrideWrittenDocNilKeepsTheOriginalsDoc() {
        val documentation = quickDocumentationIn(
            "overridden.ex",
            """
            defmodule Overridden do
              @doc "Original."
              def f(x), do: x

              defoverridable f: 1

              @doc nil
              def f(y), do: y
            end

            defmodule Caller do
              def calls(z), do: Overridden.<caret>f(z)
            end
            """.trimIndent()
        )

        assertTrue("Expected the original's @doc, got: $documentation", "Original." in documentation)
    }

    /** Another module's use lists an arity written `@doc nil`, as it lists one written with no `@doc`. */
    fun testAnArityWrittenDocNilIsListed() {
        val documentation = quickDocumentationIn(
            "listed.ex",
            """
            defmodule Listed do
              @doc "Shown."
              def f(x), do: x

              @doc nil
              def f(x, y), do: {x, y}
            end

            defmodule Caller do
              def calls(z), do: Listed.<caret>f(z)
            end
            """.trimIndent()
        )

        assertTrue("Expected f/1, got: $documentation", "Shown." in documentation)
        assertTrue("Expected f/2, got: $documentation", "f(x, y)" in documentation)
    }

    fun testADelegationWrittenDocNilShowsItsTargetsDoc() {
        myFixture.addFileToProject("target.ex", "defmodule Target do\n  @doc \"Target doc.\"\n  def t(x), do: x\nend\n")

        val documentation = quickDocumentationIn(
            "delegator.ex",
            """
            defmodule Delegator do
              @doc nil
              defdelegate t(x), to: Target

              def calls(a), do: <caret>t(a)
            end
            """.trimIndent()
        )

        assertTrue("Expected the target's @doc, got: $documentation", "Target doc." in documentation)
    }

    fun testAnOverrideWrittenDocNilKeepsTheOriginalsDocWithTheExpanderOn() =
        withTheExpanderOn(::testAnOverrideWrittenDocNilKeepsTheOriginalsDoc)

    fun testAnArityWrittenDocNilIsListedWithTheExpanderOn() =
        withTheExpanderOn(::testAnArityWrittenDocNilIsListed)

    fun testADelegationWrittenDocNilShowsItsTargetsDocWithTheExpanderOn() =
        withTheExpanderOn(::testADelegationWrittenDocNilShowsItsTargetsDoc)
}
