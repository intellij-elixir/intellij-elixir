package org.elixir_lang.reference

import org.elixir_lang.beam.BeamLibraryTestCase
import java.io.File

/** Completion of an Erlang function's name in a capture, `&:mod.name/arity`. */
class ErlangCaptureCompletionTest : BeamLibraryTestCase() {
    override val ebinDirectory: File = File("testData/org/elixir_lang/model/psi/type/ebin").absoluteFile

    fun testOffersEachNameOnceAndInsertsItBare() {
        myFixture.assertCaptureCompletion(
            capture("atom_to_<caret>"),
            listOf("atom_to_binary", "atom_to_list"),
            "atom_to_list",
            capture("atom_to_list")
        )
    }

    private fun capture(name: String): String = "defmodule Test do\n  def run do\n    &:erlang.$name/1\n  end\nend"
}
