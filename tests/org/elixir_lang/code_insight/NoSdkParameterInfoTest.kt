package org.elixir_lang.code_insight

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.enableExpander

/**
 * With Run the expander on and no SDK, the expander answers a call from the name index, and Parameter Info, shown
 * while the call is typed, describes every arity the index holds of the name.
 */
class NoSdkParameterInfoTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        enableExpander(testRootDisposable)
    }

    fun testALocalCallBeingTypedShowsTheArityItWillHave() =
        assertEquals(
            listOf("augend, addend"),
            shown(ADDER)
        )

    /** Of the `add`s the index holds, the module's own comes first, as Elixir calls it where it is defined. */
    fun testALocalCallBeingTypedShowsTheModulesOwnArityFirst() {
        myFixture.addFileToProject("other.ex", "defmodule Other do\n  def add(left, right), do: left + right\nend\n")

        assertEquals(
            listOf("augend, addend", "left, right"),
            shown(ADDER)
        )
    }

    fun testARemoteCallBeingTypedShowsEveryArity() =
        assertEquals(
            listOf("item", "item, opts"),
            shown(
                """
                defmodule Processor do
                  def process(item), do: item
                  def process(item, opts), do: {item, opts}
                end

                defmodule Caller do
                  def calls(x), do: Processor.process(x<caret>)
                end
                """.trimIndent()
            )
        )

    /** What a `quote` unquotes runs in the macro, in the module the `quote` is written in, whose private functions it calls. */
    fun testACallAQuoteUnquotesShowsItsModulesPrivateFunction() =
        assertEquals(
            listOf("x"),
            shown(
                """
                defmodule Macros do
                  defp helper(x), do: x

                  defmacro m do
                    quote do
                      unquote(helper(1<caret>))
                    end
                  end
                end
                """.trimIndent()
            )
        )

    /** What an `unquote` escapes runs where the `quote` is, not in a module the `quote` writes around it. */
    fun testACallAQuoteUnquotesInAModuleItWritesShowsTheMacrosPrivateFunction() =
        assertEquals(
            listOf("x"),
            shown(
                """
                defmodule Macros do
                  defp helper(x), do: x

                  defmacro m do
                    quote do
                      defmodule Inner do
                        def owner, do: unquote(helper(1<caret>))
                      end
                    end
                  end
                end
                """.trimIndent()
            )
        )

    private fun shown(text: String): List<String> = myFixture.parameterInfoSignaturesIn("no_sdk.ex", text)

    private companion object {
        val ADDER =
            """
            defmodule Adder do
              def run, do: add(<caret>)

              def add(augend, addend), do: augend + addend
            end
            """.trimIndent()
    }
}
