package org.elixir_lang.code_insight

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.LegSdk

/**
 * With Run the expander on and the leg's SDK, a call typed past where the expansion stops is answered from the name
 * index, and Parameter Info, shown while the call is typed, describes every arity the index holds of the name.
 */
class StoppedModuleParameterInfoTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        LegSdk.heldUntil(module, testRootDisposable)
        enableExpander(testRootDisposable)
        // `import Foo, only: [Foo, g: 1]` raises, as `only:` takes a keyword list, so the expansion stops there.
        myFixture.addFileToProject("foo.ex", "defmodule Foo do\n  def g(x), do: x\nend\n")
    }

    /** An `import` past the stop may bring in any module's `add`, so the module's own `add/2` is one of those shown. */
    fun testALocalCallBeingTypedPastAStopShowsTheArityItWillHave() =
        assertContainsElements(
            shown(ADDER),
            "augend, addend",
        )

    /** Of the `add`s the index holds past the stop, the module's own comes first, as Elixir calls it where it is defined. */
    fun testALocalCallBeingTypedPastAStopShowsTheModulesOwnArityFirst() {
        myFixture.addFileToProject("other.ex", "defmodule Other do\n  def add(left, right), do: left + right\nend\n")

        assertEquals(
            "augend, addend",
            shown(ADDER).firstOrNull(),
        )
    }

    /** The `alias` past the stop is missing from the env, so the index is read for each module `Processor` may name. */
    fun testARemoteCallBeingTypedPastAStopShowsTheArityItWillHave() =
        assertEquals(
            listOf("item, opts"),
            shown(
                """
                defmodule Some.Processor do
                  def process(item, opts), do: {item, opts}
                end

                defmodule Caller do
                  import Foo, only: [Foo, g: 1]
                  alias Some.Processor

                  def calls(x), do: Processor.process(x<caret>)
                end
                """.trimIndent()
            )
        )

    private fun shown(text: String): List<String> = myFixture.parameterInfoSignaturesIn("stopped.ex", text)

    private companion object {
        val ADDER =
            """
            defmodule Adder do
              import Foo, only: [Foo, g: 1]

              def run, do: add(<caret>)

              def add(augend, addend), do: augend + addend
            end
            """.trimIndent()
    }
}
