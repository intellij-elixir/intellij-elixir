package org.elixir_lang.documentation

/** A delegation without its own `@doc` shows its head and links what it delegates to; one hidden with `@doc false` shows no target. */
class UndocumentedDelegationQuickDocumentationTest : QuickDocumentationTestCase() {
    /** A delegation without its own `@doc` shows its head and links what it delegates to, whose docs follow. */
    fun testQuickDocOfAnUndocumentedDelegationLinksItsTarget() {
        myFixture.addFileToProject(
            "linked_target.ex",
            "defmodule Linked.Target do\n  @doc \"Target docs.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "linked.ex",
            "defmodule Linked do\n  defdelegate snoc(q, x \\\\ nil), to: Linked.Target\nend\n"
        )
        myFixture.configureByText("linked_caller.ex", "defmodule LinkedCaller do\n  def calls(a), do: Linked.sn<caret>oc(a, a)\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the delegation's head, got: $shown", "snoc(q, x \\\\ nil)" in shown)
        assertTrue(
            "Expected a link to the target, got: $shown",
            """Delegates to <a href="psi_element://Linked.Target.snoc/2"><code>Linked.Target.snoc/2</code></a>.""" in shown
        )
        assertTrue("Expected the target's docs, got: $shown", "Target docs." in shown)

        val followed = myFixture.followedLinkAtCaret(project, "psi_element://Linked.Target.snoc/2").orEmpty()

        assertTrue("Expected the link to reach the target, got: $followed", "Target docs." in followed)
    }

    /** An operator's name is text in the link, not markup. */
    fun testTheLinkToAnOperatorsTargetIsEscaped() {
        myFixture.addFileToProject(
            "operator_target.ex",
            "defmodule Operator.Target do\n  @doc \"Operator docs.\"\n  def left <~> right, do: {left, right}\nend\n"
        )
        myFixture.configureByText("operator.ex", "defmodule Operator do\n  defdelegate left <~<caret>> right, to: Operator.Target\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()
        val followed = myFixture.followedLinkAtCaret(project, "psi_element://Operator.Target.<~>/2")

        assertTrue("Expected the escaped link text, got: $shown", "<code>Operator.Target.&lt;~&gt;/2</code>" in shown)
        assertTrue("Expected the link to reach the operator, got: $followed", followed?.contains("Operator docs.") == true)
    }

    /** A name with a delegation at each of several arities documents every one, ascending. */
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

        val shown = quickDocumentationAtCaret().orEmpty()
        val links = Regex("psi_element://Arities\\.Target\\.snoc/(\\d)").findAll(shown).map { it.groupValues[1] }.distinct().toList()

        assertEquals("Expected a link per delegation, ascending, got: $shown", listOf("1", "2"), links)
    }

