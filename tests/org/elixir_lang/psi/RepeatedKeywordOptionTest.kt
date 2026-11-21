package org.elixir_lang.psi

import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewShortNameLocation
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.navigation.item_presentation.quotePresentation
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.impl.qualifiable_alias.ItemPresentation
import org.elixir_lang.reference.module.UnaliasedName
import org.elixir_lang.structure_view.element.Delegation
import org.elixir_lang.structure_view.structureViewElements

/**
 * A keyword option given twice in one call is read from its first pair, as `Keyword.get/2` reads it and as
 * `elixir_aliases:alias/6`, `elixir_expand`'s `quote`, `Kernel.Utils.defdelegate_all/3` and `Protocol.__impl__/4` read
 * theirs.
 */
class RepeatedKeywordOptionTest : ExpanderSettingTestCase() {
    // alias and require

    fun testAliasAsGivenTwiceAliasesTheFirst() = assertAliasReadsFirst("alias Target, as: First, as: Second")

    fun testRequireAsGivenTwiceAliasesTheFirst() = assertAliasReadsFirst("require Target, as: First, as: Second")

    fun testAliasAsGivenOnceAliasesIt() = assertTrue(resolvedTexts("alias Target, as: First", "First").contains("alias Target, as: First"))

    fun testRequireAsGivenOnceAliasesIt() = assertTrue(resolvedTexts("require Target, as: First", "First").contains("require Target, as: First"))

    /** `First` reaches the call that names it, and `Second` reaches nothing. */
    private fun assertAliasReadsFirst(call: String) {
        assertTrue(resolvedTexts(call, "First").contains(call))
        assertEquals(emptyList<String>(), resolvedTexts(call, "Second"))
    }

    /** What the alias [qualifier] of `[qualifier].f()` resolves to after [call]. */
    private fun resolvedTexts(call: String, qualifier: String): List<String> {
        myFixture.configureByText(
            "alias.ex",
            """
            defmodule Target do
              def f, do: :target
            end

            defmodule User do
              $call

              def g, do: <caret>$qualifier.f()
            end
            """.trimIndent()
        )

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as? PsiPolyVariantReference

        return reference?.multiResolve(false).orEmpty().mapNotNull { it.element?.text }
    }

    // defimpl

    fun testDefimplForGivenTwiceIsForTheFirst() = assertEquals("P.A", implementationName("defimpl P, for: A, for: B do\nend\n"))

    fun testDefimplForGivenOnceIsForIt() = assertEquals("P.A", implementationName("defimpl P, for: A do\nend\n"))

    private fun implementationName(code: String): String? {
        myFixture.configureByText("impl.ex", code)

        val defimpl = PsiTreeUtil
            .findChildrenOfType(myFixture.file, Call::class.java)
            .single { Implementation.`is`(it) }

        return Implementation.name(SyntacticCall.of(defimpl))
    }

    // defdelegate

    fun testDefdelegateToGivenTwiceDelegatesToTheFirst() =
        assertEquals(listOf("def f(x), do: :from_a"), delegatedTargets("defdelegate f(x), to: A, to: B"))

    fun testDefdelegateAsGivenTwiceDelegatesToTheFirst() =
        assertEquals(listOf("def g(x), do: :from_g"), delegatedTargets("defdelegate f(x), to: A, as: :g, as: :h"))

    fun testDefdelegateToGivenOnceDelegatesToIt() =
        assertEquals(listOf("def f(x), do: :from_a"), delegatedTargets("defdelegate f(x), to: A"))

    private fun delegatedTargets(defdelegate: String): List<String> {
        myFixture.configureByText(
            "delegate.ex",
            """
            defmodule A do
              def f(x), do: :from_a
              def g(x), do: :from_g
              def h(x), do: :from_h
            end

            defmodule B do
              def f(x), do: :from_b
            end

            defmodule D do
              $defdelegate
            end

            defmodule U do
              def u, do: D.f<caret>(1)
            end
            """.trimIndent()
        )

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference

        return reference.multiResolve(false).mapNotNull { it.element?.text }.filter { it.startsWith("def ") }
    }

    fun testDefdelegateAppendFirstGivenTwiceIsTheFirst() {
        assertTrue(delegation("defdelegate f(x), to: A, append_first: true, append_first: false").appendFirst())
    }

    fun testDefdelegateAppendFirstGivenOnceIsIt() {
        assertTrue(delegation("defdelegate f(x), to: A, append_first: true").appendFirst())
        assertFalse(delegation("defdelegate f(x), to: A, append_first: false").appendFirst())
    }

