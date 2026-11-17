package org.elixir_lang.structure_view.element.modular

import com.intellij.ide.structureView.StructureViewTreeElement
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.structure_view.structureViewElements

/**
 * `defimpl P, for: T` compiles to a module named `P.T`, but the node presents the protocol and the
 * type rather than that generated name's qualifier and final segment.
 */
class ImplementationNodeTest : PlatformTestCase() {
    private fun implementation(code: String): StructureViewTreeElement {
        myFixture.configureByText("demo.ex", code)

        val found = structureViewElements<Implementation>(myFixture.file as ElixirFile)
        assertEquals("the fixture must build exactly one implementation node", 1, found.size)

        return found.single()
    }

    fun testQualifiedFor() {
        val presentation =
            implementation("defimpl Enumerable, for: Foo.Bar do\n  def count(_), do: 0\nend\n").presentation

        assertEquals("Foo.Bar", presentation.presentableText)
        assertEquals("Enumerable", presentation.locationString)
    }

    fun testUnqualifiedFor() {
        val presentation =
            implementation("defimpl Enumerable, for: Bar do\n  def count(_), do: 0\nend\n").presentation

        assertEquals("Bar", presentation.presentableText)
        assertEquals("Enumerable", presentation.locationString)
    }

    /** Without `for:`, the implementation is for the module it is written in. */
    fun testWithoutForInAModule() {
        val presentation = implementation("defmodule Outer do\n  defimpl Enumerable do\n  end\nend\n").presentation

        assertEquals("Outer", presentation.presentableText)
        assertEquals("Enumerable", presentation.locationString)
    }

    fun testWithoutForInANestedModule() {
        val presentation = implementation(
            "defmodule Outer do\n  defmodule Inner do\n    defimpl Enumerable do\n    end\n  end\nend\n"
        ).presentation

        assertEquals("Outer.Inner", presentation.presentableText)
        assertEquals("Enumerable", presentation.locationString)
    }

    fun testForModuleInAModule() {
        val presentation =
            implementation("defmodule Outer do\n  defimpl Enumerable, for: __MODULE__ do\n  end\nend\n").presentation

        assertEquals("Outer", presentation.presentableText)
        assertEquals("Enumerable", presentation.locationString)
    }

    fun testForAList() {
        val presentation = implementation("defimpl Enumerable, for: [\n  A,\n  B\n] do\nend\n").presentation

        assertEquals("[A, B]", presentation.presentableText)
        assertEquals("Enumerable", presentation.locationString)
    }

    fun testForAnEmptyList() {
        val presentation = implementation("defimpl Enumerable, for: [] do\nend\n").presentation

        assertEquals("[]", presentation.presentableText)
        assertEquals("Enumerable", presentation.locationString)
    }
}
