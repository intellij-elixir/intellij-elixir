package org.elixir_lang.documentation

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL_REF_SEPARATOR

/** A function's documentation shows every arity of its name, ascending, with a header linking to each, opened at the arity used. */
class EveryArityQuickDocumentationTest : QuickDocumentationTestCase() {
    private fun configure(caller: String) {
        myFixture.configureByText(
            "arities.ex",
            """
            defmodule Arities do
              @doc "Three."
              def push(a, b, c), do: {a, b, c}

              @doc "One."
              def push(a), do: a

              @doc "Two."
              def push(a, b), do: {a, b}

              @doc "Other."
              def other(a), do: a

              $caller
            end
            """.trimIndent()
        )
    }

    private fun linkedIds(documentation: String): List<String> =
        Regex("""href="#([^"]+)"""").findAll(documentation).map { it.groupValues[1] }.toList()

    fun testEveryArityIsShownAscendingWhateverTheSourceOrder() {
        configure("def calls(a), do: pu<caret>sh(a, a)")

        val documentation = quickDocumentationAtCaret().orEmpty()
        val order = listOf("One.", "Two.", "Three.").map { documentation.indexOf(it) }

        assertTrue("Expected each arity's @doc, got: $documentation", order.all { it >= 0 })
        assertEquals("Expected ascending arity, got: $documentation", order.sorted(), order)
        assertFalse("Expected no other function's @doc, got: $documentation", "Other." in documentation)
    }

    fun testTheHeaderLinksNameEntriesThatExist() {
        configure("def calls(a), do: pu<caret>sh(a, a)")

        val documentation = quickDocumentationAtCaret().orEmpty()
        val ids = linkedIds(documentation)

        assertEquals("Expected a link per arity, got: $documentation", 3, ids.distinct().size)
        for (id in ids) {
            assertTrue("Expected an entry with id $id, got: $documentation", """id="$id"""" in documentation)
        }
    }

    fun testASingleArityHasNoHeader() {
        configure("def calls(a), do: ot<caret>her(a)")

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the @doc, got: $documentation", "Other." in documentation)
        assertEquals("Expected no header, got: $documentation", emptyList<String>(), linkedIds(documentation))
    }

    fun testTheDocumentationOpensAtTheArityCalled() {
        configure("def calls(a), do: pu<caret>sh(a, a)")

        val anchor = myFixture.documentationAnchorAtCaret(project)
        val documentation = quickDocumentationAtCaret().orEmpty()

        assertNotNull("Expected an anchor", anchor)
        assertTrue("Expected the anchor ($anchor) on an entry, got: $documentation", """id="$anchor"""" in documentation)
        assertTrue("Expected the entry anchored to hold Two., got: $documentation", documentation.substringAfter("""id="$anchor"""").substringBefore("id=\"").contains("Two."))
    }

    fun testACapturedFunctionOpensAtItsArity() {
        configure("def calls, do: &pu<caret>sh/3")

        val anchor = myFixture.documentationAnchorAtCaret(project)
        val documentation = quickDocumentationAtCaret().orEmpty()

        assertNotNull("Expected an anchor", anchor)
        assertTrue("Expected the entry anchored to hold Three., got: $documentation", documentation.substringAfter("""id="$anchor"""").substringBefore("id=\"").contains("Three."))
    }

    fun testAnArityNoEntryHasOpensAtNone() {
        configure("def calls(a), do: pu<caret>sh(a, a, a, a)")

        assertTrue("Expected the arities that exist", "Three." in quickDocumentationAtCaret().orEmpty())
        assertNull(myFixture.documentationAnchorAtCaret(project))
    }

    /** Defaults are one function at the arities they cover: one entry, anchored whichever of them is used. */
    fun testDefaultsAreOneEntryAnchoredAtEveryArityTheyCover() {
        myFixture.configureByText(
            "defaults.ex",
            """
            defmodule Defaults do
              @doc "Defaults."
              def snoc(q, x \\ nil), do: {q, x}

              @doc "Three."
              def snoc(a, b, c), do: {a, b, c}

              def calls(a), do: sn<caret>oc(a)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()
        val anchor = myFixture.documentationAnchorAtCaret(project)

        assertEquals("Expected an entry per function, got: $documentation", 2, linkedIds(documentation).distinct().size)
        assertTrue("Expected the entry anchored to hold Defaults., got: $documentation", documentation.substringAfter("""id="$anchor"""").substringBefore("id=\"").contains("Defaults."))
    }

    /** The default arity is as much the defaults' entry as the full one. */
    fun testTheOtherArityTheDefaultsCoverOpensAtTheSameEntry() {
        myFixture.configureByText(
            "defaults.ex",
            """
            defmodule Defaults do
              @doc "Defaults."
              def snoc(q, x \\ nil, y \\ nil), do: {q, x, y}

              @doc "Four."
              def snoc(a, b, c, d), do: {a, b, c, d}

              def calls(a), do: sn<caret>oc(a, a)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()
        val anchor = myFixture.documentationAnchorAtCaret(project)

        assertNotNull("Expected an anchor", anchor)
        assertTrue("Expected the entry anchored to hold Defaults., got: $documentation", documentation.substringAfter("""id="$anchor"""").substringBefore("id=\"").contains("Defaults."))
    }

    /** From outside the module, a private arity is not listed with the others, nor is one marked `@doc false`. */
    fun testAPrivateAndAHiddenArityAreNotListedForARemoteCall() {
        myFixture.configureByText(
            "visible.ex",
            """
            defmodule Visible do
              @doc "One."
              def push(a), do: a

              @doc false
              def push(a, b), do: {a, b}

              defp push(a, b, c), do: {a, b, c}

              @doc "Four."
              def push(a, b, c, d), do: push(a, b, c) && d
            end

            defmodule Caller do
              def calls(a), do: Visible.pu<caret>sh(a)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the visible arities, got: $documentation", "One." in documentation && "Four." in documentation)
        assertEquals("Expected a link per visible arity, got: $documentation", 2, linkedIds(documentation).distinct().size)
        assertFalse("Expected no entry for push(a, b, c), got: $documentation", "push(a, b, c)" in documentation)
        assertFalse("Expected no entry for push(a, b), got: $documentation", "push(a, b)" in documentation)
    }

    /** A function is hidden by the `@doc false` above its first clause, whichever clauses follow, so none of its clauses lists it. */
    fun testAHiddenFunctionWithSeveralClausesIsNotListedForARemoteCall() {
        myFixture.configureByText(
            "clauses.ex",
            """
            defmodule Clauses do
              @doc "One."
              def push(a), do: a

              @doc false
              def push(a, 0), do: a
              def push(a, b), do: {a, b}
            end

            defmodule Caller do
              def calls(a), do: Clauses.pu<caret>sh(a)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the visible arity, got: $documentation", "One." in documentation)
        assertFalse("Expected no entry for push(a, b), got: $documentation", "push(a, b)" in documentation)
    }

    /** Inside the module that defines them, a local call can call a private arity and one marked `@doc false`, so both are listed. */
    fun testALocalCallListsThePrivateAndHiddenArities() {
        myFixture.configureByText(
            "local.ex",
            """
            defmodule Local do
              @doc "One."
              def push(a), do: a

              @doc false
              def push(a, b), do: {a, b}

              defp push(a, b, c), do: {a, b, c}

              def calls(a), do: pu<caret>sh(a)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertEquals("Expected a link per arity, got: $documentation", 3, linkedIds(documentation).distinct().size)
        assertTrue("Expected push(a, b), got: $documentation", "push(a, b)" in documentation)
        assertTrue("Expected push(a, b, c), got: $documentation", "push(a, b, c)" in documentation)
    }

    /** A head is inside its module, so it lists every arity of its name whatever their visibility. */
    fun testAPrivateHeadListsEveryArityOfItsName() {
        myFixture.configureByText(
            "head.ex",
            """
            defmodule Head do
              @doc "One."
              def push(a), do: a

              @doc false
              def push(a, b), do: {a, b}

              defp pu<caret>sh(a, b, c), do: {a, b, c}
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertEquals("Expected a link per arity, got: $documentation", 3, linkedIds(documentation).distinct().size)
        assertTrue("Expected push(a), got: $documentation", "One." in documentation)
        assertTrue("Expected push(a, b), got: $documentation", "push(a, b)" in documentation)
    }

    /** A call is documented where it lands, even on an arity that is private or hidden. */
    fun testACallToAPrivateArityDocumentsThatArity() {
        myFixture.configureByText(
            "private.ex",
            """
            defmodule Private do
              @doc "One."
              def push(a), do: a

              @doc "Private two."
              defp push(a, b), do: {a, b}

              def calls(a), do: pu<caret>sh(a, a)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the private arity's doc, got: $documentation", "Private two." in documentation)
    }

    /** A delegation is documented by its target, which shows every arity as a function does. */
    fun testADelegationsTargetShowsEveryArity() {
        myFixture.addFileToProject(
            "target.ex",
            """
            defmodule Target do
              @doc "Target one."
              def push(a), do: a

              @doc "Target two."
              def push(a, b), do: {a, b}
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "delegator.ex",
            """
            defmodule Delegator do
              defdelegate push(a), to: Target

              def calls(a), do: pu<caret>sh(a)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected both arities of the target, got: $documentation", "Target one." in documentation && "Target two." in documentation)
        assertEquals("Expected a link per arity, got: $documentation", 2, linkedIds(documentation).distinct().size)
    }

    /** An imported call is a use from outside the module that defines the function, so it lists no private or `@doc false` arity. */
    fun testAnImportedCallListsNoPrivateOrHiddenArity() {
        myFixture.addFileToProject(
            "other.ex",
            """
            defmodule Other do
              @doc "One."
              def push(a), do: a

              @doc false
              def push(a, b), do: {a, b}

              defp push(a, b, c), do: {a, b, c}

              @doc "Four."
              def push(a, b, c, d), do: push(a, b, c) && d
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "importer.ex",
            """
            defmodule Importer do
              import Other

              def calls(a), do: pu<caret>sh(a)
            end
            """.trimIndent()
        )

        assertOnlyTheVisibleArities(quickDocumentationAtCaret().orEmpty())
    }

    /** A local call to a delegation reaches its target, which is another module, so the target's private arities are not listed. */
    fun testALocalCallToADelegationListsNoPrivateOrHiddenArityOfItsTarget() {
        myFixture.addFileToProject(
            "other.ex",
            """
            defmodule Other do
              @doc "One."
              def push(a), do: a

              @doc false
              def push(a, b), do: {a, b}

              defp push(a, b, c), do: {a, b, c}

              @doc "Four."
              def push(a, b, c, d), do: push(a, b, c) && d
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "delegator.ex",
            """
            defmodule Delegator do
              defdelegate push(a), to: Other

              def calls(a), do: pu<caret>sh(a)
            end
            """.trimIndent()
        )

        assertOnlyTheVisibleArities(quickDocumentationAtCaret().orEmpty())
    }

    /** The `@doc false` nearest the function hides the arity, whatever keyword `@doc` is written above it. */
    fun testMetadataAboveAHiddenArityDoesNotShowIt() {
        myFixture.configureByText(
            "metadata.ex",
            """
            defmodule Metadata do
              @doc "One."
              def push(a), do: a

              @doc since: "1.0"
              @doc false
              def push(a, b), do: {a, b}
            end

            defmodule Caller do
              def calls(a), do: Metadata.pu<caret>sh(a)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the visible arity, got: $documentation", "One." in documentation)
        assertFalse("Expected no entry for push(a, b), got: $documentation", "push(a, b)" in documentation)
    }

    /** As in Elixir, the `@doc` nearest the function stands: `false` after a text replaces it, and a text after a text does. */
    fun testTheNearestDocIsTheOneShown() {
        myFixture.configureByText(
            "nearest.ex",
            """
            defmodule Nearest do
              @doc "Replaced by false."
              @doc false
              def push(a), do: a

              @doc "Replaced by the next."
              @doc "Stands."
              def push(a, b), do: {a, b}
            end

            defmodule Caller do
              def calls(a), do: Nearest.pu<caret>sh(a)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the nearest doc, got: $documentation", "Stands." in documentation)
        assertFalse("Expected no doc replaced by false, got: $documentation", "Replaced by false." in documentation)
        assertFalse("Expected no doc replaced by a later one, got: $documentation", "Replaced by the next." in documentation)
    }

    /** Following a link to an arity opens the card at that arity's entry. */
    fun testFollowingAFunctionLinkOpensAtTheArityLinked() {
        configure("def calls(a), do: pu<caret>sh(a, a)")

        assertEquals("arity-3", myFixture.followedAnchorAtCaret(project, "psi_element://Arities.push/3"))
        assertEquals("arity-1", myFixture.followedAnchorAtCaret(project, "psi_element://Arities.push/1"))
    }

    /** An anchor written in the link wins over the one its arity gives. */
    fun testAnAnchorWrittenInTheLinkWinsOverItsArity() {
        configure("def calls(a), do: pu<caret>sh(a, a)")

        assertEquals(
            "arity-1",
            myFixture.followedAnchorAtCaret(project, "psi_element://Arities.push/3$PSI_ELEMENT_PROTOCOL_REF_SEPARATOR" + "arity-1")
        )
    }

    /** A bodiless head and a clause written alike are one head: the header links it once and the entry's title shows it once. */
    fun testAHeadWrittenTwiceIsListedOnceAmongSeveralArities() {
        myFixture.configureByText(
            "reducing.ex",
            """
            defmodule Reducing do
              @doc "Two."
              def reduce(enumerable, fun)
              def reduce([h | t], fun), do: {h, t, fun}
              def reduce(enumerable, fun), do: {enumerable, fun}

              @doc "Three."
              def reduce(enumerable, acc, fun), do: {enumerable, acc, fun}

              def calls(a), do: red<caret>uce(a, a)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()
        val head = "reduce(enumerable, fun)"

        assertEquals("Expected the head linked once in the header, got: $documentation", 1, """<a href="#arity-2">$head</a>""".toRegex(RegexOption.LITERAL).findAll(documentation).count())
        assertEquals("Expected the head in the header and in the title of its entry, once each, got: $documentation", 2, head.toRegex(RegexOption.LITERAL).findAll(documentation).count())
        assertEquals("Expected the other head of the entry kept, got: $documentation", 2, "reduce([h | t], fun)".toRegex(RegexOption.LITERAL).findAll(documentation).count())
    }

    /** A function of one arity shows a head written twice once. */
    fun testAHeadWrittenTwiceIsShownOnceForASingleArity() {
        myFixture.configureByText(
            "reducing_one.ex",
            """
            defmodule ReducingOne do
              @doc "Two."
              def reduce(enumerable, fun)
              def reduce([h | t], fun), do: {h, t, fun}
              def reduce(enumerable, fun), do: {enumerable, fun}

              def calls(a), do: red<caret>uce(a, a)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertEquals("Expected the repeated head once, got: $documentation", 1, "reduce(enumerable, fun)".toRegex(RegexOption.LITERAL).findAll(documentation).count())
        assertEquals("Expected the other head kept, got: $documentation", 1, "reduce([h | t], fun)".toRegex(RegexOption.LITERAL).findAll(documentation).count())
    }

    private fun assertOnlyTheVisibleArities(documentation: String) {
        assertTrue("Expected the visible arities, got: $documentation", "One." in documentation && "Four." in documentation)
        assertEquals("Expected a link per visible arity, got: $documentation", 2, linkedIds(documentation).distinct().size)
        assertFalse("Expected no entry for push(a, b, c), got: $documentation", "push(a, b, c)" in documentation)
        assertFalse("Expected no entry for push(a, b), got: $documentation", "push(a, b)" in documentation)
    }
}
