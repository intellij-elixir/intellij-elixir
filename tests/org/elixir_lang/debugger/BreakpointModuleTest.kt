package org.elixir_lang.debugger

import org.elixir_lang.PlatformTestCase

/** The module atom a line breakpoint is set in, which must be the atom Elixir compiles the enclosing module to. */
class BreakpointModuleTest : PlatformTestCase() {
    fun testTopLevel() = assertModuleAtoms("<caret>IO.puts(\"hello\")\n")

    fun testModule() = assertModuleAtoms("defmodule Foo do\n  <caret>:ok\nend\n", "Elixir.Foo")

    fun testNestedModule() =
        assertModuleAtoms("defmodule Foo do\n  defmodule Bar do\n    <caret>:ok\n  end\nend\n", "Elixir.Foo.Bar")

    fun testModuleHeaderAtTheTopLevel() = assertModuleAtoms("<caret>defmodule Foo do\n  :ok\nend\n")

    fun testElixirPrefixedAtom() = assertModuleAtoms("defmodule :\"Elixir.Foo\" do\n  <caret>x = 1\nend\n", "Elixir.Foo")

    fun testElixirPrefixedAliasAroundANestedModule() = assertModuleAtoms(
        "defmodule Elixir.Outer do\n  defmodule Bar do\n    <caret>x = 1\n  end\nend\n",
        "Elixir.Outer.Bar",
    )

    fun testElixirPrefixedAliasInAModule() = assertModuleAtoms(
        "defmodule Outer do\n  defmodule Elixir.Inner do\n    <caret>x = 1\n  end\nend\n",
        "Elixir.Inner",
    )

    fun testAtomInAModule() = assertModuleAtoms(
        "defmodule Outer do\n  defmodule :inner do\n    <caret>x = 1\n  end\nend\n",
        "inner",
    )

    fun testQuotedAtom() = assertModuleAtoms("defmodule :\"foo-bar\" do\n  <caret>x = 1\nend\n", "foo-bar")

    fun testSpacesAroundTheDot() = assertModuleAtoms("defmodule Foo . Bar do\n  <caret>x = 1\nend\n", "Elixir.Foo.Bar")

    /** The module's name is only known at run time, so there is no atom to set the breakpoint in. */
    fun testInterpolatedAtom() = assertModuleAtoms("defmodule :\"#{x}\" do\n  <caret>x = 1\nend\n")

    fun testAliasInAnAtomNamedModule() = assertModuleAtoms(
        "defmodule :foo do\n  defmodule Inner do\n    <caret>x = 1\n  end\nend\n",
        "Elixir.foo.Inner",
    )

    /** An EEx template's module comes from the name of the `.beam` file whose line chunk names the template. */
    fun testBeamFileOfAnAliasNamedModule() =
        assertEquals(setOf("Elixir.Foo.Templates"), moduleAtoms(setOf(beamFileModuleName("Elixir.Foo.Templates.beam"))))

    fun testBeamFileOfAnAtomNamedModule() =
        assertEquals(setOf("my_templates"), moduleAtoms(setOf(beamFileModuleName("my_templates.beam"))))

    /** A module a `quote` defines is named where the `quote` is injected, so its module is not known here. */
    fun testModuleInAQuote() = assertModuleAtoms(
        "defmodule U do\n  defmacro __using__(_) do\n    quote do\n      defmodule Foo do\n        <caret>x = 1\n      end\n    end\n  end\nend\n",
    )

    fun testQuoteInAModule() = assertModuleAtoms(
        "defmodule U do\n  defmacro __using__(_) do\n    quote do\n      <caret>x = 1\n    end\n  end\nend\n",
    )

    /** An `unquote` runs where its `quote` is, in the macro's own module. */
    fun testUnquoteInAModuleInAQuote() = assertModuleAtoms(
        "defmodule U do\n  defmacro __using__(_) do\n    quote do\n      defmodule Foo do\n        def f, do: unquote(<caret>x)\n      end\n    end\n  end\nend\n",
        "Elixir.U",
    )

    fun testModuleOfAnUnquotedModuleAliasInAQuote() = assertModuleAtoms(
        "defmodule U do\n  defmacro __using__(_) do\n    quote do\n      defmodule unquote(__MODULE__.Sub) do\n        <caret>x = 1\n      end\n    end\n  end\nend\n",
        "Elixir.U.Sub",
    )

    fun testModuleInAFunctionBody() = assertModuleAtoms(
        "defmodule U do\n  def f do\n    defmodule Foo do\n      <caret>x = 1\n    end\n  end\nend\n",
        "Elixir.U.Foo",
    )

    /** A module's header runs in the module around it. */
    fun testModuleHeaderInAModule() = assertModuleAtoms(
        "defmodule Outer do\n  <caret>defmodule Inner do\n    x = 1\n  end\nend\n",
        "Elixir.Outer",
    )

    fun testParenthesisedModuleInAModule() = assertModuleAtoms(
        "defmodule Outer do\n  defmodule(Inner, do: <caret>x = 1)\nend\n",
        "Elixir.Outer.Inner",
    )

    fun testImplementationForAList() = assertModuleAtoms(
        "defimpl P, for: [X, Y] do\n  def f(_), do: <caret>1\nend\n",
        "Elixir.P.X",
        "Elixir.P.Y",
    )

    private fun assertModuleAtoms(source: String, vararg expected: String) {
        val file = myFixture.configureByText("breakpoint.ex", source)

        assertEquals(
            expected.toSet(),
            moduleAtoms(breakpointModuleNames(project, file.virtualFile, myFixture.caretOffset)!!),
        )
    }
}
