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

    /** A delegation's own `@doc` says what the function means there, at `apply/3` as at a call. */
    fun testADelegationsOwnDocShowsAtApplyAsAtACall() {
        myFixture.addFileToProject(
            "target.ex",
            """
            defmodule Delegated.Target do
              @doc "The target's snoc."
              def snoc(q, x), do: {q, x}
            end
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "delegator.ex",
            """
            defmodule Delegated do
              @doc "The delegation's snoc."
              defdelegate snoc(q, x), to: Delegated.Target
            end
            """.trimIndent()
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
                listOf("The delegation's snoc.", "The target's snoc.").filter { it in documentation }
            }
        }

        assertEquals(listOf(listOf("The delegation's snoc."), listOf("The delegation's snoc.")), shown)
    }

    /** A delegation with defaults is one function at each arity it declares, so a use at a lower one documents it as at the full one. */
    fun testQuickDocAtALowerArityOfADelegationWithDefaultsIsTheFullAritys() {
        for ((module, doc) in listOf("Plain" to "", "Documented" to "@doc \"The delegation's snoc.\"\n  ")) {
            val shown = docsAtEachUse(module, doc)
            val full = shown.getValue("full")

            assertNotNull("$module: nothing at the full arity", full)
            assertEquals(module, shown.mapValues { full }, shown)
        }
    }

    /** Each case its own module: the project keeps the files of the one before. */
    private fun docsAtEachUse(module: String, doc: String): Map<String, String?> {
        myFixture.addFileToProject(
            "target_$module.ex",
            "defmodule $module.Target do\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "delegator_$module.ex",
            "defmodule $module do\n  ${doc}defdelegate snoc(q, x \\\\ nil), to: $module.Target\nend\n"
        )
        val caller = """
            defmodule Caller$module do
              def at_full(a), do: $module.snoc(a, a)
              def at_call(a), do: $module.snoc(a)
              def at_apply(a), do: apply($module, :snoc, [a])
              def at_mfa(a), do: {$module, :snoc, 1}
              def at_capture(a), do: &$module.snoc/1
            end
        """.trimIndent()

        return mapOf(
            "full" to "$module.sn<caret>oc(a, a)",
            "call" to "$module.sn<caret>oc(a)",
            "apply" to "apply($module, :sn<caret>oc",
            "mfa" to "{$module, :sn<caret>oc, 1}",
            "capture" to "&$module.sn<caret>oc/1"
        ).mapValues { (_, caretAt) ->
            myFixture.configureByText("caller_$module.ex", caller.replace(caretAt.replace("<caret>", ""), caretAt))

            quickDocumentationAtCaret()
        }
    }

    /** A delegation without its own `@doc` shows its head and links what it delegates to, whose docs follow. */
    fun testQuickDocOfAnUndocumentedDelegationLinksItsTarget() {
        val shown = docsAtEachUse("Linked", "").getValue("full")!!

        assertTrue("Expected the delegation's head, got: $shown", "snoc(q, x \\\\ nil)" in shown)
        assertTrue("Expected a link to the target, got: $shown", "psi_element://Linked.Target.snoc/2" in shown)
        assertTrue("Expected the target's head, got: $shown", "snoc(q, x)" in shown)

        val linked = ElixirDocumentationProvider()
            .getDocumentationElementForLink(psiManager, "Linked.Target.snoc/2", myFixture.file)
        assertTrue("Expected the link to reach the target, got ${linked?.text}", linked?.text?.startsWith("def snoc(q, x)") == true)
    }

    /** A name with a delegation at each of several arities documents every one, ascending, as a `def`'s quick docs do. */
    fun testQuickDocOfANameWithSeveralDelegationsListsEveryArity() {
        myFixture.addFileToProject(
            "arities_target.ex",
            "defmodule Arities.Target do\n  def snoc(q), do: q\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "arities_delegator.ex",
            "defmodule Arities do\n  defdelegate snoc(q, x), to: Arities.Target\n  defdelegate snoc(q), to: Arities.Target\nend\n"
        )
        myFixture.configureByText("arities_caller.ex", "defmodule AritiesCaller do\n  def calls(a), do: Arities.sn<caret>oc(a, a)\nend\n")

        val shown = quickDocumentationAtCaret()!!
        val links = Regex("psi_element://Arities\\.Target\\.snoc/(\\d)").findAll(shown).map { it.groupValues[1] }.toList()

        assertEquals(listOf("1", "2"), links)
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
