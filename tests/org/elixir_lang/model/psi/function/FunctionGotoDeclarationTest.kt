package org.elixir_lang.model.psi.function

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.PsiSymbolReferenceService
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

    /** A `@spec` above a `defdelegate` names the delegation, whose Go To Declaration lands on what it delegates to. */
    fun testGoToDeclarationFromSpecLandsOnTheDelegationsTarget() {
        myFixture.configureByFiles("goto_declaration_spec_delegation.ex")
        myFixture.assertGotoDeclarationLandsIn("count", "Counter's def") { CallDefinitionClause.`is`(it) }
    }

    /** A use of a delegation lands on every clause of what it delegates to, as a call of that function does. */
    fun testGoToDeclarationFromAUseOfADelegationLandsOnEveryTargetClause() {
        val target = "defmodule T do\n  def f(0), do: 0\n  def f(x), do: x\nend\n"
        myFixture.addFileToProject("t.ex", target)
        myFixture.addFileToProject("m.ex", "defmodule M do\n  defdelegate f(x), to: T\nend\n")
        myFixture.configureByText("u.ex", "defmodule U do\n  def run, do: M.<caret>f(1)\nend\n")

        assertEquals(
            listOf("t.ex" to target.indexOf("f(0)"), "t.ex" to target.indexOf("f(x)")),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.containingFile?.name to it.destination?.textOffset }
        )
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

    /** A one-line `do:` body is the function's body, as a `do` block is, not part of its head. */
    fun testGoToDeclarationFromABareCallInAOneLineBodyLandsOnItsDef() =
        assertLandsOn("def foo", "defmodule M do\n  def foo, do: 1\n  def name, do: fo<caret>o\nend\n")

    fun testGoToDeclarationFromABareCallInAOneLineBodyOfAHeadWithParametersLandsOnItsDef() =
        assertLandsOn("def foo", "defmodule M do\n  def foo, do: 1\n  def name(x), do: fo<caret>o\nend\n")

    fun testGoToDeclarationFromABareCallInAOneLineIfLandsOnItsDef() =
        assertLandsOn("def foo", "defmodule M do\n  def foo, do: 1\n\n  def name(x) do\n    if x, do: fo<caret>o\n  end\nend\n")

    fun testGoToDeclarationFromABareCallInAOneLineElseLandsOnItsDef() =
        assertLandsOn("def foo", "defmodule M do\n  def foo, do: 1\n\n  def name(x) do\n    if x, do: 1, else: fo<caret>o\n  end\nend\n")

    /** The head's parameters are still parameters, so a use of one in the body lands on it. */
    fun testGoToDeclarationFromAParameterInAOneLineBodyLandsOnTheParameter() =
        assertLandsOn("name(x", "defmodule M do\n  def name(x), do: <caret>x\nend\n", "name(".length)

    /** A parameter shadows a function of the same name, so its use lands on the parameter alone. */
    fun testGoToDeclarationFromAParameterNamedAsAFunctionInAOneLineBodyLandsOnTheParameter() =
        assertLandsOn("name(x", "defmodule M do\n  def x, do: 1\n  def name(x), do: <caret>x\nend\n", "name(".length)

    fun testGoToDeclarationFromAParameterNamedAsAFunctionInABodyLandsOnTheParameter() =
        assertLandsOn("name(x", "defmodule M do\n  def x, do: 1\n\n  def name(x) do\n    <caret>x\n  end\nend\n", "name(".length)

    /** A piped name is a call, `x(v)`, whatever variable shares its name, so it lands on the function alone. */
    fun testGoToDeclarationFromAPipedNameNamedAsAParameterLandsOnTheFunction() =
        assertLandsOn("def x", "defmodule M do\n  def x(a), do: a\n  def name(x, v), do: v |> <caret>x\nend\n")

    /** A piped name in a match is a call too. */
    fun testGoToDeclarationFromAPipedNameInAMatchNamedAsAParameterLandsOnTheFunction() =
        assertLandsOn("def x", "defmodule M do\n  def x(a), do: a\n\n  def name(x, v) do\n    y = v |> <caret>x\n    y\n  end\nend\n")

    /** A captured name is the function's, `&x/1`, whatever variable shares its name. */
    fun testGoToDeclarationFromACapturedNameNamedAsAParameterLandsOnTheFunction() =
        assertLandsOn("def x", "defmodule M do\n  def x(a), do: a\n  def name(x), do: &<caret>x/1\nend\n")

    /** A name with a `do` block is a call, `x(do: 1)`, whatever variable shares its name. */
    fun testGoToDeclarationFromANameWithADoBlockNamedAsAParameterLandsOnTheFunction() =
        assertLandsOn("def x", "defmodule M do\n  def x(a), do: a\n\n  def name(x) do\n    <caret>x do\n      1\n    end\n  end\nend\n")

    /** A read on the right of `=` binds nothing, so a bare name after it is still a call. */
    fun testGoToDeclarationFromACallAfterACallOnTheRightOfAMatchLandsOnItsDef() =
        assertLandsOn("def f", "defmodule M do\n  def f, do: 1\n\n  def name do\n    x = f\n    <caret>f\n  end\nend\n")

    /** A container on a line of its own is a value, so a bare name in it calls the function and binds nothing. */
    fun testGoToDeclarationFromACallAfterATupleStatementHoldingItLandsOnItsDef() =
        assertLandsOn("def f", "defmodule M do\n  def f, do: 1\n\n  def name do\n    {f, 1}\n    <caret>f\n  end\nend\n")

    fun testGoToDeclarationFromACallAfterAListStatementHoldingItLandsOnItsDef() =
        assertLandsOn("def f", "defmodule M do\n  def f, do: 1\n\n  def name do\n    [f]\n    <caret>f\n  end\nend\n")

    /** A `match?` pattern binds only inside the call, so a bare name after it calls the function. */
    fun testGoToDeclarationFromACallAfterAMatchQuestionLandsOnItsDef() =
        assertLandsOn(
            "def f",
            "defmodule M do\n  def f, do: 1\n\n  def name(value) do\n    match?({:ok, f}, value)\n    <caret>f\n  end\nend\n"
        )

    fun testGoToDeclarationFromACallInAMatchQuestionsValueLandsOnItsDef() =
        assertLandsOn(
            "def f",
            "defmodule M do\n  def f, do: {:ok, 1}\n\n  def name do\n    match?({:ok, f}, <caret>f)\n  end\nend\n"
        )

    /** Piped, `match?`'s written argument is the value matched, whose bindings stay after the call. */
    fun testGoToDeclarationFromAVariableBoundInAPipedMatchQuestionsValueLandsOnTheBinding() =
        assertLandsOn(
            "x = {:ok, 1}",
            "defmodule M do\n  def x, do: 1\n\n  def run do\n    {:ok, _} |> match?(x = {:ok, 1})\n    <caret>x\n  end\nend\n",
            0
        )

    /** `match?` is a `case` on its value, so what the value binds stays after the call. */
    fun testGoToDeclarationFromAVariableBoundInAMatchQuestionsValueLandsOnTheBinding() =
        assertLandsOn(
            "x = {:ok, 1}",
            "defmodule M do\n  def x, do: 1\n\n  def run do\n    match?({:ok, _}, x = {:ok, 1})\n    <caret>x\n  end\nend\n",
            0
        )

    fun testGoToDeclarationFromAReadInAPipedMatchQuestionsValueLandsOnTheParameter() =
        assertLandsOn(
            "run(x)",
            "defmodule M do\n  def f(a), do: a\n\n  def run(x), do: {:ok, 1} |> match?(x = f(<caret>x))\nend\n",
            "run(".length
        )

    /** Piped, the pipe's left operand is `match?`'s pattern, which binds nothing after the call nor in the value. */
    fun testGoToDeclarationFromACallAfterAPatternPipedIntoAMatchQuestionLandsOnItsDef() =
        assertLandsOn(
            "def x",
            "defmodule M do\n  def x, do: 1\n\n  def run(v) do\n    {:ok, x = 1} |> match?(v)\n    <caret>x\n  end\nend\n"
        )

    fun testGoToDeclarationFromACallInAMatchQuestionsValueAfterAPipedPatternLandsOnItsDef() =
        assertLandsOn(
            "def y",
            "defmodule M do\n  def y, do: 1\n  def f(a), do: a\n\n  def run, do: {:ok, y = 1} |> match?(f(<caret>y))\nend\n"
        )

    fun testGoToDeclarationFromACallAfterAMatchInAMatchQuestionsPatternLandsOnItsDef() =
        assertLandsOn(
            "def x",
            "defmodule M do\n  def x, do: 1\n\n  def run(v) do\n    match?({:ok, x = 1}, v)\n    <caret>x\n  end\nend\n"
        )

    fun testGoToDeclarationFromACallInAnIfOnAMatchQuestionLandsOnItsDef() =
        assertLandsOn(
            "def f",
            "defmodule M do\n  def f, do: 1\n\n  def name(value) do\n    if match?({:ok, f}, value), do: <caret>f\n  end\nend\n"
        )

    /** A head is a pattern, so a `do:` keyword in it binds a parameter, as in a DSL's `defmacro`. */
    fun testGoToDeclarationFromABlockKeywordParameterLandsOnTheParameter() =
        assertLandsOn(
            "do: block",
            "defmodule M do\n  defmacro unless(condition, do: block) do\n    bl<caret>ock\n  end\nend\n",
            "do: ".length
        )

    fun testGoToDeclarationFromABlockKeywordParameterOfAGuardedHeadLandsOnTheParameter() =
        assertLandsOn(
            "do: block",
            "defmodule M do\n  defmacro unless(condition, do: block) when is_atom(condition) do\n    bl<caret>ock\n  end\nend\n",
            "do: ".length
        )

    /** [anchor] is text just before the expected destination, which starts [shift] characters into it. */
    private fun assertLandsOn(anchor: String, text: String, shift: Int = "def ".length) {
        myFixture.configureByText("m.ex", text)
        val expected = myFixture.file.text.indexOf(anchor) + shift

        assertEquals(
            listOf("m.ex" to expected),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.containingFile?.name to it.destination?.textOffset }
        )
    }
}
