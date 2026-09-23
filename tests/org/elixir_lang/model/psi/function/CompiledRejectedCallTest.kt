package org.elixir_lang.model.psi.function

import com.intellij.find.usages.impl.searchTargets
import org.elixir_lang.beam.BeamLibraryTestCase

/** A call at an arity a compiled module does not export is offered the function it names, as a source one is. */
@Suppress("UnstableApiUsage")
class CompiledRejectedCallTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/type"

    fun testAWrongArityCallOfACompiledModuleOffersItsFunction() {
        myFixture.configureByText(
            "calls_compiled.ex",
            "defmodule CallsCompiled do\n  def run(q), do: :queue.sn<caret>oc(q)\nend\n"
        )

        assertEquals(
            listOf("def snoc"),
            searchTargets(myFixture.file, myFixture.caretOffset).map { it.presentation().presentableText.substringBefore('(') }
        )
    }
}
