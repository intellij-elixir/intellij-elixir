package org.elixir_lang.psi.impl.call

import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.PlatformTestCase

/**
 * A one-liner's body is the value of its `do:` key, and Elixir reads a quoted `"do":` as that same key: a `__using__`
 * returning `quote "do": def(injected(), do: :ok)` injects `injected/0`.
 */
class QuotedDoKeyTest : PlatformTestCase() {
    fun testUseInjectsADoKeyQuote() = assertInjectedResolves("quote do: def(injected(), do: :ok)")

    fun testUseInjectsAQuotedDoKeyQuote() = assertInjectedResolves("quote \"do\": def(injected(), do: :ok)")

    private fun assertInjectedResolves(quote: String) {
        myFixture.configureByText(
            "injector.ex",
            "defmodule Injector do\n  defmacro __using__(_opts) do\n    $quote\n  end\nend\n"
        )
        myFixture.configureByText(
            "user.ex",
            "defmodule User do\n  use Injector\n  def call, do: <caret>injected()\nend\n"
        )
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)!! as PsiPolyVariantReference

        assertNotEmpty(reference.multiResolve(false).filter { it.isValidResult })
    }
}
