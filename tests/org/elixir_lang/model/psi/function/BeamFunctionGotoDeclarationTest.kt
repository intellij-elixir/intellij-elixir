package org.elixir_lang.model.psi.function

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.code_insight.nonDeclarationUsageCountAtCaret
import java.io.File

/**
 * Behavioural Go To Declaration and Find Usages coverage for functions defined in decompiled BEAM modules, e.g.
 * `:queue.new/0` from the Erlang stdlib. Unlike source `def`s these resolve to `BeamCallDefinition` decompiled PSI,
 * so they exercise the branch of the call reference that navigates into the `.beam` mirror rather than a source clause.
 */
class BeamFunctionGotoDeclarationTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/function"

    override val ebinDirectory: File = ERLANG_STDLIB_EBIN

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testCtrlClickOnRemoteBeamFunctionChoosesGotoDeclaration() {
        myFixture.configureByFiles("beam_qualified_call_goto.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationNavigatesToRemoteBeamFunction() {
        myFixture.configureByFiles("beam_qualified_call_goto.ex")
        val target = myFixture.gotoDeclarationDestinationAtCaret()
        assertNotNull("Go To Declaration should navigate into the decompiled :queue module", target)
        assertTrue(
            "Should land in the decompiled queue.beam, not the source file (was ${target!!.containingFile.name})",
            target.containingFile.name.startsWith("queue")
        )
        assertTrue(
            "Should land on the decompiled `def new` definition (was '${target.text}')",
            target.text.contains("new")
        )
    }

    /** Capturing a decompiled function must navigate into the `.beam` mirror the same way calling it does. */
    fun testGoToDeclarationNavigatesFromCaptureToRemoteBeamFunction() {
        myFixture.configureByFiles("beam_capture_goto.ex")
        val target = myFixture.gotoDeclarationDestinationAtCaret()
        assertNotNull("Go To Declaration should navigate into the decompiled :queue module", target)
        assertTrue(
            "Should land in the decompiled queue.beam, not the source file (was ${target!!.containingFile.name})",
            target.containingFile.name.startsWith("queue")
        )
        assertTrue(
            "Should land on the decompiled `def new` definition (was '${target.text}')",
            target.text.contains("new")
        )
    }

    fun testFindUsagesFromRemoteBeamFunctionUsageFindsSourceUsages() {
        myFixture.configureByFiles("beam_qualified_call_find_usages.ex")
        assertEquals(
            "Find Usages from a `:queue.new()` call should find the two source call sites plus the within-beam " +
                "call site of `new/0` in the decompiled queue.beam",
            3,
            myFixture.nonDeclarationUsageCountAtCaret(project)
        )
    }

    fun testFindUsagesFromWithinBeamFunctionFindsSourceUsages() {
        myFixture.configureByFiles("beam_qualified_call_find_usages.ex")
        openBeamAndMoveCaretTo("queue.beam", "def new")
        assertEquals(
            "Find Usages on the `def new` clause inside the decompiled queue.beam should find the two source call " +
                "sites plus the within-beam call site of `new/0` - the same total as from a source `:queue.new()` " +
                "call of the same symbol",
            3,
            myFixture.nonDeclarationUsageCountAtCaret(project)
        )
    }

    fun testCtrlClickWithinBeamFunctionChoosesShowUsages() {
        myFixture.configureByFiles("beam_qualified_call_find_usages.ex")
        openBeamAndMoveCaretTo("queue.beam", "def new")
        myFixture.assertShowUsagesChosenAtCaret(
            "Ctrl+Click on the `def new` clause inside the decompiled queue.beam should choose show-usages"
        )
    }

    fun testGoToDeclarationFromWithinBeamFunctionUsageNavigatesToDefinition() {
        myFixture.configureByFiles("beam_qualified_call_find_usages.ex")
        // Caret on the `in_r` call in the body of `def cons(x, q), do: in_r(x, q)`; that is a usage of the
        // `in_r/2` function, defined in the same beam. `in_r/2` has several clauses, so Ctrl+Click offers a
        // chooser of declarations (outcome GTD) rather than jumping straight to one - exactly as it would for a
        // multi-clause source function. Before the fix the caret sat on the coarse `def cons` declaration and the
        // outcome was "show usages", so nothing navigated to `in_r`.
        openBeamAndMoveCaretTo("queue.beam", "do: in_r")
        myFixture.assertGotoDeclarationChosenAtCaret(
            "Ctrl+Click on the `in_r` call inside the decompiled queue.beam should navigate to its declaration"
        )
    }
}
