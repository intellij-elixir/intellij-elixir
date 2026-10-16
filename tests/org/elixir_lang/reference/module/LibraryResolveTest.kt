package org.elixir_lang.reference.module

import com.intellij.openapi.application.ReadAction
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.reference.Module
import java.io.File

/** What a module alias naming a compiled library module resolves to. */
class LibraryResolveTest : BeamLibraryTestCase() {
    override val ebinDirectory: File = File("testData/org/elixir_lang/goto_decompiled/ebin").absoluteFile

    /** The `alias` the use was reached through stays among the results, but is not what the use names. */
    fun testAnAliasedCompiledModuleResolvesToItsModule() {
        myFixture.configureByText("use.ex", "defmodule U do\n  alias GotoRelated.Lib\n\n  def run, do: <caret>Lib\nend\n")

        val resolved = ReadAction.computeBlocking<String?, Throwable> {
            assertInstanceOf(myFixture.file.findReferenceAt(myFixture.caretOffset), Module::class.java)
                .resolve()
                ?.containingFile
                ?.name
        }

        assertEquals("Elixir.GotoRelated.Lib.beam", resolved)
    }
}
