package org.elixir_lang.code_insight

import org.elixir_lang.PlatformTestCase

/** What a use shows when it names no one declaration, or names a declaration that is not a definition. */
class UseParameterInfoTest : PlatformTestCase() {
    /** `Mod.unquote(name)(|)` can reach any function of `Mod`, so it describes none of them. */
    fun testAQualifiedUnquotedNameShowsNothing() =
        assertEquals(
            emptyList<String>(),
            shown(
                """
                defmodule Many do
                  def one(a), do: a
                  def two(a, b), do: {a, b}
                end

                defmodule Caller do
                  defmacro calls(name) do
                    quote do
                      Many.unquote(name)(name<caret>)
                    end
                  end
                end
                """.trimIndent()
            )
        )

    /** A `@callback` declares no function body, and the definition beside it is the call's one signature. */
    fun testACallbackIsNotASignature() =
        assertEquals(
            listOf("name"),
            shown(
                """
                defmodule Greeter do
                  @callback greet(person :: String.t()) :: String.t()

                  def greet(name), do: name

                  def calls(a), do: greet(a<caret>)
                end
                """.trimIndent()
            )
        )

    /** Parameter Info is shown while the call is typed, so a function is described at every arity it has. */
    fun testEveryArityOfTheNameIsShown() =
        assertEquals(
            listOf("item", "item, opts"),
            shown(
                """
                defmodule Processor do
                  def process(item), do: item
                  def process(item, opts), do: {item, opts}

                  def calls(x), do: process(x<caret>)
                end
                """.trimIndent()
            )
        )

    private fun shown(text: String): List<String> {
        myFixture.configureByText("use.ex", text)

        return myFixture.parameterInfoSignaturesAtCaret()
    }
}
