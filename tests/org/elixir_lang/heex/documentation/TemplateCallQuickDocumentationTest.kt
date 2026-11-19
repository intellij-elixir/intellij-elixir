package org.elixir_lang.heex.documentation

import org.elixir_lang.declaration.disableExpander
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.documentation.documentationHintAtCaret
import org.elixir_lang.documentation.quickDocumentationIn
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

        assertHelps(myFixture.quickDocumentationIn(project, "page.html.heex", "<p>{Helper.he<caret>lp()}</p>"))
    }

    fun testACallInAnHeexFileShowsItsDocWithTheExpanderOn() = on(::testACallInAnHeexFileShowsItsDocWithTheExpanderOff)

    fun testACallInAnESigilShowsItsDocWithTheExpanderOff() =
        assertHelps(documentationInSigil("~E'''\n    <p><%= Helper.he<caret>lp() %></p>\n    '''"))

    fun testACallInAnESigilShowsItsDocWithTheExpanderOn() = on(::testACallInAnESigilShowsItsDocWithTheExpanderOff)

    fun testACallInAnLSigilShowsItsDocWithTheExpanderOff() =
        assertHelps(documentationInSigil("~L'''\n    <p><%= Helper.he<caret>lp() %></p>\n    '''"))

    fun testACallInAnLSigilShowsItsDocWithTheExpanderOn() = on(::testACallInAnLSigilShowsItsDocWithTheExpanderOff)

    /** A template compiles in the module around its sigil, so a private function called in one is that module's own. */
    fun testAPrivateFunctionCalledInAnHSigilIsFoundWithTheExpanderOff() {
        val documentation = myFixture.quickDocumentationIn(
            project,
            "test.ex",
            listOf(
                "defmodule M do",
                "  def render(assigns) do",
                "    ~H\"<p>{se<caret>cret(1)}</p>\"",
                "  end",
                "",
                "  defp secret(x), do: x",
                "end"
            ).joinToString("\n")
        )

        assertTrue("Expected the private `secret/1`, got: $documentation", "secret(x)" in documentation)
    }

    fun testAPrivateFunctionCalledInAnHSigilIsFoundWithTheExpanderOn() = on(::testAPrivateFunctionCalledInAnHSigilIsFoundWithTheExpanderOff)

    fun testTheHintAtACallInAnHSigilNamesTheFunctionWithTheExpanderOff() {
        myFixture.configureByText("test.ex", moduleHolding("""~H"<p>{Helper.he<caret>lp()}</p>""""))

        assertEquals("function \"help\" [test.ex]", myFixture.documentationHintAtCaret(project))
    }

    fun testTheHintAtACallInAnHSigilNamesTheFunctionWithTheExpanderOn() = on(::testTheHintAtACallInAnHSigilNamesTheFunctionWithTheExpanderOff)

    fun testTheModuleMacroInAnHSigilShowsTheSpecialFormWithTheExpanderOff() =
        assertShowsSpecialForm(documentationInSigil("""~H"<p>{__MOD<caret>ULE__}</p>""""))

    fun testTheModuleMacroInAnHSigilShowsTheSpecialFormWithTheExpanderOn() = on(::testTheModuleMacroInAnHSigilShowsTheSpecialFormWithTheExpanderOff)

    fun testTheModuleMacroInAnHeexFileShowsTheSpecialFormWithTheExpanderOff() =
        assertShowsSpecialForm(myFixture.quickDocumentationIn(project, "page.html.heex", "<p>{__MOD<caret>ULE__}</p>"))

    fun testTheModuleMacroInAnHeexFileShowsTheSpecialFormWithTheExpanderOn() = on(::testTheModuleMacroInAnHeexFileShowsTheSpecialFormWithTheExpanderOff)

    fun testTheModuleMacroInAnESigilHeredocShowsTheSpecialFormWithTheExpanderOff() =
        assertShowsSpecialForm(documentationInSigil("~E'''\n    <p><%= __MOD<caret>ULE__ %></p>\n    '''"))

    fun testTheModuleMacroInAnESigilHeredocShowsTheSpecialFormWithTheExpanderOn() =
        on(::testTheModuleMacroInAnESigilHeredocShowsTheSpecialFormWithTheExpanderOff)

    private fun assertHelps(documentation: String) {
        assertTrue("Expected Helper.help/0's doc, got: $documentation", "Helps." in documentation)
    }

    /** `__MODULE__` is documented as the special form in `.ex`, so in a template too. */
    private fun assertShowsSpecialForm(documentation: String) {
        assertTrue("Expected Kernel.SpecialForms, got: $documentation", "<b>Kernel.SpecialForms</b>" in documentation)
        assertTrue("Expected the special form's doc, got: $documentation", "The current module name as an atom." in documentation)
    }

    /** Quick Documentation at the caret in [sigil], written in `M`'s `render` beside `Helper`. */
    private fun documentationInSigil(sigil: String): String = myFixture.quickDocumentationIn(project, "test.ex", moduleHolding(sigil))

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
