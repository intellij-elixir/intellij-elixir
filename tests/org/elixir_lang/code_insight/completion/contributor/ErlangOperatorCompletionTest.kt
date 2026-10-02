package org.elixir_lang.code_insight.completion.contributor

import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.completeCandidateAtCaret
import java.io.File

/** A function whose name Elixir can only call quoted is offered under its name and inserted quoted. */
class ErlangOperatorCompletionTest : BeamLibraryTestCase() {
    override val ebinDirectory: File = File("testData/org/elixir_lang/model/psi/type/ebin").absoluteFile

    fun testOperatorFunctionInsertsQuotedRemoteCall() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Test do
                  def run do
                    :erlang.<caret>
                  end
                end
            """.trimIndent()
        )

        assertEquals(
            """
                defmodule Test do
                  def run do
                    :erlang."=:="(_A, _B)
                  end
                end
            """.trimIndent(),
            myFixture.completeCandidateAtCaret("=:=", '\n')
        )
    }
}
