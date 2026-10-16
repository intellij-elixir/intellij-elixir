package org.elixir_lang.model.psi.protocol

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Protocol

/**
 * Behavior-level tests for reverse navigation: "Go To Declaration" on an implementing `def`/`defmacro`
 * navigates to the protocol function `def` it implements.
 *
 * Assertions are on IDE behavior only (the Go To Declaration action, and resolution through the
 * platform's [PsiSymbolReferenceService]) - never on internal resolver classes - so they survive
 * refactoring of the implementation.
 */
@Suppress("UnstableApiUsage")
class ProtocolImplGotoDeclarationTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/protocol"

    override fun setUp() {
        super.setUp()
        // The Go To Declaration action needs a real DataContext (see platform SearchTargetTest).
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    /** Flagship end-to-end: the action moves the caret onto the protocol function `def` name. */
    @RequiresReadLock
    fun testGoToDeclarationNavigatesToProtocolFunction() {
        myFixture.configureByFiles("goto_impl_use_injected.ex")

        val enclosingClause = myFixture.assertGotoDeclarationLandsIn(
            "perform",
            "a def/defmacro clause"
        ) { CallDefinitionClause.`is`(it) }
        val defprotocol = CallDefinitionClause.enclosingModularMacroCall(enclosingClause)
        assertNotNull("Expected the clause to be inside a defprotocol", defprotocol)
        assertTrue("Expected the enclosing macro to be a defprotocol", Protocol.`is`(defprotocol!!))
    }

    /**
     * Ctrl-Click decision on an implementing `def` - an element with a symbol reference -
     * the "Go To Declaration or Usages" handler that Ctrl-Click uses
     * (`GotoDeclarationAction implements CtrlMouseAction` -> `GotoDeclarationOrUsageHandler2`) chooses
     * **Go To Declaration** rather than Show Usages or doing nothing.
     */
    fun testCtrlClickOnImplementingDefChoosesGotoDeclaration() {
        myFixture.configureByFiles("goto_impl_use_injected.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    /**
     * The reference on an implementing `def` must resolve to the protocol function symbol.
     * We use the platform's `PsiSymbolReferenceService` which queries our reference providers.
     */
    fun testImplementingDefReferenceResolvesToProtocolFunction() {
        myFixture.configureByFiles("goto_impl_use_injected.ex")
        val defClause = myFixture.enclosingCallAtCaret { CallDefinitionClause.`is`(it) }
        assertNotNull("Implementing def clause at caret should exist", defClause)
        val references = PsiSymbolReferenceService.getService().getReferences(defClause!!)
        assertTrue("Implementing def should have at least one symbol reference", references.isNotEmpty())

        val resolved = references.flatMap { it.resolveReference() }
        assertTrue("Reference should resolve to at least one symbol", resolved.isNotEmpty())
    }

    fun testNonImplementingDefReferencesNothing() {
        myFixture.configureByFiles("goto_non_implementing.ex")
        // Move caret to the non-matching def
        val offset = myFixture.file.text.indexOf("other_function") + "other".length
        val element = myFixture.file.findElementAt(offset)
        assertNotNull("Element at offset should exist", element)

        val references = PsiSymbolReferenceService.getService().getReferences(element!!)
        // The ref provider should not attach a reference to a def that doesn't match the protocol function
        assertTrue("Non-matching def should have no symbol references", references.isEmpty())
    }

    /** A delegation that implements nothing declares itself: Ctrl+Click shows its usages and Find Usages lists its call. */
    fun testNonImplementingDelegationActsOnItsOwnSymbol() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def helper(x), do: x\nend\n")
        myFixture.configureByText(
            "p.ex",
            "defprotocol P do\n  def f(x)\nend\n\ndefimpl P, for: Integer do\n  def f(x), do: helper(x)\n" +
                "  defdelegate hel<caret>per(x), to: T\nend\n"
        )

        myFixture.assertShowUsagesChosenAtCaret()
        assertEquals(
            listOf("p.ex:6"),
            myFixture.psiUsagesAtCaret(project).filterNot { it.declaration }.map { usage ->
                "${usage.file.name}:${usage.file.text.take(usage.range.startOffset).count { it == '\n' } + 1}"
            }
        )
    }
}
