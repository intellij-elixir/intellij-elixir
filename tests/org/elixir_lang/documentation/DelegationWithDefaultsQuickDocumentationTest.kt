package org.elixir_lang.documentation

/** A delegation with defaults is one function at each arity it declares, so a use at a lower one documents it as at the full one. */
class DelegationWithDefaultsQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testQuickDocAtALowerArityOfADelegationWithDefaultsIsTheFullAritys() {
        for ((module, doc) in listOf("Plain" to "", "Documented" to "@doc \"The delegation's snoc.\"\n  ")) {
            val shown = docsAtEachUse(module, doc)
            val full = shown.getValue("full")

            assertNotNull("$module: nothing at the full arity", full)
            assertEquals(module, shown.mapValues { full }, shown)
        }
    }

    /** A call at a lower arity fills in the defaults and calls the target at the full one, so the card opens at the target's entry for that arity. */
    fun testACallAtALowerArityOpensTheTargetsEntryForTheArityItPasses() {
        myFixture.addFileToProject(
            "target_opens.ex",
            "defmodule Opens.Target do\n  @doc \"One.\"\n  def snoc(q), do: q\n\n  @doc \"Two.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "delegator_opens.ex",
            "defmodule Opens do\n  defdelegate snoc(q, x \\\\ nil), to: Opens.Target\nend\n"
        )
        myFixture.configureByText("caller_opens.ex", "defmodule CallerOpens do\n  def run(a), do: Opens.sn<caret>oc(a)\nend\n")

        val documentation = quickDocumentationAtCaret().orEmpty()
        val anchor = myFixture.documentationAnchorAtCaret(project)

        assertNotNull("Expected an anchor, got: $documentation", anchor)
        assertTrue(
            "Expected the anchor ($anchor) on the target's entry for snoc/2, got: $documentation",
            documentation.substringAfter("""id="$anchor"""").substringBefore("id=\"").contains("Two.")
        )
    }

    /** Through a chain of delegations the target is called at the arity the last one passes on, so the card opens at its entry. */
    fun testACallThroughAChainOpensTheTargetsEntryForTheArityTheLastDelegationPasses() {
        chain()

        assertOpensAtTwo(quickDocumentationAtCaret().orEmpty(), myFixture.documentationAnchorAtCaret(project))
    }

    /** A followed link to the first delegation of a chain opens where a call of it does. */
    fun testALinkThroughAChainOpensTheTargetsEntryForTheArityTheLastDelegationPasses() {
        chain()

        assertOpensAtTwo(
            myFixture.followedLinkAtCaret(project, "psi_element://Chain.f/1").orEmpty(),
            myFixture.followedAnchorAtCaret(project, "psi_element://Chain.f/1"),
        )
    }

    /** At the head of the first delegation of a chain, the card opens where a call of it does. */
    fun testTheHeadOfAChainsFirstDelegationOpensTheTargetsEntryForTheArityTheLastDelegationPasses() {
        chain(atTheHead = true)

        assertOpensAtTwo(quickDocumentationAtCaret().orEmpty(), myFixture.documentationAnchorAtCaret(project))
    }

    /**
     * `Chain.f/1` delegates to `Chain.Middle.f/2`, whose default fills in the second argument for `Chain.End.f/2`, with
     * the caret on `Chain`'s head [atTheHead], else on a call of `Chain.f/1` in `Caller`.
     */
    private fun chain(atTheHead: Boolean = false) {
        myFixture.addFileToProject(
            "chain_end.ex",
            "defmodule Chain.End do\n  @doc \"One.\"\n  def f(x), do: x\n\n  @doc \"Two.\"\n  def f(x, y), do: {x, y}\nend\n"
        )
        myFixture.addFileToProject("chain_middle.ex", "defmodule Chain.Middle do\n  defdelegate f(x, y \\\\ 0), to: Chain.End\nend\n")
        if (atTheHead) {
            myFixture.configureByText("chain.ex", "defmodule Chain do\n  defdelegate <caret>f(x), to: Chain.Middle\nend\n")
        } else {
            myFixture.addFileToProject("chain.ex", "defmodule Chain do\n  defdelegate f(x), to: Chain.Middle\nend\n")
            myFixture.configureByText("chain_caller.ex", "defmodule Caller do\n  def run(a), do: Chain.<caret>f(a)\nend\n")
        }
    }

    /** [documentation] opens at [anchor], on `Chain.End.f/2`'s entry. */
    private fun assertOpensAtTwo(documentation: String, anchor: String?) {
        assertNotNull("Expected an anchor, got: $documentation", anchor)
        assertTrue(
            "Expected the anchor ($anchor) on the entry for f/2, got: $documentation",
            documentation.substringAfter("""id="$anchor"""").substringBefore("id=\"").contains("Two.")
        )
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
}
