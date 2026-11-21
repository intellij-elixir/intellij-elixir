package org.elixir_lang.heex.reference

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.elixir_lang.model.psi.module_attribute.ModuleAttributeReference

/** In a template, `@form[:email]` reads the assign `form`, which is no module attribute even when none is declared. */
class HeexAttributeBracketReadTest : HeexHostTestCase() {
    fun testAKeyReadInAHeexFileIsNoModuleAttribute() {
        myFixture.configureByText("form.html.heex", "<input field={@form[:email]} />")

        assertNoModuleAttributeIn(myFixture.file)
    }

    fun testAKeyReadInAnHSigilIsNoModuleAttribute() {
        myFixture.configureByText(
            "form.ex",
            """
            defmodule Page do
              def render(assigns) do
                ~H"<input field={@form[:email]} />"
              end
            end
            """.trimIndent()
        )

        assertNoModuleAttributeIn(myFixture.injectedHeexRoots().single())
    }

    /** No element from `form` in [template]'s Elixir up to the `@form[:email]` around it has a module-attribute reference. */
    private fun assertNoModuleAttributeIn(template: PsiFile) {
        val elixir = template.viewProvider.allFiles.single { it.language.id == "Elixir" }
        val read = elixir.text.indexOf(READ)
        val name = elixir.findElementAt(read + 1)!!

        assertEquals("form", name.text)
        assertEmpty(
            generateSequence<PsiElement>(name) { it.parent }
                .takeWhile { it.textRange.startOffset >= read && it.textRange.endOffset <= read + READ.length }
                .flatMap { PsiSymbolReferenceService.getService().getReferences(it).asSequence() }
                .filterIsInstance<ModuleAttributeReference>()
                .toList()
        )
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
    }

    private companion object {
        const val READ = "@form[:email]"
    }
}
