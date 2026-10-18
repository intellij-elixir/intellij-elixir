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

    /** A target whose name holds a dot is not mistaken for a function of the module named by what precedes the dot. */
    fun testTheLinkToATargetWithADottedNameReachesIt() {
        assertTheLinkToATargetNamedReachesIt("Dotted", """:"part.name"""", """"part.name"""")
    }

    /** A name that is no identifier is written after the module as Elixir writes it, quoted. */
    fun testTheLinkToATargetWithASpacedNameReachesIt() {
        assertTheLinkToATargetNamedReachesIt("Spaced", """:"two words"""", """"two words"""")
    }

    /** A quote in a name is escaped in the link and read back as one. */
    fun testTheLinkToATargetWithAQuoteInItsNameReachesIt() {
        assertTheLinkToATargetNamedReachesIt("Quoted", """:"say \"hi\""""", """"say \"hi\""""")
    }

    private fun assertTheLinkToATargetNamedReachesIt(module: String, atom: String, written: String) {
        myFixture.addFileToProject(
            "${module.lowercase()}_target.ex",
            "defmodule $module.Target do\n  @doc \"$module target docs.\"\n  def unquote($atom)(x), do: x\nend\n"
        )
        myFixture.configureByText(
            "${module.lowercase()}.ex",
            "defmodule $module do\n  defdelegate fu<caret>nction(x), to: $module.Target, as: $atom\nend\n"
        )

        val shown = quickDocumentationAtCaret().orEmpty()
        val href = Regex("""Delegates to <a href="([^"]*)"""").find(shown)?.groupValues?.get(1)

        assertNotNull("Expected a link to the target, got: $shown", href)

        val link = com.intellij.openapi.util.text.StringUtil.unescapeXmlEntities(href!!)
        val followed = myFixture.followedLinkAtCaret(project, link)

        assertEquals("Expected the name written as Elixir writes it", "psi_element://$module.Target.$written/1", link)
        assertTrue("Expected the link to reach the target, got: $followed from $href", followed?.contains("$module target docs.") == true)
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

    /** Inside its module every arity is callable, so a local call lists the delegation marked `@doc false` too, by its head, as when it is reached alone. */
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

        assertTrue("Expected the delegation reached to link its target, got: $shown", "psi_element://Beside.Target.snoc/1" in shown)
        assertEquals("Expected a head for each delegation of the module, got: $shown", 2, "<b>Beside</b>".toRegex().findAll(shown).count())
        assertFalse("Expected no link from the hidden delegation, got: $shown", "psi_element://Beside.Target.snoc/2" in shown)
    }

    /** Another arity of the name with a `@doc` of its own shows that doc beside the undocumented one reached, and no link to its target. */
    fun testASiblingDelegationWithItsOwnDocShowsThatDocBesideTheOneReached() {
        myFixture.addFileToProject(
            "sibling_target.ex",
            "defmodule Sibling.Target do\n  def snoc(q), do: q\n  @doc \"Second target docs.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "sibling.ex",
            "defmodule Sibling do\n  @doc \"First arity own docs.\"\n  defdelegate snoc(q), to: Sibling.Target\n\n  defdelegate snoc(q, x), to: Sibling.Target\nend\n"
        )
        myFixture.configureByText("sibling_caller.ex", "defmodule SiblingCaller do\n  def calls(a), do: Sibling.sn<caret>oc(a, a)\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the sibling's own docs, got: $shown", "First arity own docs." in shown)
        assertTrue("Expected a link to the target of the delegation reached, got: $shown", "psi_element://Sibling.Target.snoc/2" in shown)
        assertFalse("Expected no link to the target of the sibling with docs, got: $shown", "psi_element://Sibling.Target.snoc/1" in shown)
    }

    /** A function the module defines itself beside a delegation of its name shows too, with its docs, not only the delegations. */
    fun testADefinedSiblingArityShowsBesideTheDelegationReached() {
        myFixture.addFileToProject(
            "defined_target.ex",
            "defmodule Defined.Target do\n  def snoc(q), do: q\n  @doc \"Second target docs.\"\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "defined.ex",
            "defmodule Defined do\n  @doc \"Defined first arity docs.\"\n  def snoc(q), do: q\n\n  defdelegate snoc(q, x), to: Defined.Target\nend\n"
        )
        myFixture.configureByText("defined_caller.ex", "defmodule DefinedCaller do\n  def calls(a), do: Defined.sn<caret>oc(a, a)\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the defined arity's docs, got: $shown", "Defined first arity docs." in shown)
        assertTrue("Expected a link to the target of the delegation reached, got: $shown", "psi_element://Defined.Target.snoc/2" in shown)
        assertFalse("Expected no link for the defined arity, got: $shown", "psi_element://Defined.Target.snoc/1" in shown)
    }

    /** A delegation with no `@doc` that delegates to one with its own `@doc` shows the docs of that one, beside its own head and the link to it. */
    fun testADelegationToADocumentedDelegationShowsItsOwnHeadAndLink() {
        myFixture.addFileToProject(
            "chain_end.ex",
            "defmodule Chain.End do\n  def f(x), do: x\nend\n"
        )
        myFixture.addFileToProject(
            "chain_middle.ex",
            "defmodule Chain.Middle do\n  @doc \"Middle docs.\"\n  defdelegate f(x), to: Chain.End\nend\n"
        )
        myFixture.addFileToProject(
            "chain.ex",
            "defmodule Chain do\n  defdelegate f(x), to: Chain.Middle\nend\n"
        )
        myFixture.configureByText("chain_caller.ex", "defmodule ChainCaller do\n  def calls(a), do: Chain.<caret>f(a)\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the docs of the delegation reached, got: $shown", "Middle docs." in shown)
        assertTrue("Expected the block of the delegation called, got: $shown", "<b>Chain</b>" in shown)
        assertTrue("Expected a link to the delegation it delegates to, got: $shown", "psi_element://Chain.Middle.f/1" in shown)
    }

    /** A head a sibling writes twice, as a bodiless head and a clause, shows once beside the delegation reached. */
    fun testAHeadASiblingWritesTwiceShowsOnceBesideTheDelegationReached() {
        myFixture.addFileToProject(
            "twice_target.ex",
            "defmodule Twice.Target do\n  def reduce(e, a, f), do: {e, a, f}\nend\n"
        )
        myFixture.addFileToProject(
            "twice.ex",
            "defmodule Twice do\n  defdelegate reduce(e, a, f), to: Twice.Target\n\n  @doc \"Twice docs.\"\n  def reduce(enumerable, fun)\n  def reduce([h | t], fun), do: {h, t, fun}\n  def reduce(enumerable, fun), do: {enumerable, fun}\nend\n"
        )
        myFixture.configureByText("twice_caller.ex", "defmodule TwiceCaller do\n  def calls(a), do: Twice.red<caret>uce(a, a, a)\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()

        assertEquals("Expected the repeated head once, got: $shown", 1, "reduce(enumerable, fun)".toRegex(RegexOption.LITERAL).findAll(shown).count())
        assertEquals("Expected the other head kept, got: $shown", 1, "reduce([h | t], fun)".toRegex(RegexOption.LITERAL).findAll(shown).count())
    }

    /** The clauses of a function defined beside the delegation reached are one function, as the arities card shows them, with a head for each. */
    fun testTheClausesOfADefinedSiblingAreOneBlock() {
        myFixture.addFileToProject(
            "clauses_target.ex",
            "defmodule Clauses.Target do\n  def snoc(q), do: q\nend\n"
        )
        myFixture.addFileToProject(
            "clauses.ex",
            "defmodule Clauses do\n  defdelegate snoc(q), to: Clauses.Target\n\n  @doc \"Clauses docs.\"\n  def snoc(q, x) when is_list(q), do: [x | q]\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.configureByText("clauses_caller.ex", "defmodule ClausesCaller do\n  def calls(a), do: Clauses.sn<caret>oc(a)\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()

        assertEquals("Expected one block for each function of the name, got: $shown", 2, "<b>Clauses</b>".toRegex().findAll(shown).count())
        assertEquals("Expected the docs of the clauses once, got: $shown", 1, "Clauses docs.".toRegex().findAll(shown).count())
        assertEquals("Expected a head for each clause, got: $shown", 2, "snoc\\(q, x\\)".toRegex().findAll(shown).count())
    }

    /** A function a sibling's docs name without a module is the sibling's own module's, not the target's. */
    fun testALinkInASiblingsDocOpensTheFunctionOfTheSiblingsModule() {
        myFixture.addFileToProject(
            "relative_target.ex",
            "defmodule Relative.Target do\n  @doc \"Target helper docs.\"\n  def helper(x), do: x\n\n  def snoc(q), do: q\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "relative.ex",
            "defmodule Relative do\n  @doc \"Own helper docs.\"\n  def helper(x), do: x\n\n  @doc \"Use `helper/1`.\"\n  defdelegate snoc(q), to: Relative.Target\n\n  defdelegate snoc(q, x), to: Relative.Target\nend\n"
        )
        myFixture.configureByText("relative_caller.ex", "defmodule RelativeCaller do\n  def calls(a), do: Relative.sn<caret>oc(a, a)\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()
        val href = Regex("""href="(psi_element://[^"]*helper/1)"""").find(shown)?.groupValues?.get(1)

        assertNotNull("Expected the sibling's docs to link helper/1, got: $shown", href)

        val followed = myFixture.followedLinkAtCaret(project, href!!).orEmpty()

        assertTrue("Expected the link to reach the sibling's module's function, got: $followed", "Own helper docs." in followed)
        assertFalse("Expected the link not to reach the target's function, got: $followed", "Target helper docs." in followed)
    }

    /** A bare link is drawn as one in a sibling's docs whatever it names, as it is in the docs of the function reached. */
    fun testABareLinkInASiblingsDocIsDrawnWhetherOrNotItsFunctionExists() {
        myFixture.addFileToProject(
            "drawn_target.ex",
            "defmodule Drawn.Target do\n  def snoc(q), do: q\n  def snoc(q, x), do: {q, x}\nend\n"
        )
        myFixture.addFileToProject(
            "drawn.ex",
            "defmodule Drawn do\n  @doc \"Use `missing/1`.\"\n  defdelegate snoc(q), to: Drawn.Target\n\n  defdelegate snoc(q, x), to: Drawn.Target\nend\n"
        )
        myFixture.configureByText("drawn_caller.ex", "defmodule DrawnCaller do\n  def calls(a), do: Drawn.sn<caret>oc(a, a)\nend\n")

        val shown = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the bare link drawn as a link, got: $shown", "psi_element://Drawn.missing/1" in shown)
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
