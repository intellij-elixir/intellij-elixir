package org.elixir_lang.refactoring

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.GtduNavigation
import org.elixir_lang.code_insight.gotoDeclarationLineAtCaret
import org.elixir_lang.code_insight.gtduNavigationAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret

/**
 * Renaming either side of a `defdelegate` changes only the name renamed: `as:` keeps the other side, so renaming the
 * delegation leaves its target alone and renaming the target leaves the delegation's public name alone. A call or
 * `apply/3` names the delegation, so it renames the delegation, while Go To from it follows `to:`.
 */
class DelegationRenameTest : PlatformTestCase() {
    private val source = """
        defmodule Target do
          def snoc(q, x), do: {q, x}
          def other(q), do: q
        end

        defmodule Delegator do
          defdelegate snoc(q, x), to: Target
          defdelegate aliased(q), to: Target, as: :other
          defdelegate lost(q), to: Missing
        end

        defmodule Caller do
          def calls(a, b), do: {Delegator.snoc(a, b), Target.snoc(a, b), Delegator.aliased(a), Target.other(a)}
          def applies(a, b), do: {apply(Delegator, :snoc, [a, b]), Delegator.lost(a)}
        end
    """.trimIndent()

    fun testRenamingADelegationPinsItsTargetWithAs() =
        assertRenamed(
            "defdelegate sn<caret>oc(q, x)",
            "renamed",
            "defdelegate snoc(q, x), to: Target" to "defdelegate renamed(q, x), to: Target, as: :snoc",
            "Delegator.snoc(a, b)" to "Delegator.renamed(a, b)",
            "apply(Delegator, :snoc" to "apply(Delegator, :renamed"
        )

    fun testRenamingFromACallRenamesTheDelegationItNames() =
        assertRenamed(
            "{Delegator.sn<caret>oc(a, b)",
            "renamed",
            "defdelegate snoc(q, x), to: Target" to "defdelegate renamed(q, x), to: Target, as: :snoc",
            "Delegator.snoc(a, b)" to "Delegator.renamed(a, b)",
            "apply(Delegator, :snoc" to "apply(Delegator, :renamed"
        )

    fun testRenamingFromAnApplyRenamesTheDelegationItNames() =
        assertRenamed(
            "apply(Delegator, :sn<caret>oc",
            "renamed",
            "defdelegate snoc(q, x), to: Target" to "defdelegate renamed(q, x), to: Target, as: :snoc",
            "Delegator.snoc(a, b)" to "Delegator.renamed(a, b)",
            "apply(Delegator, :snoc" to "apply(Delegator, :renamed"
        )

    fun testGoToDeclarationFromACallFollowsTheDelegation() {
        configure("{Delegator.sn<caret>oc(a, b)")

        assertEquals("def snoc(q, x), do: {q, x}", destinationLine())
    }

    /** A delegation with defaults fills them in and calls what it delegates to at its full arity, whichever arity it was used at. */
    fun testGoToDeclarationFromALowerArityOfADelegationWithDefaultsFollowsItAtItsFullArity() {
        val defaults = """
            defmodule Target do
              def snoc(q, x), do: {q, x}
            end

            defmodule Delegator do
              defdelegate snoc(q, x \\ nil), to: Target
            end

            defmodule Caller do
              def calls(a), do: {Delegator.snoc(a), apply(Delegator, :snoc, [a]), {Delegator, :snoc, 1}}
            end
        """.trimIndent()

        assertEquals(
            List(3) { "def snoc(q, x), do: {q, x}" },
            listOf("{Delegator.sn<caret>oc(a)", "apply(Delegator, :sn<caret>oc", "{Delegator, :sn<caret>oc, 1}").map { caretAt ->
                configure(caretAt, defaults)
                destinationLine()
            }
        )
    }

