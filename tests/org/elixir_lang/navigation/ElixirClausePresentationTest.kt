package org.elixir_lang.navigation

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call

/** The container shown for a clause inside a `defimpl` is the module the implementation is for. */
class ElixirClausePresentationTest : PlatformTestCase() {
    private fun containerText(code: String): String? {
        myFixture.configureByText("scratch.ex", code)

        val clause = PsiTreeUtil
            .findChildrenOfType(myFixture.file, Call::class.java)
            .single { CallDefinitionClause.`is`(it) }

        return ElixirClausePresentation.containerText(clause)
    }

    fun testWithoutForInAModule() =
        assertEquals("Outer", containerText("defmodule Outer do\n  defimpl P do\n    def f(_), do: 1\n  end\nend\n"))

    fun testWithoutForInANestedModule() = assertEquals(
        "Outer.Inner",
        containerText("defmodule Outer do\n  defmodule Inner do\n    defimpl P do\n      def f(_), do: 1\n    end\n  end\nend\n"),
    )

    fun testWithoutForInAModuleOfModuleAlias() = assertEquals(
        "Outer.Inner",
        containerText(
            "defmodule Outer do\n  defmodule __MODULE__.Inner do\n    defimpl P do\n      def f(_), do: 1\n    end\n  end\nend\n"
        ),
    )

    fun testForModuleInAModule() = assertEquals(
        "Outer",
        containerText("defmodule Outer do\n  defimpl P, for: __MODULE__ do\n    def f(_), do: 1\n  end\nend\n"),
    )

    fun testForAList() =
        assertEquals("[A, B]", containerText("defimpl P, for: [A, B] do\n  def f(_), do: 1\nend\n"))

    fun testInAModule() = assertEquals("Outer", containerText("defmodule Outer do\n  def f(_), do: 1\nend\n"))

    fun testInAModuleNamedWithAQuotedAtom() =
        assertEquals(":\"a.b\"", containerText("defmodule :\"a.b\" do\n  def f(_), do: 1\nend\n"))

    fun testInAModuleNamedWithAnAtomWrittenQuoted() =
        assertEquals(":plain", containerText("defmodule :\"plain\" do\n  def f(_), do: 1\nend\n"))

    fun testInAModuleNamedWithAnElixirPrefixedAtomThatIsNotAnAlias() = assertEquals(
        ":\"Elixir.foo bar\"",
        containerText("defmodule :\"Elixir.foo bar\" do\n  def f(_), do: 1\nend\n"),
    )

    fun testInANestedModule() = assertEquals(
        "Outer.Inner",
        containerText("defmodule Outer do\n  defmodule Inner do\n    def f(_), do: 1\n  end\nend\n"),
    )

    fun testInAModuleOfModuleAlias() = assertEquals(
        "Outer.Inner",
        containerText("defmodule Outer do\n  defmodule __MODULE__.Inner do\n    def f(_), do: 1\n  end\nend\n"),
    )
}
