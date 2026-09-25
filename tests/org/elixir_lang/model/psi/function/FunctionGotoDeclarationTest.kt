package org.elixir_lang.model.psi.function

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.util.TextRange
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.assertNoNavigationAtCaret
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.psi.CallDefinitionClause

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

    /**
     * A bodiless head with defaults and the clauses after it are one function, and the clauses are the code that runs, so
     * Go To from a use at either arity offers the head and every clause.
     */
    fun testGoToDeclarationOffersABodilessHeadAndEveryClause() {
        val source = """
            defmodule Definer do
              @spec snoc(term) :: term
              @spec snoc(term, term) :: term
              def snoc(a \\ nil, b \\ nil)
              def snoc(a, b) when is_nil(a), do: {a, b}
              def snoc(a, b), do: {a, b}

              def local(a), do: snoc(a)
            end

            defmodule Caller do
              import Definer

              def calls(a, b), do: {Definer.snoc(a, b), snoc(a), &Definer.snoc/1, {Definer, :snoc, 1}}
            end
        """.trimIndent()
        val expected = listOf(
            "def snoc(a \\\\ nil, b \\\\ nil)",
            "def snoc(a, b) when is_nil(a), do: {a, b}",
            "def snoc(a, b), do: {a, b}"
        )
        val uses = listOf(
            "local" to "do: sn<caret>oc(a)\nend",
            "qualified" to "Definer.sn<caret>oc(a, b)",
            "imported" to "{Definer.snoc(a, b), sn<caret>oc(a)",
            "capture" to "&Definer.sn<caret>oc/1",
            "MFA" to "{Definer, :sn<caret>oc, 1}",
            "@spec" to "@spec sn<caret>oc(term) :: term"
        )

        assertEquals(
            uses.associate { (use, _) -> use to expected },
            uses.associate { (use, caretAt) ->
                myFixture.configureByText("head_and_clauses.ex", source.replace(caretAt.replace("<caret>", ""), caretAt))
                val document = myFixture.editor.document

                use to myFixture.gotoDeclarationTargetsAtCaret().orEmpty()
                    .mapNotNull { it.destination }
                    .map { destination ->
                        val line = document.getLineNumber(destination.textOffset)
                        document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
                    }
                    .sorted()
            }
        )
    }

    /** A module attribute between a head and its clauses does not part them. */
    fun testGoToDeclarationOffersTheClausesAfterAModuleAttribute() {
        myFixture.configureByText(
            "attribute_between.ex",
            """
            defmodule Definer do
              def snoc(a \\ nil, b \\ nil)
              @compile {:inline, snoc: 2}
              def snoc(a, b), do: {a, b}

              def local(a), do: sn<caret>oc(a)
            end
            """.trimIndent()
        )
        val document = myFixture.editor.document

        assertEquals(
            listOf("def snoc(a \\\\ nil, b \\\\ nil)", "def snoc(a, b), do: {a, b}"),
            myFixture.gotoDeclarationTargetsAtCaret().orEmpty()
                .mapNotNull { it.destination }
                .map { destination ->
                    val line = document.getLineNumber(destination.textOffset)
                    document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
                }
                .sorted()
        )
    }

    /** A head and its clauses are one function, so an MFA atom naming it has one search target, as a call does. */
    fun testAnMfaAtomNamingAHeadAndClausesHasOneSearchTarget() {
        myFixture.configureByText(
            "one_target.ex",
            """
            defmodule Definer do
              def snoc(a \\ nil, b \\ nil)
              def snoc(a, b) when is_nil(a), do: {a, b}
              def snoc(a, b), do: {a, b}
            end

            defmodule Caller do
              def calls(a), do: {Definer.snoc(a), {Definer, :sn<caret>oc, 1}}
            end
            """.trimIndent()
        )

        assertEquals(
            listOf("def snoc(a \\\\ nil, b \\\\ nil)"),
            com.intellij.find.usages.impl.searchTargets(myFixture.file, myFixture.caretOffset).map { it.presentation().presentableText }
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
