package org.elixir_lang.documentation

/** A call or MFA atom at an arity the function does not take documents the function it is a near miss for. */
class WrongArityQuickDocumentationTest : QuickDocumentationTestCase() {
    private fun stack() {
        myFixture.addFileToProject(
            "stack.ex",
            """
            defmodule Stack do
              @doc "Pushes one."
              def push(a), do: a

              @doc "Pushes two."
              def push(a, b), do: {a, b}

              defp hidden(a), do: a

              def shown, do: hidden(1)
            end
            """.trimIndent()
        )
    }

    private fun documentationAt(caller: String): String {
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  def run(a), do: $caller\nend\n")

        return quickDocumentationAtCaret().orEmpty()
    }

    fun testAQualifiedCallAtTheWrongArityDocumentsEveryArityOfTheName() {
        stack()

        val documentation = documentationAt("Stack.pu<caret>sh(a, a, a, a)")

        assertTrue("Expected both arities, got: $documentation", "Pushes one." in documentation && "Pushes two." in documentation)
    }

    fun testALocalCallAtTheWrongArityDocumentsTheFunction() {
        myFixture.configureByText(
            "local.ex",
            "defmodule Local do\n  @doc \"Pushes.\"\n  def push(a), do: a\n\n  def run(a), do: pu<caret>sh(a, a, a)\nend\n"
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the function, got: $documentation", "Pushes." in documentation)
    }

    /** A private function is no near miss for a call from outside its module. */
    fun testAPrivateFunctionIsNoNearMissFromAnotherModule() {
        stack()

        val documentation = documentationAt("Stack.hid<caret>den(a, a)")

        assertEquals("", documentation)
    }

    /** A near-miss delegation is documented as a delegation is: through what it delegates to. */
    fun testANearMissDelegationDocumentsWhatItDelegatesTo() {
        myFixture.addFileToProject(
            "target.ex",
            "defmodule Delegating.Target do\n  @doc \"The targets snoc.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "delegating.ex",
            "defmodule Delegating do\n  defdelegate snoc(q, x), to: Delegating.Target\nend\n"
        )

        val documentation = documentationAt("Delegating.sn<caret>oc(a)")

        assertTrue("Expected the target's docs, got: $documentation", "The targets snoc." in documentation)
        assertTrue("Expected a link to the target, got: $documentation", "psi_element://Delegating.Target.snoc/2" in documentation)
    }

    /** An `import only:` of one arity of a delegation with defaults leaves the other a near miss, documented as the delegation is. */
    fun testAnArityAnImportLeavesOutIsANearMissOfADelegationWithDefaults() {
        myFixture.addFileToProject(
            "target.ex",
            "defmodule Imported.Target do\n  @doc \"The targets snoc.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "imported.ex",
            "defmodule Imported do\n  defdelegate snoc(q, x \\\\ nil), to: Imported.Target\nend\n"
        )
        myFixture.configureByText(
            "caller.ex",
            "defmodule Caller do\n  import Imported, only: [snoc: 2]\n\n  def run(a), do: sn<caret>oc(a)\nend\n"
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the target's docs, got: $documentation", "The targets snoc." in documentation)
        assertTrue("Expected a link to the target, got: $documentation", "psi_element://Imported.Target.snoc/2" in documentation)
    }

    fun testAWrongArityMfaAtomDocumentsTheExportedFunction() {
        stack()

        val documentation = documentationAt("apply(Stack, :pu<caret>sh, [a, a, a, a])")

        assertTrue("Expected the function, got: $documentation", "Pushes one." in documentation && "Pushes two." in documentation)
    }

    /** A macro of the called arity is no function to call, so the function of the name at another arity is the near miss. */
    fun testAnMfaAtomAtTheArityOfAMacroDocumentsTheFunctionOfTheName() {
        myFixture.addFileToProject(
            "mixed.ex",
            "defmodule Mixed do\n  defmacro f(a), do: a\n\n  @doc \"Function two.\"\n  def f(a, b), do: {a, b}\nend\n"
        )

        val documentation = documentationAt("apply(Mixed, :<caret>f, [a])")

        assertTrue("Expected the function, got: $documentation", "Function two." in documentation)
    }

    /** An MFA atom calls from outside the module, so a private function of that name is no near miss. */
    fun testAnMfaAtomNamingOnlyAPrivateFunctionDocumentsNothing() {
        stack()

        val documentation = documentationAt("apply(Stack, :hid<caret>den, [a, a])")

        assertEquals("", documentation)
    }
}
