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
