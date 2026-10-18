package org.elixir_lang.documentation

import org.elixir_lang.beam.BeamLibraryTestCase

/** An undocumented delegation to a compiled function shows that function's docs, as one to a source function does. */
class CompiledDelegationQuickDocumentationTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/module"

    fun testAnUndocumentedDelegationToACompiledFunctionShowsItsDocs() {
        myFixture.configureByText(
            "delegates_to_compiled.ex",
            """
            defmodule DelegatesToCompiled do
              defdelegate eval_string(string, binding, opts), to: Code
            end

            defmodule CallsDelegation do
              def run, do: DelegatesToCompiled.eval_st<caret>ring("1", [], [])
            end
            """.trimIndent()
        )

        val documentation = myFixture.quickDocumentationAtCaret(project)

        assertTrue("Expected Code.eval_string's docs, got: $documentation", documentation.orEmpty().contains("Evaluates the contents given by"))
    }
}
