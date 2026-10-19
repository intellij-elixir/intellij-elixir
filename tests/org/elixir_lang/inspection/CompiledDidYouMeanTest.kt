package org.elixir_lang.inspection

import org.elixir_lang.beam.BeamLibraryTestCase

/** The suggestions for a remote call to a compiled module, as for a source one. */
class CompiledDidYouMeanTest : BeamLibraryTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(References::class.java)
    }

    fun testAMisspelledCallToACompiledModuleIsOfferedTheNameItIsNearTo() {
        myFixture.configureByText("m.ex", "defmodule U do\n  def run(q, x), do: :queue.sno(q, x)\nend\n")

        val reports = myFixture.doHighlighting()
            .filter { it.inspectionToolId == "ElixirReferences" && it.text == ":queue.sno(q, x)" }
            .map { it.description }

        assertEquals(listOf("Does not resolve to anything. Did you mean: snoc/2?"), reports)
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/inspection/references_wrong_arity_message"
}
