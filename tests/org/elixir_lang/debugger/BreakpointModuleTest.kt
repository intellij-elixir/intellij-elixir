package org.elixir_lang.debugger

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.impl.getModuleName

/** The module atom a line breakpoint is set in, which must be the atom Elixir compiles the enclosing module to. */
class BreakpointModuleTest : PlatformTestCase() {
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

    private fun assertModuleAtoms(source: String, vararg expected: String) {
        myFixture.configureByText("breakpoint.ex", source)
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!

        assertEquals(expected.toSet(), moduleAtoms(setOfNotNull(element.getModuleName())))
    }
}
