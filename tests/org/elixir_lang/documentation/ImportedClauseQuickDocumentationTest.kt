package org.elixir_lang.documentation

import com.intellij.openapi.util.text.StringUtil

/** A use that reaches a clause another way than the module's own definitions do still shows that clause once. */
class ImportedClauseQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testAnImportedFunctionsSpecIsShownOnce() = assertSpecOnce(IMPORTING)

    fun testAnImportedFunctionsSpecIsShownOnceWithTheExpanderOn() = withTheExpanderOn { assertSpecOnce(IMPORTING) }

    fun testAnImportedFunctionsDeprecationIsShown() = assertDeprecated()

    fun testAnImportedFunctionsDeprecationIsShownWithTheExpanderOn() = withTheExpanderOn(::assertDeprecated)

    /** Elixir doesn't compile `Bar`, whose `import` raises, so the use is past a stop. */
    fun testASpecPastAStopIsShownOnceWithTheExpanderOn() = withTheExpanderOn {
        assertSpecOnce("defmodule Bar do\n  import Foo, only: [Foo, g: 1]\n  def h, do: <caret>g(1)\nend\n")
    }

    /** A local call in a `quote` may not call `U`'s `helper/1`, so its find is marked where the module's is not. */
    fun testAQuotedLocalCallsSpecIsShownOnceWithTheExpanderOn() = withTheExpanderOn {
        val documentation = quickDocumentationIn(
            "u.ex",
            "defmodule U do\n  @doc \"Helps.\"\n  @spec helper(integer) :: integer\n  def helper(x), do: x\n\n" +
                "  defmacro __using__(_) do\n    quote do\n      def f(y), do: hel<caret>per(y)\n    end\n  end\nend\n"
        )

        assertTrue("Expected helper's docs, got: $documentation", "Helps." in documentation)
        assertEquals("Expected the spec once, got: $documentation", 1, StringUtil.getOccurrenceCount(documentation, "helper(integer) :: integer"))
    }

    private fun assertSpecOnce(bar: String) {
        myFixture.addFileToProject("foo.ex", "defmodule Foo do\n  @doc \"Gs.\"\n  @spec g(integer) :: integer\n  def g(x), do: x\nend\n")
        val documentation = quickDocumentationIn("bar.ex", bar)

        assertTrue("Expected g's docs, got: $documentation", "Gs." in documentation)
        assertEquals("Expected the spec once, got: $documentation", 1, StringUtil.getOccurrenceCount(documentation, "g(integer) :: integer"))
    }

    private fun assertDeprecated() {
        myFixture.addFileToProject("foo.ex", "defmodule Foo do\n  @deprecated \"Use f/1.\"\n  def g(x), do: x\nend\n")
        val documentation = quickDocumentationIn("bar.ex", IMPORTING)

        assertTrue("Expected the deprecation, got: $documentation", "Use f/1." in documentation)
    }

    private companion object {
        const val IMPORTING = "defmodule Bar do\n  import Foo\n  def h, do: <caret>g(1)\nend\n"
    }
}
