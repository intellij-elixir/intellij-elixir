package org.elixir_lang.beam

import org.elixir_lang.documentation.quickDocumentationAtCaret
import java.io.File

/** A compiled module named by an atom that starts `Elixir.` but is not an alias keeps the whole atom as its name. */
class ElixirPrefixedAtomModuleTest : BeamLibraryTestCase() {
    override val ebinDirectory: File = File("testData/org/elixir_lang/beam/elixir_prefixed_atom_module/ebin").absoluteFile

    fun testDecompiledHeadWritesTheWholeAtom() {
        openBeam("Elixir.foo.beam")

        val text = myFixture.editor.document.text

        assertTrue("Expected `defmodule :\"Elixir.foo\" do` in:\n$text", text.contains("defmodule :\"Elixir.foo\" do"))
    }

    fun testQuickDocumentationNamesTheModuleByItsAtom() {
        myFixture.configureByText(
            "quick_doc.ex",
            """
            defmodule Caller do
              def run do
                :"Elixir.foo".hel<caret>lo()
              end
            end
            """.trimIndent()
        )

        val documentation = myFixture.quickDocumentationAtCaret(project)

        assertNotNull("Quick Documentation should be shown for a documented compiled function", documentation)
        assertTrue(
            "Expected the module header to name the atom, got: $documentation",
            documentation!!.contains("<i>module</i> <b>:\"Elixir.foo\"</b>")
        )
    }
}
