package org.elixir_lang.structure_view.element

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ui.icons.RowIcon
import org.elixir_lang.Icons
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.structure_view.Model

/** The head node of an EEx function takes its visibility from the kind, which need not be a literal. */
class EExFunctionFromHeadTest : PlatformTestCase() {
    fun testKindNotALiteralHasAHead() {
        myFixture.configureByText(
            "eex_dynamic.ex",
            EEX_DEFINITION + "defmodule M do\n  require EEx\n  EEx.function_from_string(@kind, :dyn, \"<%= a %>\", [:a])\nend\n"
        )

        val heads = heads(Model(myFixture.file as ElixirFile, null).root)

        assertEquals("the node has one head", 1, heads.size)

        val (presentation, errors) = captureLoggedErrors { heads.single().presentation }

        assertEmpty("a head with no visibility is not an error", errors)
        assertEquals("EEx.function_from_string(@kind, :dyn, \"<%= a %>\", [:a])", presentation.presentableText)
        assertEquals(Icons.UNKNOWN, (presentation.getIcon(false) as RowIcon).getIcon(1))
    }

    fun testKindLiteralHasAHeadWithItsVisibility() {
        myFixture.configureByText(
            "eex_literal.ex",
            EEX_DEFINITION + "defmodule M do\n  require EEx\n  EEx.function_from_string(:defp, :lit, \"<%= a %>\", [:a])\nend\n"
        )

        val head = heads(Model(myFixture.file as ElixirFile, null).root).single()

        assertEquals(Icons.Visibility.PRIVATE, (head.presentation.getIcon(false) as RowIcon).getIcon(1))
    }

    private fun heads(root: StructureViewTreeElement): List<EExFunctionFromHead> {
        val found = mutableListOf<EExFunctionFromHead>()

        fun walk(element: StructureViewTreeElement) {
            if (element is EExFunctionFromHead) found.add(element)

            for (child in element.children) {
                if (child is StructureViewTreeElement) walk(child)
            }
        }

        walk(root)

        return found
    }

    private companion object {
        const val EEX_DEFINITION =
            "defmodule EEx do\n  defmacro function_from_string(kind, name, source, args \\\\ [], options \\\\ []) do\n" +
                "    quote do\n      unquote(kind)\n      unquote(name)\n    end\n  end\nend\n\n"
    }
}
