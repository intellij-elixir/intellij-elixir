package org.elixir_lang.documentation

import org.elixir_lang.beam.BeamLibraryTestCase

/** The Ctrl+hover text for a call, a module alias and a call of a compiled function. */
class DocumentationHintTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/type"

    fun testHintAtACall() {
        myFixture.configureByText(
            "hint.ex",
            """
            defmodule Documented do
              @doc "Adds."
              def add(a, b), do: a + b
            end

            defmodule Caller do
              def calls, do: Documented.ad<caret>d(1, 2)
            end
            """.trimIndent()
        )

        assertEquals("function \"add\" [hint.ex]", myFixture.documentationHintAtCaret(project))
    }

    fun testHintAtAModuleAlias() {
        myFixture.configureByText(
            "hint.ex",
            """
            defmodule Documented do
            end

            defmodule Caller do
              def calls, do: Documen<caret>ted
            end
            """.trimIndent()
        )

        assertEquals("module \"Documented\" [hint.ex]", myFixture.documentationHintAtCaret(project))
    }

    fun testHintAtACallOfACompiledFunction() {
        myFixture.configureByText(
            "hint.ex",
            """
            defmodule Caller do
              def calls, do: :queue.ne<caret>w()
            end
            """.trimIndent()
        )

        val hint = myFixture.documentationHintAtCaret(project).orEmpty()

        assertTrue(hint, hint.startsWith("function \"") && hint.endsWith("\" [queue.beam]"))
    }
}
