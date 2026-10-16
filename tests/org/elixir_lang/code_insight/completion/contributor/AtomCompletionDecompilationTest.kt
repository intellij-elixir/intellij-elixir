package org.elixir_lang.code_insight.completion.contributor

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.psi.call.Call
import java.io.File

/**
 * Completing an atom lists compiled modules from the index; a module is decompiled only when something navigates to it.
 */
class AtomCompletionDecompilationTest : BeamLibraryTestCase() {
    override val ebinDirectory: File = File("testData/org/elixir_lang/beam/quoted_atom_module/ebin").absoluteFile

    fun testCompletingAtomDoesNotBuildCompiledModuleMirror() {
        val beamFile = beamFile("foo-bar.beam")
        assertNull("The fixture's mirror was built before completion", beamFile.cachedMirror)

        configureAtomPrefix("fo")
        myFixture.complete(CompletionType.BASIC)

        assertNull("Completing an atom decompiled foo-bar.beam", beamFile.cachedMirror)
    }

    fun testCompletingAliasDoesNotBuildCompiledModuleMirror() {
        val beamFile = beamFile("Elixir.QuotedAtomFoo.beam")

        configureSource("def f, do: QuotedAtom<caret>")
        myFixture.complete(CompletionType.BASIC)

        assertNull("Completing an alias decompiled Elixir.QuotedAtomFoo.beam", beamFile.cachedMirror)
    }

    fun testCompletingRemoteFunctionDoesNotBuildOtherCompiledModuleMirrors() {
        val beamFile = beamFile("foo-bar.beam")

        configureSource("def f, do: QuotedAtomFoo.<caret>")
        myFixture.complete(CompletionType.BASIC)

        assertNull("Completing a function of one module decompiled foo-bar.beam", beamFile.cachedMirror)
    }

    fun testCompiledModuleIsOfferedUnderItsAtomSpellings() {
        configureAtomPrefix()
        val module = compiledModuleLookupElement()

        assertEquals(":\"foo-bar\"", module.lookupString)
        assertEquals(setOf(":\"foo-bar\"", ":foo-bar"), module.allLookupStrings.filter { it.contains("foo-bar") }.toSet())
    }

    fun testCompiledModuleLookupElementNavigatesToItsDecompiledSource() {
        configureAtomPrefix()
        val navigationElement = compiledModuleLookupElement().psiElement!!.navigationElement

        assertTrue("Expected a call in the decompiled mirror, got $navigationElement", navigationElement is Call)
        assertSame(
            beamFile("foo-bar.beam").decompiledPsiFile,
            navigationElement.containingFile,
        )
    }

    private fun configureAtomPrefix(typed: String = "") = configureSource("def f, do: :$typed<caret>")

    private fun configureSource(body: String) {
        myFixture.configureByText(
            "completion.ex",
            """
            defmodule User do
              $body
            end
            """.trimIndent(),
        )
    }

    /** The offered `foo-bar` module, read from the popup that `:` opens. */
    private fun compiledModuleLookupElement(): LookupElement =
        myFixture.complete(CompletionType.BASIC)!!.single { it.lookupString == ":\"foo-bar\"" }

    private fun beamFile(name: String): BeamFileImpl {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(ebinDirectory, name))!!

        return PsiManager.getInstance(project).findFile(virtualFile) as BeamFileImpl
    }
}
