package org.elixir_lang.model.psi.function

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.PsiSymbolReferenceService
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.assertNoNavigationAtCaret
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.structure_view.element.Delegation

/**
 * Behavior-level tests for reverse navigation: "Go To Declaration" on a call site navigates to
 * the `def` that defines the function.
 *
 * Mirrors [org.elixir_lang.model.psi.protocol.ProtocolImplGotoDeclarationTest] but for regular module functions rather than protocol
 * implementations.
 */
@Suppress("UnstableApiUsage")
class FunctionGotoDeclarationTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/function"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    /** Ctrl-Click on a call site - an element with a symbol reference - should choose Go To Declaration. */
    fun testCtrlClickOnCallSiteChoosesGotoDeclaration() {
        myFixture.configureByFiles("goto_declaration.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    /** The Go To Declaration action moves the caret onto the `def` name in the same module. */
    fun testGoToDeclarationNavigatesToFunctionDef() {
        myFixture.configureByFiles("goto_declaration.ex")
        myFixture.assertGotoDeclarationLandsIn("perform", "a def clause") { CallDefinitionClause.`is`(it) }
    }

    /** A `@spec` above a `defdelegate` names the function the delegation defines, so Go To Declaration lands on it. */
    fun testGoToDeclarationFromSpecLandsOnDelegation() {
        myFixture.configureByFiles("goto_declaration_spec_delegation.ex")
        myFixture.assertGotoDeclarationLandsIn("size", "a defdelegate") { Delegation.`is`(it) }
    }

    /** The reference at the call site must resolve to at least one [FunctionSymbol]. */
    fun testCallSiteReferenceResolvesToFunctionSymbol() {
        myFixture.configureByFiles("goto_declaration.ex")
        val callElement = myFixture.enclosingCallAtCaret { !CallDefinitionClause.`is`(it) }
        assertNotNull("Call site element at caret should exist", callElement)

        val references = PsiSymbolReferenceService.getService().getReferences(callElement!!)
        assertTrue("Call site should have at least one symbol reference", references.isNotEmpty())

        val resolved = references.flatMap { it.resolveReference() }
        assertTrue("Reference should resolve to at least one FunctionSymbol", resolved.any { it is FunctionSymbol })
    }

    fun testMultiClauseTargetsHaveDistinctPresentation() {
        myFixture.configureByFiles("choose_declaration_multi_clause.ex")
        val callElement = myFixture.enclosingCallAtCaret { !CallDefinitionClause.`is`(it) }
        assertNotNull("Call site element at caret should exist", callElement)

        val references = PsiSymbolReferenceService.getService().getReferences(callElement!!)
        val symbols = references
            .flatMap { it.resolveReference() }
            .filterIsInstance<FunctionSymbol>()

        assertTrue("Expected multiple declaration targets for a multi-clause function", symbols.size >= 2)

        val presentationTexts = symbols.map { it.presentation().presentableText }.distinct()
        assertEquals(
            "Each resolved multi-clause target should have a distinguishable chooser row",
            symbols.size,
            presentationTexts.size
        )
    }

    fun testGoToDeclarationNavigatesToPrivateFunctionDef() {
        myFixture.configureByFiles("goto_declaration_private.ex")
        myFixture.assertGotoDeclarationLandsIn("perform", "a defp clause") { CallDefinitionClause.`is`(it) }
    }

    fun testGoToDeclarationNavigatesToMacroDef() {
        myFixture.configureByFiles("goto_declaration_macro.ex")
        myFixture.assertGotoDeclarationLandsIn("perform", "a defmacro clause") { CallDefinitionClause.`is`(it) }
    }

    /**
     * A capture, `&Mod.fun/arity`, must navigate to the same `def` the equivalent qualified call does.
     * The capture carries its arity in the source rather than in an argument list, so it reaches the
     * resolver by a different route and needs its own case; the call below is the control.
     */
    fun testGoToDeclarationNavigatesFromQualifiedCapture() {
        myFixture.configureByFiles("goto_declaration_qualified_capture.ex", "goto_declaration_capture_referenced.ex")
        myFixture.assertGotoDeclarationLandsIn("changeset", "a def clause") { CallDefinitionClause.`is`(it) }
    }

    fun testGoToDeclarationNavigatesFromQualifiedCall() {
        myFixture.configureByFiles("goto_declaration_qualified_call.ex", "goto_declaration_capture_referenced.ex")
        myFixture.assertGotoDeclarationLandsIn("changeset", "a def clause") { CallDefinitionClause.`is`(it) }
    }

    fun testCtrlClickOnErlangQualifiedCallDoesNothingYet() {
        myFixture.configureByFiles("goto_declaration_erlang_qualified_call.ex")
        myFixture.assertNoNavigationAtCaret()
    }
}
