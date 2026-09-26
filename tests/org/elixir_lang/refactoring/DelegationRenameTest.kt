package org.elixir_lang.refactoring

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.util.TextRange
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.GtduNavigation
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
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

    private val listedOptions = source.replace("defdelegate snoc(q, x), to: Target", "defdelegate snoc(q, x), [to: Target]")

    private fun configure(caretAt: String, text: String = source) {
        myFixture.configureByText("delegation_rename.ex", text.replace(caretAt.replace("<caret>", ""), caretAt))
    }

    private fun destinationLine(): String? =
        myFixture.gotoDeclarationDestinationAtCaret()?.let { destination ->
            val document = myFixture.editor.document
            val line = document.getLineNumber(destination.textOffset)
            document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
        }

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
