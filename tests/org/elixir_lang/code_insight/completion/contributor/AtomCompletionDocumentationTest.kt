package org.elixir_lang.code_insight.completion.contributor

import com.intellij.codeInsight.completion.CompletionType
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.documentation.ElixirDocumentationProvider
import java.io.File

/** A compiled module offered by atom completion shows the documentation of its decompiled source. */
class AtomCompletionDocumentationTest : BeamLibraryTestCase() {
    override val ebinDirectory: File = File("testData/org/elixir_lang/model/psi/module/ebin").absoluteFile

    fun testCompiledModuleLookupElementShowsTheSameDocumentationAsItsDecompiledSource() {
        myFixture.configureByText(
            "completion.ex",
            """
            defmodule User do
              def f, do: :"Elixir.<caret>"
            end
            """.trimIndent(),
        )
        val candidates = myFixture.complete(CompletionType.BASIC)!!
        val element = candidates.singleOrNull { it.lookupString == "Code" }?.psiElement
            ?: throw AssertionError("No Code candidate in ${candidates.map { it.lookupString }}")
        val provider = ElixirDocumentationProvider()

        val documentation = provider.generateDoc(element, null)

        assertNotNull("Expected documentation for Code", documentation)
        assertEquals(documentation, provider.generateDoc(element.navigationElement, null))
    }
}
