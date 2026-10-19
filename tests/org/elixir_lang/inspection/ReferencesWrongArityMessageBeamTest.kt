package org.elixir_lang.inspection

import org.elixir_lang.beam.BeamLibraryTestCase

/**
 * The arities the message names include those of a declaration compiled into a `.beam` library, as Erlang's `:queue`
 * stands in for any compiled module.
 */
class ReferencesWrongArityMessageBeamTest : BeamLibraryTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(References::class.java)
    }

    fun testARemoteCallToACompiledModuleNamesTheArityItsNameIsExportedAt() {
        assertEquals(
            INVALID + "snoc/2",
            reportOn(":queue.snoc(q)", "defmodule U do\n  def run(q), do: :queue.snoc(q)\nend\n")
        )
    }

    fun testAnImportedCompiledArityIsNamed() {
        assertEquals(
            INVALID + "len/1",
            reportOn("len(q, 1)", "defmodule U do\n  import :queue\n\n  def run(q), do: len(q, 1)\nend\n")
        )
    }

    /** A local `lenx` only starts with `len`; the exact compiled name is the one named. */
    fun testAnExactCompiledNameIsNamedOverASourcePrefix() {
        assertEquals(
            INVALID + "len/1",
            reportOn(
                "len(q, 1)",
                "defmodule U do\n  import :queue\n\n  def lenx(a), do: a\n\n  def run(q), do: len(q, 1)\nend\n"
            )
        )
    }

    private fun reportOn(call: String, text: String): String {
        myFixture.configureByText("m.ex", text)

        val reports = myFixture.doHighlighting()
            .filter { it.inspectionToolId == "ElixirReferences" && it.text == call }
            .map { it.description }

        return reports.singleOrNull() ?: throw AssertionError("Expected one report on `$call`, found $reports")
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/inspection/references_wrong_arity_message"

    private companion object {
        const val INVALID = "Only resolves to invalid results; defined as "
    }
}
