package org.elixir_lang.navigation

import com.intellij.navigation.NavigationItem
import org.elixir_lang.PlatformTestCase

/** Go to Class keeps an implementation only if the name it presents is the name it was indexed under. */
class GotoClassContributorTest : PlatformTestCase() {
    private fun items(code: String, name: String): List<NavigationItem> {
        myFixture.configureByText("scratch.ex", code)

        val contributor = com.intellij.navigation.ChooseByNameContributor.CLASS_EP_NAME
            .extensionList
            .filterIsInstance<GotoClassContributor>()
            .single()

        return contributor.getItemsByName(name, name, myFixture.project, false).toList()
    }

    private fun assertOneImplementation(items: List<NavigationItem>, name: String, forName: String) {
        val item = items.single()

        assertEquals(name, item.name)
        assertEquals(forName, item.presentation!!.presentableText)
    }

    fun testWithoutForInAModule() = assertOneImplementation(
        items("defmodule Outer do\n  defimpl P do\n  end\nend\n", "P.Outer"),
        "P.Outer",
        "Outer",
    )

    fun testForModuleInAModule() = assertOneImplementation(
        items("defmodule Outer do\n  defimpl P, for: __MODULE__ do\n  end\nend\n", "P.Outer"),
        "P.Outer",
        "Outer",
    )

    fun testForAQualifiedAlias() = assertOneImplementation(
        items("defimpl P, for: A.B do\nend\n", "P.A.B"),
        "P.A.B",
        "A.B",
    )

    /** Each module of a `for:` list is its own entry, and only the one asked for is listed. */
    fun testForAList() = assertOneImplementation(
        items("defimpl P, for: [A, AB] do\nend\n", "P.A"),
        "P.A",
        "A",
    )

    /** `for: :foo` is the module `:"Elixir.P.foo"`, the name it is indexed under. */
    fun testForAnAtom() = assertOneImplementation(
        items("defimpl P, for: :foo do\nend\n", ":Elixir.P.foo"),
        ":Elixir.P.foo",
        ":foo",
    )

    fun testWithoutForInAnAtomNamedModule() = assertOneImplementation(
        items("defmodule :bar do\n  defimpl P do\n  end\nend\n", ":Elixir.P.bar"),
        ":Elixir.P.bar",
        ":bar",
    )
}
