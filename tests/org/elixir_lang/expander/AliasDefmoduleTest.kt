package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/** [aliasDefmodule]: what `defmodule` defines and the alias it adds, by the name as written and where it is. */
class AliasDefmoduleTest : ExpanderTestCase() {
    fun testANestedName() = assertAlias("Bar", "Elixir.Bar", "Elixir.X", "Elixir.X.Bar Elixir.X.Bar Elixir.Bar")

    fun testANameAtTheTop() = assertAlias("Bar", "Elixir.Bar", null, "Elixir.Bar Elixir.Bar none")

    fun testAnElixirRootedName() = assertAlias("Elixir.Bar", "Elixir.Bar", "Elixir.X", "Elixir.Bar Elixir.Bar none")

    fun testANestedNameOfTwoSegments() =
        assertAlias("Bar.Baz", "Elixir.Bar.Baz", "Elixir.X", "Elixir.X.Bar.Baz Elixir.X.Bar Elixir.Bar")

    /** After `alias Foo.Bar`, `defmodule Bar.Baz` in `Outer` still defines `Outer.Bar.Baz` and aliases `Bar` to `Outer.Bar`. */
    fun testTheExpandedNameIsDiscarded() =
        assertAlias("Bar.Baz", "Elixir.Foo.Bar.Baz", "Elixir.Outer", "Elixir.Outer.Bar.Baz Elixir.Outer.Bar Elixir.Bar")

    fun testAnAtomNamedEnclosingModule() =
        assertAlias("Inner", "Elixir.Inner", "atom_parent", "Elixir.atom_parent.Inner Elixir.atom_parent.Inner Elixir.Inner")

    fun testANameThatIsNotAnAlias() = assertAlias(":foo", "foo", "Elixir.X", "foo foo none")

    fun testAnAliasWhoseHeadIsNotAnAtom() =
        assertAlias("__MODULE__.Foo", "Elixir.X.Foo", "Elixir.X", "Elixir.X.Foo Elixir.X.Foo none")

    private fun assertAlias(name: String, expanded: String, enclosing: String?, expected: String) {
        val alias = aliasDefmodule(lower(name, ElixirLanguageLevel.of("1.20.4")), expanded, enclosing)

        assertEquals(expected, "${alias.full} ${alias.old} ${alias.alias ?: "none"}")
    }
}
