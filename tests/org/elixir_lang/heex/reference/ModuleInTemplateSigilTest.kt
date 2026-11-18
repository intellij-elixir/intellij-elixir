package org.elixir_lang.heex.reference

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.psi.HeredocLiteral
import org.elixir_lang.psi.call.Call

/** A template sigil's Elixir is compiled inside the module that holds the sigil, so `__MODULE__` there names it. */
class ModuleInTemplateSigilTest : HeexHostTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "special_forms.ex",
            """
            defmodule Kernel.SpecialForms do
              @doc "The current module name as an atom."
              defmacro __MODULE__, do: :ok
            end
            """.trimIndent()
        )
    }

    fun testGoToDeclarationInAnHSigilReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            """
            defmodule Page do
              def render(assigns) do
                ~H"<p>{__MOD<caret>ULE__}</p>"
              end
            end
            """
        )

    fun testGoToDeclarationInAnHSigilHeredocReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            """
            defmodule Page do
              def render(assigns) do
                ~H'''
                <p>{__MOD<caret>ULE__}</p>
                '''
              end
            end
            """
        )

    fun testGoToDeclarationInAnESigilReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            """
            defmodule Page do
              def render(assigns) do
                ~E'''
                <p><%= __MOD<caret>ULE__ %></p>
                '''
              end
            end
            """
        )

    fun testGoToDeclarationInAnLSigilReachesTheEnclosingModule() =
        assertEnclosingModuleReached(
            """
            defmodule Page do
              def render(assigns) do
                ~L'''
                <p><%= __MOD<caret>ULE__ %></p>
                '''
              end
            end
            """
        )

    /** Past the sigil, the `quote` holds it, so its module is the caller's. */
    fun testGoToDeclarationInAnHSigilInAQuoteBodyReachesTheSpecialForm() {
        myFixture.configureByText(
            "test.ex",
            """
            defmodule MyWeb.Components do
              defmacro page do
                quote do
                  def render(assigns) do
                    ~H"<p>{__MOD<caret>ULE__}</p>"
                  end
                end
              end
            end
            """.trimIndent()
        )

        assertEquals("special_forms.ex", myFixture.gotoDeclarationDestinationAtCaret()?.containingFile?.name)
    }

    /**
     * A documentation example is not compiled in the module it documents, unlike a template. The caret would land in
     * the Markdown injection, so this reads the code block's Elixir injection directly.
     */
    fun testInADocumentationCodeBlockNamesNoModule() {
        myFixture.configureByText(
            "test.ex",
            """
            defmodule Page do
              @moduledoc ""${'"'}
              Example:

                  __MODULE__
              ""${'"'}
            end
            """.trimIndent()
        )
        val heredoc = PsiTreeUtil.findChildOfType(myFixture.file, HeredocLiteral::class.java)!!
        val codeBlock = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(heredoc).orEmpty()
            .map { it.first }
            .single { it.language.isKindOf(ElixirLanguage) }
        val module =
            PsiTreeUtil.getParentOfType(codeBlock.findElementAt(codeBlock.text.indexOf("__MODULE__")), Call::class.java)!!

        assertEquals(
            emptyList<String?>(),
            (module.reference as PsiPolyVariantReference).multiResolve(false).map { it.element?.text }
        )
    }

    private fun assertEnclosingModuleReached(text: String) {
        myFixture.configureByText("test.ex", text.trimIndent())

        val destination = myFixture.gotoDeclarationDestinationAtCaret()

        assertEquals("Expected the enclosing module's name", "Page", destination?.text)
        assertEquals("test.ex", destination?.containingFile?.name)
    }
}
