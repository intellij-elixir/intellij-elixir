package org.elixir_lang.structure_view.element.structure

import com.intellij.ide.structureView.StructureViewTreeElement
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.structure_view.Model
import org.elixir_lang.structure_view.element.Exception as ExceptionElement

/**
 * A struct is named after the `defmodule` it is compiled onto, so the label must not vary with the
 * file name or with what the enclosing node happens to present.
 */
class StructureNameTest : PlatformTestCase() {
    private fun structure(code: String): StructureViewTreeElement {
        myFixture.configureByText("a_file_whose_name_has_dots.ex", code)

        val found = mutableListOf<StructureViewTreeElement>()

        fun walk(element: StructureViewTreeElement) {
            if (element is Structure) {
                found.add(element)
            }

            for (child in element.children) {
                if (child is StructureViewTreeElement) {
                    walk(child)
                }
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root)
        assertEquals("the fixture must build exactly one struct node", 1, found.size)

        return found.single()
    }

    fun testUnqualifiedModule() {
        val presentation = structure("defmodule Foo do\n  defstruct [:a]\nend\n").presentation

        assertEquals("%Foo{}", presentation.presentableText)
        assertNull("an unqualified module leaves the struct with no qualifier", presentation.locationString)
    }

    fun testQualifiedModule() {
        val presentation = structure("defmodule Foo.Bar do\n  defstruct [:a]\nend\n").presentation

        assertEquals("%Bar{}", presentation.presentableText)
        assertEquals("Foo", presentation.locationString)
    }

    /** The name comes from the module, not from the `def` the `defstruct` is written in. */
    fun testInsideACallDefinition() {
        val presentation =
            structure("defmodule Foo do\n  def f do\n    defmodule Inner do\n      defstruct [:a]\n    end\n  end\nend\n")
                .presentation

        assertEquals("%Inner{}", presentation.presentableText)
        assertEquals("Foo", presentation.locationString)
    }

    /** A module in a `quote` is named for the module the quote is injected into, which the quote does not say. */
    fun testInsideAQuote() {
        val presentation =
            structure("defmodule Foo do\n  defmacro __using__(_) do\n    quote do\n      defmodule Inner do\n        defstruct [:a]\n      end\n    end\n  end\nend\n")
                .presentation

        assertEquals("%Inner{}", presentation.presentableText)
        assertNull("the quote's module is not Foo", presentation.locationString)
    }

    /** A lexically nested `defmodule` defines `Foo.Bar`, so it must read as `defmodule Foo.Bar` does. */
    fun testLexicallyNestedModule() {
        val nested = structure("defmodule Foo do\n  defmodule Bar do\n    defstruct [:a]\n  end\nend\n").presentation
        val dotted = structure("defmodule Foo.Bar do\n  defstruct [:a]\nend\n").presentation

        assertEquals("%Bar{}", nested.presentableText)
        assertEquals("Foo", nested.locationString)
        assertEquals(dotted.presentableText, nested.presentableText)
        assertEquals(dotted.locationString, nested.locationString)
    }

    /** `defexception` named itself the same broken way, and shares the fix. */
    fun testAnExceptionIsNamedAfterItsModule() {
        myFixture.configureByText(
            "a_file_whose_name_has_dots.ex",
            "defmodule Foo.Bar do\n  defexception [:message]\nend\n"
        )

        val found = mutableListOf<ExceptionElement>()

        fun walk(element: StructureViewTreeElement) {
            if (element is ExceptionElement) {
                found.add(element)
            }

            for (child in element.children) {
                if (child is StructureViewTreeElement) {
                    walk(child)
                }
            }
        }

        walk(Model(myFixture.file as ElixirFile, null).root)
        assertEquals("the fixture must build exactly one exception node", 1, found.size)

        assertEquals("Bar", found.single().presentation.presentableText)
        assertEquals("Foo", found.single().presentation.locationString)
    }

    /** `Module.is` accepts a parenthesised `defmodule`, so the tree builds a node and the struct in it
     *  must be named rather than falling back. */
    fun testParenthesisedModule() {
        val presentation = structure("defmodule(Foo) do\n  defstruct [:a]\nend\n").presentation

        assertEquals("%Foo{}", presentation.presentableText)
    }

    /** `for:` a list compiles to one module per entry, but the tree builds one node for all of them. */
    fun testInsideAnImplementationForAList() {
        val presentation = structure("defimpl Enumerable, for: [A, B] do\n  defstruct [:a]\nend\n").presentation

        assertEquals("%[A, B]{}", presentation.presentableText)
        assertEquals("Enumerable", presentation.locationString)
    }

    /** The list is assembled from the `for:` aliases, not read off the source, so a `.` in an entry
     *  is not mistaken for the qualifier separator and newlines cannot reach the label. */
    fun testInsideAnImplementationForAnAwkwardList() {
        val dotted = structure("defimpl Enumerable, for: [A.B, C] do\n  defstruct [:a]\nend\n").presentation

        assertEquals("%[A.B, C]{}", dotted.presentableText)
        assertEquals("Enumerable", dotted.locationString)

        val multiline = structure(
            "defimpl Enumerable,\n  for: [\n    A,\n    B\n  ] do\n  defstruct [:a]\nend\n"
        ).presentation

        assertEquals("%[A, B]{}", multiline.presentableText)
    }

    /** `defprotocol` defines a module too, so the walk must stop at it rather than climb past. */
    fun testInsideAProtocol() {
        val presentation = structure("defmodule Foo do\n  defprotocol Bar do\n    defstruct [:a]\n  end\nend\n").presentation

        assertEquals("%Bar{}", presentation.presentableText)
        assertEquals("Foo", presentation.locationString)
    }

    /** An empty `for:` list is still a list, not a missing `for:`. */
    fun testInsideAnImplementationForAnEmptyList() {
        val presentation = structure("defimpl Enumerable, for: [] do\n  defstruct [:a]\nend\n").presentation

        assertEquals("%[]{}", presentation.presentableText)
        assertEquals("Enumerable", presentation.locationString)
    }

    /** A qualified outer module exercises the rejoin the nested walk does before re-splitting. */
    fun testANestedModuleUnderAQualifiedOuterModule() {
        val presentation = structure("defmodule Foo.Bar do\n  defmodule Baz do\n    defstruct [:a]\n  end\nend\n").presentation

        assertEquals("%Baz{}", presentation.presentableText)
        assertEquals("Foo.Bar", presentation.locationString)
    }

    /** The walk out of a nested module must not lose the list implementation above it. */
    fun testAModuleInsideAnImplementationForAList() {
        val presentation = structure("defimpl Enumerable, for: [A, B] do\n  defmodule Inner do\n    defstruct [:a]\n  end\nend\n").presentation

        assertEquals("%Inner{}", presentation.presentableText)
        assertEquals("Enumerable.[A, B]", presentation.locationString)
    }

    /** `defimpl P` with no `for:` means the module it is written in, so the module is `P.That`. */
    fun testInsideAnImplementationWithoutAFor() {
        val presentation = structure("defmodule Foo do\n  defimpl Enumerable do\n    defstruct [:a]\n  end\nend\n").presentation

        assertEquals("%Foo{}", presentation.presentableText)
        assertEquals("Enumerable", presentation.locationString)
    }

    /** The nearest modular wins: a `defmodule` inside a `defimpl` is not overridden by it. */
    fun testAModuleInsideAnImplementation() {
        val presentation = structure("defimpl Enumerable, for: Bar do\n  defmodule Baz do\n    defstruct [:a]\n  end\nend\n").presentation

        assertEquals("%Baz{}", presentation.presentableText)
        assertEquals("Enumerable.Bar", presentation.locationString)
    }

    /** A parenthesised `defmodule` nested in a plain one must still be qualified by it. */
    fun testParenthesisedModuleNestedInAPlainOne() {
        val presentation = structure("defmodule Foo do\n  defmodule(Bar) do\n    defstruct [:a]\n  end\nend\n").presentation

        assertEquals("%Bar{}", presentation.presentableText)
        assertEquals("Foo", presentation.locationString)
    }

    /** Both spellings nested, which neither `getModuleName` nor a single-step walk assembles. */
    fun testParenthesisedModuleNestedInAParenthesisedOne() {
        val presentation = structure("defmodule(Foo) do\n  defmodule(Bar) do\n    defstruct [:a]\n  end\nend\n").presentation

        assertEquals("%Bar{}", presentation.presentableText)
        assertEquals("Foo", presentation.locationString)
    }

    /** `defimpl Proto, for: For` compiles onto `Proto.For`, not onto the module around it. */
    fun testInsideAnImplementation() {
        val presentation =
            structure("defmodule Foo do\n  defimpl Enumerable, for: Bar do\n    defstruct [:a]\n  end\nend\n").presentation

        assertEquals("%Bar{}", presentation.presentableText)
        assertEquals("Enumerable", presentation.locationString)
    }
}
