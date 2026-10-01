package org.elixir_lang.documentation

import org.elixir_lang.PlatformTestCase

/** A documentation link spelled with `Elixir.` names the module of that atom, as `Elixir.Foo == Foo` in Elixir. */
class ElixirPrefixedLinkTest : PlatformTestCase() {
    fun testAlias() = assertLinks("defmodule Foo do\n  def f, do: 1\nend\n", "Foo")

    fun testElixirPrefixedAlias() = assertLinks("defmodule Foo do\n  def f, do: 1\nend\n", "Elixir.Foo")

    fun testElixirPrefixedFunction() = assertLinks("defmodule Foo do\n  def f, do: 1\nend\n", "Elixir.Foo.f/0")

    fun testElixirPrefixedAtomNotShapedAsAnAlias() =
        assertLinks("defmodule :\"Elixir.foo\" do\n  def f, do: 1\nend\n", "Elixir.foo")

    private fun assertLinks(source: String, link: String) {
        myFixture.configureByText("linked.ex", source)

        assertNotNull(
            "$link links nowhere",
            ElixirDocumentationProvider().getDocumentationElementForLink(psiManager, link, myFixture.file)
        )
    }
}
