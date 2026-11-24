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

    /** `require` serves its `as:` name as `alias` does: `First` reaches the module the `require` names. */
    fun testRequireAsReachesTheModuleItNames() =
        assertTrue(resolvedTexts("require Target, as: First", "First").any { it.startsWith("defmodule Target") })

    /** `First.Sub` after an `alias` or `require` that gives `Target` the name `First` reaches `Target.Sub`. */
    fun testAliasAsNameReachesAModuleUnderTheModuleItNames() =
        assertCaretReaches(prefixedSource("alias Target, as: First"), "defmodule Target.Sub")

    fun testRequireAsNameReachesAModuleUnderTheModuleItNames() =
        assertCaretReaches(prefixedSource("require Target, as: First"), "defmodule Target.Sub")

    /** The module an `alias` or `require` names is read before the `as:` name it gives: a caret in it reaches the module only. */
    fun testTheModuleAnAliasAsNamesIsReachedWithoutTheAlias() =
        assertReachesOnlyTheModule("alias Foo.<caret>Bar, as: Foo", "defmodule Foo.Bar")

    fun testTheQualifierOfTheModuleAnAliasAsNamesIsReachedWithoutTheAlias() =
        assertReachesOnlyTheModule("alias <caret>Foo.Bar, as: Foo", "defmodule Foo do")

    fun testTheModuleARequireAsNamesIsReachedWithoutTheRequire() =
        assertReachesOnlyTheModule("require Foo.<caret>Bar, as: Foo", "defmodule Foo.Bar")

    /** `alias Foo, as: Foo` names itself: `Foo` after it stops at the recursion guard and reaches the module. */
    fun testAnAliasAsItsOwnNameReachesTheModule() =
        assertCaretReaches(
            """
            defmodule Foo do
              def f, do: :foo
            end

            defmodule User do
              alias Foo, as: Foo

              def g, do: <caret>Foo.f()
            end
            """.trimIndent(),
            "defmodule Foo",
        )

    /** `Refd.Sub` after `alias NS.Referenced` and `alias Referenced, as: Refd` reaches `NS.Referenced.Sub`. */
    fun testAliasAsOverAnAliasReachesAModuleUnderTheModuleItNames() {
        assertCaretReaches(
            """
            defmodule NS.Referenced do
            end

            defmodule NS.Referenced.Sub do
              def f, do: :sub
            end

            defmodule User do
              alias NS.Referenced
              alias Referenced, as: Refd

              def g, do: Refd.<caret>Sub.f()
            end
            """.trimIndent(),
            "defmodule NS.Referenced.Sub",
        )
    }

    /** `alias NS.Foo` comes before `alias Foo, as: M`, so `M` is `NS.Foo` though a top-level `Foo` is defined too. */
    fun testAliasAsOverAnAliasReachesTheAliasedModuleNotTheSameNamedOne() = assertAsReachesTheAliasedModule("alias")

    fun testRequireAsOverAnAliasReachesTheAliasedModuleNotTheSameNamedOne() = assertAsReachesTheAliasedModule("require")

    private fun assertAsReachesTheAliasedModule(directive: String) {
        assertCaretReaches(
            underAnAliasOfNsFoo("$directive Foo, as: M\n\ndef g, do: <caret>M.f()"),
            "defmodule NS.Foo", "defmodule Foo",
        )
    }

    /** `alias Foo.Bar` after `alias NS.Foo` is `NS.Foo.Bar`, though a top-level `Foo.Bar` is defined too. */
    fun testAnAliasOverAnAliasReachesTheAliasedModuleNotTheSameNamedOne() {
        assertCaretReaches(
            underAnAliasOfNsFoo("alias Foo.Bar\n\ndef g, do: <caret>Bar.f()", defines = "Foo.Bar"),
            "defmodule NS.Foo.Bar", "defmodule Foo.Bar",
        )
    }

    /** An `Elixir.`-rooted name is the module it says, so `alias Elixir.Foo, as: M` after `alias NS.Foo` is `Foo`. */
    fun testAnElixirRootedAliasAsOverAnAliasReachesTheModuleItSays() {
        assertCaretReaches(
            underAnAliasOfNsFoo("alias Elixir.Foo, as: M\n\ndef g, do: <caret>M.f()"),
            "defmodule Foo", "defmodule NS.Foo",
        )
    }

    /** A written `Elixir.Foo` is the module it says, so Go to Definition on it after `alias NS.Foo` reaches `Foo`. */
    fun testAnElixirRootedNameAfterAnAliasReachesTheModuleItSays() {
        assertCaretReaches(
            underAnAliasOfNsFoo("\ndef g, do: Elixir.<caret>Foo.f()"),
            "defmodule Foo", "defmodule NS.Foo",
        )
    }

    /** An `alias` of a module a `quote` unquotes reaches that module: the call names the one module it unquotes. */
    fun testAliasAsNameOfAnUnquotedModuleReachesIt() {
        assertCaretReaches(
            """
            defmodule Target do
              def f, do: :target
            end

            defmodule Macros do
              defmacro m do
                quote do
                  alias unquote(Target), as: First

                  def g, do: <caret>First.f()
                end
              end
            end
            """.trimIndent(),
            "defmodule Target",
        )
    }

    /** A file whose caret is on the alias `First.Sub` of `First.Sub.f()` after [call]. */
    private fun prefixedSource(call: String): String =
        """
        defmodule Target do
        end

        defmodule Target.Sub do
          def f, do: :sub
        end

        defmodule User do
          $call

          def g, do: First.<caret>Sub.f()
        end
        """.trimIndent()

    /** The caret in [directive] reaches [module], and no `alias` or `require`. */
    private fun assertReachesOnlyTheModule(directive: String, module: String) {
        assertCaretReaches(
            """
            defmodule Foo do
            end

            defmodule Foo.Bar do
            end

            defmodule User do
              $directive
            end
            """.trimIndent(),
            module, "alias ", "require ",
        )
    }

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

        return caretResolvedTexts()
    }

    /** The caret in [source] reaches a text starting with [reached], and none starting with any of [unreached]. */
    private fun assertCaretReaches(source: String, reached: String, vararg unreached: String) {
        myFixture.configureByText("alias.ex", source)

        val texts = caretResolvedTexts()

        assertTrue(texts.toString(), texts.any { it.startsWith(reached) })
        assertEquals(texts.toString(), emptyList<String>(), texts.filter { text -> unreached.any(text::startsWith) })
    }

    /** What the reference at the caret resolves to. */
    private fun caretResolvedTexts(): List<String> {
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

        return caretResolvedTexts().filter { it.startsWith("def ") }
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

    /** The call a `quote` unquotes is presented as the module it names, as `First` resolves to it. */
    fun testItemPresentationOfAsOfAnUnquotedModuleIsTheModule() {
        val first = alias("defmodule Target do\nend\n\nquote do\n  alias unquote(Target), as: <caret>First\nend")

        assertEquals("alias Target, as: First", ItemPresentation(first).presentableText)
    }

    private fun usageShortName(alias: QualifiableAlias): String? =
        ElementDescriptionUtil.getElementDescription(alias, UsageViewShortNameLocation.INSTANCE)

    private fun alias(code: String): QualifiableAlias {
        myFixture.configureByText("as.ex", "$code\n")

        return myFixture.caretAlias()
    }
}
