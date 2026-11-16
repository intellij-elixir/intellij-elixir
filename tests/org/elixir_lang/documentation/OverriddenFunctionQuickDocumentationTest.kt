package org.elixir_lang.documentation

/**
 * A `defoverridable` function overridden by a `def` with no `@doc` keeps the original's: Elixir keys the docs by name
 * and arity, and `defoverridable` takes the definition, not its docs. An override's own `@doc` replaces it.
 */
class OverriddenFunctionQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testAnOverrideWithNoDocKeepsTheOriginalsDoc() =
        assertKeepsTheOriginalsDoc(
            """
            defmodule Overridden do
              @doc "Original."
              def f(x), do: x

              defoverridable f: 1

              def f(y), do: y
            end
            """
        )

    /** `super` keeps the original as a private definition of another name; its docs stay with `f/1`. */
    fun testAnOverrideCallingSuperKeepsTheOriginalsDoc() =
        assertKeepsTheOriginalsDoc(
            """
            defmodule Overridden do
              @doc "Original."
              def f(x), do: x

              defoverridable f: 1

              def f(y), do: super(y)
            end
            """
        )

    /** The walk reads both definitions as one function and shows both docs, so this is the expander's alone. */
    fun testAnOverridesOwnDocReplacesTheOriginalsWithTheExpanderOn() =
        withTheExpanderOn {
            assertShowsInsteadOf(
                """
                defmodule Overridden do
                  @doc "Original."
                  def f(x), do: x

                  defoverridable f: 1

                  @doc "Override."
                  def f(y), do: y
                end
                """,
                "Override.",
                "Original."
            )
        }

    /** A delegation's docs list the other arities of its name, an overridden one with what Elixir keeps of it. */
    fun testADelegationListsAnOverrideWithTheOriginalsDoc() {
        myFixture.addFileToProject("target.ex", "defmodule Target do\n  def f(x), do: x\nend\n")

        assertKeepsTheOriginalsDoc(
            """
            defmodule Overridden do
              @doc "Original."
              def f(x, y), do: {x, y}

              defoverridable f: 2

              def f(x, y), do: {y, x}

              defdelegate f(x), to: Target
            end
            """
        )
    }

    /** `@doc false` is the original's doc too, so an override that writes none stays hidden from another module. */
    fun testAnOverrideWithNoDocOfAHiddenOriginalIsNotListed() {
        val documentation = documentationOf(withF2(""))

        assertFalse("Expected no f/2, got: $documentation", "f(x, y)" in documentation)
    }

    /** The walk reads both definitions as one function, hidden by the original's `@doc false`, so this is the expander's alone. */
    fun testAnOverridesOwnDocListsAHiddenOriginalWithTheExpanderOn() =
        withTheExpanderOn {
            val documentation = documentationOf(withF2("@doc \"Override.\""))

            assertTrue("Expected f/2, got: $documentation", "f(x, y)" in documentation)
        }

    /** Elixir keeps the last doc written across a chain of overrides; the walk reads them all as one function. */
    fun testAChainKeepsTheLastDocWrittenWithTheExpanderOn() =
        withTheExpanderOn {
            assertShowsInsteadOf(
                """
                defmodule Overridden do
                  @doc "Original."
                  def f(x), do: x

                  defoverridable f: 1

                  @doc "Middle."
                  def f(x), do: x + 1

                  defoverridable f: 1

                  def f(x), do: x + 2
                end
                """,
                "Middle.",
                "Original."
            )
        }

    /** A doc written after a `@doc false` replaces it, as `use` over `use GenServer`'s `child_spec/1` writes. */
    fun testAChainListsADocWrittenAfterAHiddenOriginalWithTheExpanderOn() =
        withTheExpanderOn {
            val documentation = documentationOf(
                """
                defmodule Overridden do
                  @doc "Shown."
                  def f(x), do: x

                  @doc false
                  def f(x, y), do: {x, y}

                  defoverridable f: 2

                  @doc "Middle."
                  def f(x, y), do: {y, x}

                  defoverridable f: 2

                  def f(x, y), do: {x, x}
                end
                """
            )

            assertTrue("Expected f/2 with the middle @doc, got: $documentation", "f(x, y)" in documentation && "Middle." in documentation)
        }

    /** Each `@deprecated` replaces the earlier one, as metadata merges over the earlier's. */
    fun testAChainKeepsTheLastDeprecationWrittenWithTheExpanderOn() =
        withTheExpanderOn {
            assertShowsInsteadOf(
                """
                defmodule Overridden do
                  @doc "Original."
                  @deprecated "Use g/1."
                  def f(x), do: x

                  defoverridable f: 1

                  @deprecated "Use h/1."
                  def f(x), do: x + 1

                  defoverridable f: 1

                  def f(x), do: x + 2
                end
                """,
                "Use h/1.",
                "Use g/1."
            )
        }

    /** A `defdelegate` that is the override writes only its target, so the original's doc stays. */
    fun testADelegationOverridingAFunctionKeepsTheOriginalsDoc() {
        myFixture.addFileToProject("target.ex", "defmodule Target do\n  def f(x), do: x\nend\n")

        assertKeepsTheOriginalsDoc(
            """
            defmodule Overridden do
              @doc "Original."
              def f(x), do: x

              defoverridable f: 1

              defdelegate f(x), to: Target
            end
            """
        )
    }

    /** `@doc false` is the override's own doc, so a use of it shows none of the original's, as `use Agent`'s `child_spec/1` would. */
    fun testAHiddenOverrideShowsNoOriginalDocWithTheExpanderOn() =
        withTheExpanderOn {
            assertShowsInsteadOf(
                """
                defmodule Overridden do
                  @doc "Original."
                  def f(x), do: x

                  defoverridable f: 1

                  @doc false
                  def f(y), do: y
                end
                """,
                "def f(y)",
                "Original."
            )
        }

    fun testAHiddenDelegationOverridingAFunctionShowsNoOriginalDocWithTheExpanderOn() =
        withTheExpanderOn {
            myFixture.addFileToProject("target.ex", "defmodule Target do\n  def f(x), do: x\nend\n")

            assertShowsInsteadOf(
                """
                defmodule Overridden do
                  @doc "Original."
                  def f(x), do: x

                  defoverridable f: 1

                  @doc false
                  defdelegate f(x), to: Target
                end
                """,
                "defdelegate f(x)",
                "Original."
            )
        }

    fun testADelegationOverridingAFunctionKeepsTheOriginalsDocWithTheExpanderOn() =
        withTheExpanderOn(::testADelegationOverridingAFunctionKeepsTheOriginalsDoc)

    fun testAnOverrideWithNoDocOfAHiddenOriginalIsNotListedWithTheExpanderOn() =
        withTheExpanderOn(::testAnOverrideWithNoDocOfAHiddenOriginalIsNotListed)

    fun testADelegationListsAnOverrideWithTheOriginalsDocWithTheExpanderOn() =
        withTheExpanderOn(::testADelegationListsAnOverrideWithTheOriginalsDoc)

    fun testAnOverrideWithNoDocKeepsTheOriginalsDocWithTheExpanderOn() =
        withTheExpanderOn(::testAnOverrideWithNoDocKeepsTheOriginalsDoc)

    fun testAnOverrideCallingSuperKeepsTheOriginalsDocWithTheExpanderOn() =
        withTheExpanderOn(::testAnOverrideCallingSuperKeepsTheOriginalsDoc)

    private fun assertKeepsTheOriginalsDoc(module: String) {
        val documentation = documentationOf(module)

        assertTrue("Expected the original's @doc, got: $documentation", "Original." in documentation)
    }

    /** Quick Documentation beside [module] shows [shown], what Elixir keeps, and not [dropped], what it replaced. */
    private fun assertShowsInsteadOf(module: String, shown: String, dropped: String) {
        val documentation = documentationOf(module)

        assertTrue("Expected \"$shown\", got: $documentation", shown in documentation)
        assertFalse("Expected no \"$dropped\", got: $documentation", dropped in documentation)
    }

    /** `Overridden`, with a documented `f/1` and an `f/2` written `@doc false` and overridden by a `def` under [doc]. */
    private fun withF2(doc: String): String =
        """
        defmodule Overridden do
          @doc "Shown."
          def f(x), do: x

          @doc false
          def f(x, y), do: {x, y}

          defoverridable f: 2

          $doc
          def f(x, y), do: {y, x}
        end
        """

    /** Quick Documentation at a call to `Overridden.f/1` from another module, beside [module]. */
    private fun documentationOf(module: String): String =
        quickDocumentationIn(
            "overridden.ex",
            module.trimIndent() + "\n\n" +
                """
                defmodule Caller do
                  def calls(z), do: Overridden.<caret>f(z)
                end
                """.trimIndent()
        )
}
