package org.elixir_lang.documentation

/**
 * Quick Documentation on the function atom of `apply/3` names a `defdelegate`d function and documents what the
 * delegation forwards to, as it does at a direct call.
 */
class DelegatedApplyQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testQuickDocAtApplyOnADelegatedFunctionDocumentsTheTarget() {
        myFixture.addFileToProject(
            "target.ex",
            """
            defmodule Delegated.Target do
              def snoc(q, x), do: {q, x}
            end
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "delegator.ex",
            """
            defmodule Delegated do
              defdelegate snoc(q, x), to: Delegated.Target
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              def at_apply(a, b), do: apply(Delegated, :sn<caret>oc, [a, b])
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertNotNull("Quick Documentation at apply showed nothing", documentation)
        assertTrue("Expected snoc's head, got: $documentation", documentation!!.contains("snoc(q, x)"))
    }

    /** An atom naming a module, not a function, still documents the module. */
    fun testQuickDocAtAModuleAtomDocumentsTheModule() {
        myFixture.addFileToProject(
            "documented.ex",
            """
            defmodule :documented do
              @moduledoc "The documented module."

              def f, do: :ok
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              def at_apply, do: apply(:docu<caret>mented, :f, [])
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the module's @moduledoc, got: $documentation", documentation?.contains("The documented module.") == true)
    }
}