    private fun delegation(defdelegate: String): Delegation {
        myFixture.configureByText("delegation.ex", "defmodule D do\n  $defdelegate\nend\n")

        return structureViewElements<Delegation>(myFixture.file as ElixirFile).single()
    }

    // quote

    fun testQuoteLocationGivenTwiceIsTheFirst() =
        assertEquals("quote location: :keep", myFixture.quotePresentation("quote location: :keep, location: :other do\n end"))

    fun testQuoteUnquoteGivenTwiceIsTheFirst() =
        assertEquals("quote unquote: false", myFixture.quotePresentation("quote unquote: false, unquote: true do\n end"))

    fun testQuoteBindQuotedGivenTwiceBindsTheFirst() {
        assertBindsKey(
            "quote bind_quoted: [a: 1], bind_quoted: [b: 2] do\n      <caret>a\n    end",
            expectedKey = "a"
        )
    }

    fun testQuoteBindQuotedGivenOnceBindsIt() {
        assertBindsKey("quote bind_quoted: [a: 1] do\n      <caret>a\n    end", expectedKey = "a")
    }

    fun testQuoteBindQuotedIsNotBoundBySecond() {
        assertBindsKey("quote bind_quoted: [a: 1], bind_quoted: [b: 2] do\n      <caret>b\n    end", expectedKey = null)
    }

    fun testQuoteUnquoteOnFirstTurnsUnquoteOnInBody() {
        assertBindsKey(
            "quote bind_quoted: [a: 1], unquote: true, unquote: false do\n      unquote(<caret>a)\n    end",
            expectedKey = null
        )
    }

    fun testQuoteUnquoteOffFirstLeavesKeysBoundInUnquote() {
        assertBindsKey(
            "quote bind_quoted: [a: 1], unquote: false, unquote: true do\n      unquote(<caret>a)\n    end",
            expectedKey = "a"
        )
    }

    /** Plugin's two `do:` readers agree on code Elixir rejects: the first `x` is bound, not the second. */
    fun testQuoteDoGivenTwiceBindsInTheFirst() {
        assertBindsKey("quote bind_quoted: [x: 1], do: <caret>x, do: x", expectedKey = "x")
    }

    fun testQuoteDoGivenTwiceDoesNotBindInTheSecond() {
        assertBindsKey("quote bind_quoted: [x: 1], do: x, do: <caret>x", expectedKey = null)
    }

    private fun assertBindsKey(quote: String, expectedKey: String?) {
        myFixture.configureByText("bind.ex", "defmodule M do\n  def f do\n    $quote\n  end\nend\n")

        val resolved = myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve()

        if (expectedKey != null) {
            assertInstanceOf(resolved, ElixirKeywordKey::class.java)
            assertEquals(expectedKey, resolved!!.text)
        } else {
            assertFalse("resolved to ${resolved?.text}", resolved is ElixirKeywordKey)
        }
    }

    // the `as:` readers

    fun testUnaliasedNameOfFirstAsIsTheModule() {
        assertEquals("Target", UnaliasedName.unaliasedName(alias("alias Target, as: <caret>First, as: Second")))
    }

    fun testUnaliasedNameOfSecondAsIsNotTheModule() {
        assertNull(UnaliasedName.unaliasedName(alias("alias Target, as: First, as: <caret>Second")))
    }

    fun testUsageShortNameOfFirstAsBeginsWithAs() {
        assertEquals("as: First", usageShortName(alias("alias Target, as: <caret>First, as: Second")))
    }

    fun testUsageShortNameOfSecondAsDoesNotBeginWithAs() {
        assertEquals("Second", usageShortName(alias("alias Target, as: First, as: <caret>Second")))
    }

    fun testItemPresentationOfFirstAsIsTheAlias() {
        assertEquals(
            "alias Target, as: First",
            ItemPresentation(alias("alias Target, as: <caret>First, as: Second")).presentableText
        )
    }

    fun testItemPresentationOfSecondAsIsNotAnAlias() {
        assertNull(ItemPresentation(alias("alias Target, as: First, as: <caret>Second")).presentableText)
    }

    fun testAsGivenOnceIsAnAlias() {
        val first = alias("alias Target, as: <caret>First")

        assertEquals("Target", UnaliasedName.unaliasedName(first))
        assertEquals("as: First", usageShortName(first))
        assertEquals("alias Target, as: First", ItemPresentation(first).presentableText)
    }

    private fun usageShortName(alias: QualifiableAlias): String? =
        ElementDescriptionUtil.getElementDescription(alias, UsageViewShortNameLocation.INSTANCE)

    private fun alias(code: String): QualifiableAlias {
        myFixture.configureByText("as.ex", "$code\n")

        return myFixture.caretAlias()
    }
}
