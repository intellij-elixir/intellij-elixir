package org.elixir_lang.reference.callable

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.declaration.ExpanderSettingTestCase

/**
 * https://github.com/intellij-elixir/intellij-elixir/issues/1613
 *
 * Cmd-Click on a usage of a function declared by `defdelegate` navigated nowhere, while the same
 * gesture on a `def` worked. Both halves are asserted because the report is a comparison between
 * them, and a `def` regression would otherwise read as this issue returning.
 *
 * The gesture and [PsiPolyVariantReference.multiResolve] disagree here and both are pinned: the
 * scope walk offers two valid results (the `defdelegate` head and the `to:` module's `def`), while
 * Go To Declaration navigates to the `def` alone. Asserting only the gesture would leave the
 * `defdelegate` head - the element the reporter asked to reach - unpinned.
 */
class Issue1613Test : ExpanderSettingTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/callable/issue_1613"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    /** The issue itself: the gesture navigates instead of doing nothing. */
    fun testQualifiedUsageOfDefdelegateNavigatesToTheDelegatedDef() {
        myFixture.configureByFile("qualified_usage_of_defdelegate.ex")

        val declarations = gotoDeclarationTextsAtCaret()
        assertEquals(
            "Go To Declaration from a defdelegate usage should land on the delegated def",
            listOf("def delegated(x), do: x"),
            declarations
        )
    }

    /** The `defdelegate` head declares `delegated/1` in `Delegator`, so it resolves as a declaration too. */
    fun testQualifiedUsageOfDefdelegateResolvesToBothTheHeadAndTheDelegatedDef() {
        myFixture.configureByFile("qualified_usage_of_defdelegate.ex")

        assertEquals(
            listOf("defdelegate delegated(x), to: DelegateTarget", "def delegated(x), do: x"),
            multiResolveTextsAtCaret()
        )
    }

    /** The comparison the report is written against - `def` was always fine and must stay fine. */
    fun testQualifiedUsageOfDefNavigatesToTheDef() {
        myFixture.configureByFile("qualified_usage_of_def.ex")

        assertEquals(listOf("def plain(x), do: x"), gotoDeclarationTextsAtCaret())
    }

    private fun gotoDeclarationTextsAtCaret(): List<String> =
        myFixture.gotoDeclarationTargetsAtCaret().orEmpty()
            .mapNotNull { it.destination }
            .map(::declarationText)

    private fun multiResolveTextsAtCaret(): List<String> {
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val reference = generateSequence(leaf) { it.parent }.mapNotNull { it.reference }.first()

        return (reference as PsiPolyVariantReference)
            .multiResolve(false)
            .filter { it.isValidResult }
            .mapNotNull { it.element }
            .map { it.text.trim() }
    }

    /**
     * The text of the outermost ancestor still on the destination's own line - enough to name which
     * declaration the gesture landed in without depending on a `Call` subtype.
     */
    private fun declarationText(destination: PsiElement): String =
        generateSequence(destination) { it.parent }
            .takeWhile { !it.text.contains('\n') }
            .last()
            .text
            .trim()
}
