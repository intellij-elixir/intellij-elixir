package org.elixir_lang.model.psi.atom

import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret

/** Find Usages of a function of a module named by a quoted atom reaches an atom naming it in an MFA. */
@Suppress("UnstableApiUsage")
class QuotedModuleAtomUsagesTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/atom/quoted_module"

    fun testFromTheCompiledDefinition() {
        myFixture.addFileToProject("caller.ex", CALLER)
        openBeamAndMoveCaretTo("a-b.beam", "def f")

        assertEquals(listOf(USE), usages())
    }

    fun testFromASourceDefinitionOfTheSameModule() {
        myFixture.addFileToProject("caller.ex", CALLER)
        myFixture.configureByText("a_b.ex", "defmodule :\"a-b\" do\n  def <caret>f, do: 1\nend\n")

        assertEquals(listOf(USE), usages())
    }

    private fun usages(): List<String> =
        myFixture.singleTargetPsiUsagesAtCaret(project)
            .filterNot { it.declaration }
            .map { "${it.file.name}:${it.range.startOffset}" }

    private companion object {
        const val CALLER = "defmodule Caller do\n  def run, do: apply(:\"a-b\", :f, [])\nend\n"
        val USE = "caller.ex:${CALLER.indexOf(":f,") + 1}"
    }
}