    /** A delegation with defaults calls its target at full arity, not at another arity the target also defines. */
    fun testGoToDeclarationFromALowerArityLandsOnTheFullArityNotAnother() {
        val source = """
            defmodule Target do
              def snoc(q), do: q
              def snoc(q, x), do: {q, x}
              def other(q), do: q
              def other(q, x), do: {q, x}
            end

            defmodule Delegator do
              defdelegate snoc(q, x \\ nil), to: Target
              defdelegate aliased(q, x \\ nil), to: Target, as: :other
            end

            defmodule Caller do
              def calls(a), do: {Delegator.snoc(a), Delegator.aliased(a)}
            end
        """.trimIndent()

        assertEquals(
            listOf("def snoc(q, x), do: {q, x}", "def other(q, x), do: {q, x}"),
            listOf("{Delegator.sn<caret>oc(a)", "Delegator.ali<caret>ased(a)").map { caretAt ->
                configure(caretAt, source)
                destinationLine()
            }
        )
    }

    /** A head whose arity is open passes every argument on, so its target is called at the arity used. */
    fun testGoToDeclarationFromACallOfAnOpenDelegationLandsOnTheTargetAtTheArityUsed() {
        val source = """
            defmodule Target do
              def f, do: :none
              def f(a, b, c), do: {a, b, c}
            end

            defmodule Delegator do
              defdelegate f(unquote_splicing(args)), to: Target
            end

            defmodule Caller do
              def calls(a), do: Delegator.f(a, a, a)
            end
        """.trimIndent()

        configure("Delegator.<caret>f(a, a, a)", source)

        assertEquals("def f(a, b, c), do: {a, b, c}", destinationLine())
    }

    fun testGoToDeclarationFromACallOfAnUnresolvableDelegationLandsOnIt() {
        configure("Delegator.lo<caret>st(a)")

        assertEquals("defdelegate lost(q), to: Missing", destinationLine())
    }

    fun testCtrlClickOnADelegationHeadShowsUsages() {
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        configure("defdelegate sn<caret>oc(q, x)")

        val navigation = myFixture.gtduNavigationAtCaret()

        assertTrue("Expected Show Usages, got $navigation", navigation is GtduNavigation.ShowUsages)
    }

    fun testRenamingADelegationWithAsKeepsItsAs() =
        assertRenamed(
            "defdelegate ali<caret>ased(q)",
            "renamed",
            "defdelegate aliased(q)" to "defdelegate renamed(q)",
            "Delegator.aliased(a)" to "Delegator.renamed(a)"
        )

    fun testRenamingATargetKeepsTheDelegationsNameWithAs() =
        assertRenamed(
            "def sn<caret>oc(q, x)",
            "fresh",
            "def snoc(q, x)" to "def fresh(q, x)",
            "Target.snoc(a, b)" to "Target.fresh(a, b)",
            "defdelegate snoc(q, x), to: Target" to "defdelegate snoc(q, x), to: Target, as: :fresh"
        )

    fun testRenamingATargetUpdatesADelegationsAs() =
        assertRenamed(
            "def ot<caret>her(q)",
            "fresh",
            "def other(q)" to "def fresh(q)",
            "Target.other(a)" to "Target.fresh(a)",
            "as: :other" to "as: :fresh"
        )

    /** Options written as a list take `as:` inside it: `defdelegate/2` has no third argument. */
    fun testRenamingADelegationWithListedOptionsPinsItsTargetInsideTheList() =
        assertRenamed(
            "defdelegate sn<caret>oc(q, x)",
            "renamed",
            "defdelegate snoc(q, x), [to: Target]" to "defdelegate renamed(q, x), [to: Target, as: :snoc]",
            "Delegator.snoc(a, b)" to "Delegator.renamed(a, b)",
            "apply(Delegator, :snoc" to "apply(Delegator, :renamed",
            source = listedOptions
        )

    fun testRenamingATargetPinsADelegationWithListedOptionsInsideTheList() =
        assertRenamed(
            "def sn<caret>oc(q, x)",
            "fresh",
            "def snoc(q, x)" to "def fresh(q, x)",
            "Target.snoc(a, b)" to "Target.fresh(a, b)",
            "defdelegate snoc(q, x), [to: Target]" to "defdelegate snoc(q, x), [to: Target, as: :fresh]",
            source = listedOptions
        )

