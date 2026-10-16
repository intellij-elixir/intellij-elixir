package org.elixir_lang.documentation

/**
 * Quick Documentation on the function atom of `apply/3` names a `defdelegate`d function and documents what the
 * delegation forwards to, as it does at a direct call.
 */
class DelegatedApplyQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testQuickDocAtApplyOnADelegatedFunctionDocumentsTheTarget() {
        myFixture.addFileToProject(
            "target.ex",
            "defmodule Delegated.Target do\n  @doc \"The targets snoc.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "delegator.ex",
            "defmodule Delegated do\n  defdelegate snoc(q, x), to: Delegated.Target\nend\n"
        )
        myFixture.configureByText(
            "caller.ex",
            "defmodule Caller do\n  def at_apply(a, b), do: apply(Delegated, :sn<caret>oc, [a, b])\nend\n"
        )

        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation at apply showed nothing", documentation)
        assertTrue("Expected snoc's head, got: $documentation", documentation!!.contains("snoc(q, x)"))
        assertTrue("Expected the target's docs, got: $documentation", documentation.contains("The targets snoc."))
    }

    /** An undocumented delegation shows its head and links its target at `apply/3`, as at a call. */
    fun testAnUndocumentedDelegationLinksItsTargetAtApply() {
        myFixture.addFileToProject(
            "target.ex",
            "defmodule Delegated.Target do\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "delegator.ex",
            "defmodule Delegated do\n  defdelegate snoc(q, x), to: Delegated.Target\nend\n"
        )
        myFixture.configureByText(
            "caller.ex",
            "defmodule Caller do\n  def at_apply(a, b), do: apply(Delegated, :sn<caret>oc, [a, b])\nend\n"
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected a link to the target, got: $documentation", "psi_element://Delegated.Target.snoc/2" in documentation)
    }

    /** A delegation's own `@doc` says what the function means there, at `apply/3` as at a call. */
    fun testADelegationsOwnDocShowsAtApplyAsAtACall() {
        myFixture.addFileToProject(
            "target.ex",
            "defmodule Delegated.Target do\n  @doc \"The targets snoc.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "delegator.ex",
            "defmodule Delegated do\n  @doc \"The delegations snoc.\"\n  defdelegate snoc(q, x), to: Delegated.Target\nend\n"
        )
        val caller = """
            defmodule Caller do
              def at_apply(a, b), do: apply(Delegated, :snoc, [a, b])
              def at_call(a, b), do: Delegated.snoc(a, b)
            end
        """.trimIndent()

        val shown = listOf("apply(Delegated, :sn<caret>oc", "Delegated.sn<caret>oc(a, b)").map { caretAt ->
            myFixture.configureByText("caller.ex", caller.replace(caretAt.replace("<caret>", ""), caretAt))

            quickDocumentationAtCaret()?.let { documentation ->
                listOf("The delegations snoc.", "The targets snoc.").filter { it in documentation }
            }
        }

        assertEquals(listOf(listOf("The delegations snoc."), listOf("The delegations snoc.")), shown)
    }

    /** An atom naming a module, not a function, still documents the module. */
    fun testQuickDocAtAModuleAtomDocumentsTheModule() {
        myFixture.addFileToProject(
            "documented.ex",
            "defmodule :documented do\n  @moduledoc \"The documented module.\"\n\n  def f, do: :ok\nend\n"
        )
        myFixture.configureByText(
            "caller.ex",
            "defmodule Caller do\n  def at_apply, do: apply(:docu<caret>mented, :f, [])\nend\n"
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the module's @moduledoc, got: $documentation", documentation?.contains("The documented module.") == true)
    }

    /** `apply/3` with arguments of unknown count names the function at any arity, so every arity shows. */
    fun testANonLiteralMfaDocumentsEveryArity() {
        stack()
        myFixture.configureByText(
            "caller.ex",
            "defmodule Caller do\n  def at_apply(args), do: apply(Stack, :pu<caret>sh, args)\nend\n"
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected every arity, got: $documentation", "One." in documentation && "Two." in documentation)
    }

    /** `apply/3` with a literal argument list names the arity, so the documentation opens at its entry. */
    fun testALiteralMfaOpensAtItsArity() {
        stack()
        myFixture.configureByText(
            "caller.ex",
            "defmodule Caller do\n  def at_apply(a, b), do: apply(Stack, :pu<caret>sh, [a, b])\nend\n"
        )

        val documentation = quickDocumentationAtCaret().orEmpty()
        val anchor = myFixture.documentationAnchorAtCaret(project)

        assertNotNull("Expected an anchor", anchor)
        assertTrue(
            "Expected the anchor ($anchor) on the entry for push/2, got: $documentation",
            documentation.substringAfter("""id="$anchor"""").substringBefore("id=\"").contains("Two.")
        )
    }

    private fun stack() {
        myFixture.addFileToProject(
            "stack.ex",
            "defmodule Stack do\n  @doc \"One.\"\n  def push(a), do: a\n\n  @doc \"Two.\"\n  def push(a, b), do: {a, b}\nend\n"
        )
    }
}