    /** A delegation a `use` injects has no module of its own in its `quote`, and still shows what it delegates to. */
    fun testQuickDocOfAnInjectedDelegationShowsItsTargetsDocs() {
        myFixture.addFileToProject("injected_target.ex", "defmodule InjectedTarget do\n  @doc \"Injected target docs.\"\n  def x(a), do: a\nend\n")
        myFixture.addFileToProject(
            "injector.ex",
            "defmodule Injector do\n  defmacro __using__(_) do\n    quote do\n      defdelegate x(a), to: InjectedTarget\n    end\n  end\nend\n"
        )
        myFixture.configureByText(
            "injected_caller.ex",
            "defmodule Injected do\n  use Injector\nend\n\ndefmodule InjectedCaller do\n  def run, do: Injected.<caret>x(1)\nend\n"
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the target's @doc, got: $documentation", documentation?.contains("Injected target docs.") == true)
        assertTrue("Expected a link to the target, got: $documentation", documentation?.contains("psi_element://InjectedTarget.x/1") == true)
        assertTrue("Expected the delegation in the module that uses the injector, got: $documentation", documentation?.contains("<b>Injected</b>") == true)
        assertFalse("Expected no module called after the quote, got: $documentation", documentation?.contains("quote") == true)
    }

    /** A call in the `quote` that holds the delegation reaches it where the head's card names no module, and so does this one. */
    fun testQuickDocOfACallToADelegationInTheSameQuoteNamesNoModule() {
        myFixture.addFileToProject("quoted_target.ex", "defmodule QuotedTarget do\n  @doc \"Quoted target docs.\"\n  def x(a), do: a\nend\n")
        myFixture.configureByText(
            "quoted_injector.ex",
            "defmodule QuotedInjector do\n  defmacro __using__(_) do\n    quote do\n      defdelegate x(a), to: QuotedTarget\n\n      def run, do: <caret>x(1)\n    end\n  end\nend\n"
        )

        val documentation = quickDocumentationAtCaret()

        assertTrue("Expected the target's @doc, got: $documentation", documentation?.contains("Quoted target docs.") == true)
        assertTrue("Expected a link to the target, got: $documentation", documentation?.contains("psi_element://QuotedTarget.x/1") == true)
        assertFalse("Expected no module called after the quote, got: $documentation", documentation?.contains("quote") == true)
    }

    /** A delegation shows the name Elixir gives it beside its target, as the card of one with no target does. */
    fun testADelegationHeadWrittenWithACombiningMarkShowsTheNameElixirGivesIt() {
        myFixture.addFileToProject("marked_target.ex", "defmodule MarkedTarget do\n  @doc \"Marked target docs.\"\n  def other(q), do: q\nend\n")
        myFixture.configureByText("marked.ex", "defmodule Marked do\n  defdelegate sn<caret>oc\u0301(q), to: MarkedTarget, as: :other\nend\n")

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the target's @doc, got: $documentation", "Marked target docs." in documentation)
        assertTrue("Expected the composed name, got: $documentation", "snoć(q)" in documentation)
    }

    /** `@doc false` is the delegation's own word that the function is hidden: its head shows, and neither its target's docs nor a link. */
    fun testAHiddenDelegationShowsItsHeadAndNoTarget() {
        myFixture.addFileToProject(
            "hidden_target.ex",
            "defmodule Hidden.Target do\n  @doc \"Hidden target docs.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "hidden.ex",
            "defmodule Hidden do\n  @doc false\n  defdelegate snoc(q, x), to: Hidden.Target\nend\n"
        )
        myFixture.configureByText("hidden_caller.ex", "defmodule HiddenCaller do\n  def calls(a), do: Hidden.sn<caret>oc(a, a)\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the head, got: $shown", "snoc(q, x)" in shown)
        assertFalse("Expected no target docs, got: $shown", "Hidden target docs." in shown)
        assertFalse("Expected no link, got: $shown", "psi_element://" in shown)
    }

    /** A delegation with its own `@doc` shows that, and does not link what it delegates to. */
    fun testADocumentedDelegationShowsItsOwnDocAndNoLink() {
        myFixture.addFileToProject(
            "own_target.ex",
            "defmodule Own.Target do\n  @doc \"Own target docs.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "own.ex",
            "defmodule Own do\n  @doc \"The delegation's own docs.\"\n  defdelegate snoc(q, x), to: Own.Target\nend\n"
        )
        myFixture.configureByText("own_caller.ex", "defmodule OwnCaller do\n  def calls(a), do: Own.sn<caret>oc(a, a)\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the delegation's docs, got: $shown", "The delegation&#39;s own docs." in shown || "The delegation's own docs." in shown)
        assertFalse("Expected no target docs, got: $shown", "Own target docs." in shown)
        assertFalse("Expected no link, got: $shown", "Delegates to" in shown)
    }

    /** A delegation reached alone, its target not found, shows its module and head, and links nothing. */
    fun testAnUnresolvableDelegationShowsItsHeadAndNoLink() {
        myFixture.addFileToProject("alone.ex", "defmodule Alone do\n  defdelegate snoc(q, x), to: Missing.Target\nend\n")
        myFixture.configureByText("alone_caller.ex", "defmodule AloneCaller do\n  def calls(a), do: Alone.sn<caret>oc(a, a)\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the module, got: $shown", "Alone" in shown)
        assertTrue("Expected the head, got: $shown", "snoc(q, x)" in shown)
        assertFalse("Expected no link, got: $shown", "Delegates to" in shown)
    }

    /** A name delegated at several arities to a target that is not found lists every arity, as a name defined with `def` does. */
    fun testDelegationsWithNoTargetListEveryArityAtTheirHeadAndAtACall() {
        myFixture.addFileToProject(
            "lone_delegator.ex",
            "defmodule Lone do\n  defdelegate snoc(q), to: Missing.Target\n  defdelegate snoc(q, x), to: Missing.Target\n  defdelegate snoc(q, x, y), to: Missing.Target\nend\n"
        )
        myFixture.configureByText("lone_caller.ex", "defmodule LoneCaller do\n  def calls(a), do: Lone.sn<caret>oc(a, a)\nend\n")

        val atACall = quickDocumentationAtCaret().orEmpty()

        assertEveryArityHeadIsLinked(atACall)

        myFixture.configureByText("lone_head.ex", "defmodule LoneHead do\n  defdelegate sn<caret>oc(q, x), to: Missing.Target\n  defdelegate snoc(q), to: Missing.Target\n  defdelegate snoc(q, x, y), to: Missing.Target\nend\n")

        assertEveryArityHeadIsLinked(quickDocumentationAtCaret().orEmpty())
    }

    private fun assertEveryArityHeadIsLinked(shown: String) {
        val heads = Regex("""<a href="#arity-(\d)">snoc\(([^)]*)\)</a>""").findAll(shown).map { it.groupValues[1] to it.groupValues[2] }.toList()

        assertEquals("Expected a link per arity, ascending, got: $shown", listOf("1" to "q", "2" to "q, x", "3" to "q, x, y"), heads)
    }

    /** The name at the head of a `@spec` over a delegation shows what the delegation does, as the delegation's own head does. */
    fun testASpecOverAnUndocumentedDelegationShowsItsTarget() {
        myFixture.addFileToProject(
            "spec_target.ex",
            "defmodule Spec.Target do\n  @doc \"Spec target docs.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.configureByText(
            "spec.ex",
            "defmodule Spec do\n  @spec sn<caret>oc(term, term) :: term\n  defdelegate snoc(q, x), to: Spec.Target\nend\n"
        )

        val shown = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected a link to the target, got: $shown", "psi_element://Spec.Target.snoc/2" in shown)
        assertTrue("Expected the target's docs, got: $shown", "Spec target docs." in shown)
    }

    /** `@doc` with a keyword list sets metadata and writes no doc, so a delegation with only that still shows and links its target. */
    fun testMetadataAboveADelegationIsNotItsOwnDoc() {
        myFixture.addFileToProject(
            "since_target.ex",
            "defmodule Since.Target do\n  @doc \"Since target docs.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "since.ex",
            "defmodule Since do\n  @doc since: \"1.0\"\n  defdelegate snoc(q, x), to: Since.Target\nend\n"
        )
        myFixture.configureByText("since_caller.ex", "defmodule SinceCaller do\n  def calls(a), do: Since.sn<caret>oc(a, a)\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected a link to the target, got: $shown", "psi_element://Since.Target.snoc/2" in shown)
        assertTrue("Expected the target's docs, got: $shown", "Since target docs." in shown)
    }

    /** A delegation marked `@doc false` is not listed with the undocumented others of its name from outside its module. */
    fun testAHiddenDelegationIsNotListedBesideAnotherArityForARemoteCall() {
        configureHiddenBesideShown()
        myFixture.configureByText("remote_caller.ex", "defmodule RemoteCaller do\n  def calls(a), do: Beside.sn<caret>oc(a)\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the shown delegation's target, got: $shown", "psi_element://Beside.Target.snoc/1" in shown)
        assertFalse("Expected no hidden delegation, got: $shown", "psi_element://Beside.Target.snoc/2" in shown)
    }

    /** Inside its module every arity is callable, so a local call lists the delegation marked `@doc false` too. */
    fun testAHiddenDelegationIsListedBesideAnotherArityForALocalCall() {
        myFixture.addFileToProject(
            "beside_target.ex",
            "defmodule Beside.Target do\n  def snoc(q), do: q\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.configureByText(
            "beside_local.ex",
            "defmodule Beside do\n  defdelegate snoc(q), to: Beside.Target\n\n  @doc false\n  defdelegate snoc(q, x), to: Beside.Target\n\n  def calls(a), do: sn<caret>oc(a)\nend\n"
        )

        val shown = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected both delegations, got: $shown", "psi_element://Beside.Target.snoc/1" in shown && "psi_element://Beside.Target.snoc/2" in shown)
    }

    private fun configureHiddenBesideShown() {
        myFixture.addFileToProject(
            "beside_target.ex",
            "defmodule Beside.Target do\n  def snoc(q), do: q\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "beside.ex",
            "defmodule Beside do\n  defdelegate snoc(q), to: Beside.Target\n\n  @doc false\n  defdelegate snoc(q, x), to: Beside.Target\nend\n"
        )
    }

    /** The head of an undocumented delegation shows what a use of it does. */
    fun testTheHeadOfAnUndocumentedDelegationLinksItsTarget() {
        myFixture.addFileToProject(
            "head_target.ex",
            "defmodule Head.Target do\n  @doc \"Head target docs.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.configureByText("head.ex", "defmodule Head do\n  defdelegate sn<caret>oc(q, x), to: Head.Target\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected a link to the target, got: $shown", "psi_element://Head.Target.snoc/2" in shown)
        assertTrue("Expected the target's docs, got: $shown", "Head target docs." in shown)
    }
}
