package org.elixir_lang.heex.documentation

import org.elixir_lang.declaration.disableExpander
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.documentation.documentationHintAtCaret
import org.elixir_lang.documentation.quickDocumentationAtCaret
import org.elixir_lang.heex.reference.HeexHostTestCase

/**
 * Quick Documentation on Elixir inside a template - a `~H`, `~E` or `~L` sigil, or a `.heex` file - shows what it shows
 * outside one, with Run the expander off and on.
 */
class TemplateCallQuickDocumentationTest : HeexHostTestCase() {
    override fun setUp() {
        super.setUp()
        disableExpander(testRootDisposable)
        myFixture.addFileToProject(
            "special_forms.ex",
            "defmodule Kernel.SpecialForms do\n  @doc \"The current module name as an atom.\"\n  defmacro __MODULE__, do: :ok\nend"
        )
    }

    fun testACallInAnHSigilShowsItsDocWithTheExpanderOff() = assertHelps(documentationInSigil("""~H"<p>{Helper.he<caret>lp()}</p>""""))

    fun testACallInAnHSigilShowsItsDocWithTheExpanderOn() = on(::testACallInAnHSigilShowsItsDocWithTheExpanderOff)

    fun testACallInAnHSigilHeredocShowsItsDocWithTheExpanderOff() =
        assertHelps(documentationInSigil("~H'''\n    <p>{Helper.he<caret>lp()}</p>\n    '''"))

    fun testACallInAnHSigilHeredocShowsItsDocWithTheExpanderOn() = on(::testACallInAnHSigilHeredocShowsItsDocWithTheExpanderOff)

    fun testACallInAnHeexFileShowsItsDocWithTheExpanderOff() {
        myFixture.addFileToProject("helper.ex", HELPER)
        myFixture.configureByText("page.html.heex", "<p>{Helper.he<caret>lp()}</p>")

        assertHelps(myFixture.quickDocumentationAtCaret(project))
    }

    fun testACallInAnHeexFileShowsItsDocWithTheExpanderOn() = on(::testACallInAnHeexFileShowsItsDocWithTheExpanderOff)

    fun testACallInAnESigilShowsItsDocWithTheExpanderOff() =
        assertHelps(documentationInSigil("~E'''\n    <p><%= Helper.he<caret>lp() %></p>\n    '''"))

    fun testACallInAnESigilShowsItsDocWithTheExpanderOn() = on(::testACallInAnESigilShowsItsDocWithTheExpanderOff)

    fun testACallInAnLSigilShowsItsDocWithTheExpanderOff() =
        assertHelps(documentationInSigil("~L'''\n    <p><%= Helper.he<caret>lp() %></p>\n    '''"))

    fun testACallInAnLSigilShowsItsDocWithTheExpanderOn() = on(::testACallInAnLSigilShowsItsDocWithTheExpanderOff)

    fun testTheHintAtACallInAnHSigilNamesTheFunctionWithTheExpanderOff() {
        myFixture.configureByText("test.ex", moduleHolding("""~H"<p>{Helper.he<caret>lp()}</p>""""))

        assertEquals("function \"help\" [test.ex]", myFixture.documentationHintAtCaret(project))
    }

    fun testTheHintAtACallInAnHSigilNamesTheFunctionWithTheExpanderOn() = on(::testTheHintAtACallInAnHSigilNamesTheFunctionWithTheExpanderOff)

    fun testTheModuleMacroInAnHSigilShowsTheSpecialFormWithTheExpanderOff() =
        assertShowsSpecialForm(documentationInSigil("""~H"<p>{__MOD<caret>ULE__}</p>""""))

    fun testTheModuleMacroInAnHSigilShowsTheSpecialFormWithTheExpanderOn() = on(::testTheModuleMacroInAnHSigilShowsTheSpecialFormWithTheExpanderOff)

    fun testTheModuleMacroInAnHeexFileShowsTheSpecialFormWithTheExpanderOff() {
        myFixture.configureByText("page.html.heex", "<p>{__MOD<caret>ULE__}</p>")

        assertShowsSpecialForm(myFixture.quickDocumentationAtCaret(project))
    }

    fun testTheModuleMacroInAnHeexFileShowsTheSpecialFormWithTheExpanderOn() = on(::testTheModuleMacroInAnHeexFileShowsTheSpecialFormWithTheExpanderOff)

    fun testTheModuleMacroInAnESigilHeredocShowsTheSpecialFormWithTheExpanderOff() =
        assertShowsSpecialForm(documentationInSigil("~E'''\n    <p><%= __MOD<caret>ULE__ %></p>\n    '''"))

    fun testTheModuleMacroInAnESigilHeredocShowsTheSpecialFormWithTheExpanderOn() =
        on(::testTheModuleMacroInAnESigilHeredocShowsTheSpecialFormWithTheExpanderOff)

    private fun assertHelps(documentation: String?) {
        assertTrue("Expected Helper.help/0's doc, got: $documentation", "Helps." in documentation.orEmpty())
    }

    /** `__MODULE__` is documented as the special form in `.ex`, so in a template too. */
    private fun assertShowsSpecialForm(documentation: String?) {
        assertTrue("Expected Kernel.SpecialForms, got: $documentation", "<b>Kernel.SpecialForms</b>" in documentation.orEmpty())
        assertTrue("Expected the special form's doc, got: $documentation", "The current module name as an atom." in documentation.orEmpty())
    }

    /** Quick Documentation at the caret in [sigil], written in `M`'s `render` beside `Helper`. */
    private fun documentationInSigil(sigil: String): String? {
        myFixture.configureByText("test.ex", moduleHolding(sigil))

        return myFixture.quickDocumentationAtCaret(project)
    }

    private fun moduleHolding(sigil: String): String =
        listOf(
            HELPER,
            "",
            "defmodule M do",
            "  @moduledoc \"The M module.\"",
            "",
            "  def render(assigns) do",
            "    $sigil",
            "  end",
            "end"
        ).joinToString("\n")

    private fun on(test: () -> Unit) {
        enableExpander(module, testRootDisposable)
        test()
    }

    private companion object {
        const val HELPER = "defmodule Helper do\n  @doc \"Helps.\"\n  def help, do: :ok\nend"
    }
}
