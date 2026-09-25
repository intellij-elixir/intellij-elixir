package org.elixir_lang.code_insight

import org.elixir_lang.PlatformTestCase

/** The gestures that ask a call's `resolve()` agree with Go To Declaration at a call of a delegation. */
class DelegationCallGestureTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/code_insight/delegation_call"

    /** Ctrl+Click goes where Ctrl+B goes, with no legacy target beside the symbol's. */
    fun testCtrlClickGoesWhereGoToDeclarationGoes() {
        myFixture.configureByFiles("delegation_call.ex")
        val goToDeclaration = myFixture.gotoDeclarationLinesAtCaret()

        val navigation = myFixture.gtduNavigationAtCaret()

        assertTrue("Ctrl+Click chose $navigation", navigation is GtduNavigation.GotoDeclaration)
        assertEquals(
            goToDeclaration,
            (navigation as GtduNavigation.GotoDeclaration).targets.mapNotNull { target ->
                target.destination?.let { myFixture.lineAt(it.textOffset) }
            }
        )
    }

    /** Highlight Usages marks the delegation's name and each call of it, not what it delegates to. */
    fun testHighlightUsagesMarksTheDelegationAndItsCalls() {
        val highlighted = myFixture.testHighlightUsages("delegation_call.ex")
        val document = myFixture.editor.document

        // Line 6 is the `defdelegate`, line 10 the two calls; `Target`'s `def` on line 2 is another function.
        assertEquals(
            listOf(6 to "snoc", 10 to "snoc", 10 to "snoc"),
            highlighted.sortedBy { it.startOffset }.map { highlighter ->
                document.getLineNumber(highlighter.startOffset) + 1 to
                    document.charsSequence.subSequence(highlighter.startOffset, highlighter.endOffset).toString()
            }
        )
    }
}