    /** `as:` goes straight after the last option, so a trailing comma or a line break before `]` stays as written. */
    fun testRenamingADelegationWithATrailingCommaOrMultilineOptionsKeepsThemValid() {
        assertRenamed(
            "defdelegate sn<caret>oc(q, x)",
            "renamed",
            "defdelegate snoc(q, x), [to: Target,]" to "defdelegate renamed(q, x), [to: Target, as: :snoc,]",
            "Delegator.snoc(a, b)" to "Delegator.renamed(a, b)",
            "apply(Delegator, :snoc" to "apply(Delegator, :renamed",
            source = listedOptions.replace("[to: Target]", "[to: Target,]")
        )
        assertRenamed(
            "defdelegate sn<caret>oc(q, x)",
            "renamed",
            "defdelegate snoc(q, x), [\n    to: Target\n  ]" to "defdelegate renamed(q, x), [\n    to: Target, as: :snoc\n  ]",
            "Delegator.snoc(a, b)" to "Delegator.renamed(a, b)",
            "apply(Delegator, :snoc" to "apply(Delegator, :renamed",
            source = listedOptions.replace("[to: Target]", "[\n    to: Target\n  ]")
        )
    }

    /** An `as:` that is not a literal already says what it delegates to, so a rename adds no second one. */
    fun testRenamingADelegationWithANonLiteralAsKeepsItsAs() =
        assertRenamed(
            "defdelegate dyn<caret>amic(q)",
            "renamed",
            "defdelegate dynamic(q)" to "defdelegate renamed(q)",
            "Delegator.dynamic(a)" to "Delegator.renamed(a)",
            source = source
                .replace("  defdelegate lost(q), to: Missing\n", "  defdelegate lost(q), to: Missing\n  defdelegate dynamic(q), to: Target, as: @target\n")
                .replace("Delegator.lost(a)}", "Delegator.lost(a), Delegator.dynamic(a)}")
        )

    /** Each arity a head with defaults declares is the one function renamed, so an import key naming any of them is. */
    fun testRenamingADelegationWithDefaultsRenamesImportKeysAtEveryArity() =
        assertRenamed(
            "sn<caret>oc(a, b)",
            "renamed",
            "defdelegate snoc(q, x \\\\ nil), to: Target" to "defdelegate renamed(q, x \\\\ nil), to: Target, as: :snoc",
            "only: [snoc: 1]" to "only: [renamed: 1]",
            "do: snoc(a)" to "do: renamed(a)",
            "except: [snoc: 1]" to "except: [renamed: 1]",
            "do: snoc(a, b)" to "do: renamed(a, b)",
            source = """
                defmodule Target do
                  def snoc(q, x), do: {q, x}
                end

                defmodule Delegator do
                  defdelegate snoc(q, x \\ nil), to: Target
                end

                defmodule OnlyCaller do
                  import Delegator, only: [snoc: 1]

                  def calls(a), do: snoc(a)
                end

                defmodule ExceptCaller do
                  import Delegator, except: [snoc: 1]

                  def calls(a, b), do: snoc(a, b)
                end
            """.trimIndent()
        )

    private val listedOptions = source.replace("defdelegate snoc(q, x), to: Target", "defdelegate snoc(q, x), [to: Target]")

    private fun configure(caretAt: String, text: String = source) {
        myFixture.configureByText("delegation_rename.ex", text.replace(caretAt.replace("<caret>", ""), caretAt))
    }

    private fun destinationLine(): String? = myFixture.gotoDeclarationLineAtCaret()

    private fun assertRenamed(caretAt: String, newName: String, vararg edits: Pair<String, String>, source: String = this.source) {
        configure(caretAt, source)

        myFixture.renameTargetAtCaret(newName)

        val expected = edits.fold(source) { text, (before, after) ->
            assertEquals("`$before` must occur once", 1, text.split(before).size - 1)
            text.replace(before, after)
        }
        assertEquals(expected, myFixture.editor.document.text)
    }
}
