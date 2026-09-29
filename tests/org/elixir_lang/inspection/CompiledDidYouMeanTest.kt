package org.elixir_lang.inspection

import com.intellij.lang.annotation.HighlightSeverity
import org.elixir_lang.beam.BeamLibraryTestCase

/** A misspelled call of a compiled module is offered what the module exports near it. */
class CompiledDidYouMeanTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/type"

    fun testAMisspelledCallOfACompiledModuleOffersItsExports() {
        myFixture.configureByText(
            "uses_compiled.ex",
            """
            defmodule UsesCompiled do
              def run(q, x), do: :queue.sno(q, x)
            end
            """.trimIndent()
        )
        myFixture.enableInspections(References())

        val said = myFixture.doHighlighting(HighlightSeverity.ERROR)
            .filter { it.text.startsWith(":queue.sno") }
            .mapNotNull { it.description }
            .distinct()

        assertEquals(listOf("Does not resolve to anything. Did you mean: snoc/2?"), said)
    }
}
