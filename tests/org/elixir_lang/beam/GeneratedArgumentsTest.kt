package org.elixir_lang.beam

import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiManager
import com.intellij.usageView.UsageViewNodeTextLocation
import org.elixir_lang.beam.psi.CallDefinition
import org.elixir_lang.beam.psi.impl.ModuleImpl
import org.elixir_lang.code_insight.Signature
import org.elixir_lang.documentation.BeamDocsHelper
import org.elixir_lang.documentation.ElixirDocumentationProvider
import org.elixir_lang.documentation.FetchedDocs
import java.io.File

/**
 * Parameter names for a `.beam` definition whose source names were not kept: Elixir's `arg1`...`argN`, or the names of
 * the `Docs` entry whose defaults cover its arity.
 */
class GeneratedArgumentsTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/beam/generated_arguments"

    // No debug info and no `Docs`

    fun testDecompiledHeadsWithoutNames() {
        assertSameElements(
            heads("Elixir.NoNames.beam"),
            "def f(arg1, arg2)",
            "defmacro m(arg1)",
            "def __info__(arg1)",
            "def module_info()",
            "def module_info(arg1)",
            "defp unquote(:\"-inlined-__info__/1-\")(arg1)"
        )
    }

    fun testSignatureWithoutNames() {
        assertEquals(listOf("arg1", "arg2"), Signature.of(definition("Elixir.NoNames.beam", "f", 2)).parameters)
    }

    fun testCompletionInsertsGeneratedNames() {
        assertEquals("NoNames.f(arg1, arg2)", insertedCall("NoNames", "f", 2))
    }

    fun testUsageViewTextWithoutNames() {
        assertEquals("def f(arg1, arg2), do: ...", usageViewText(definition("Elixir.NoNames.beam", "f", 2)))
    }

    // `Docs` without debug info: a lower arity the defaults cover takes the entry's names

    fun testDecompiledHeadsOfLowerAritiesTheDefaultsCover() {
        assertSameElements(
            heads("Elixir.DocsDefaults.beam").filter { it.contains("snoc(") || it.contains(" f(") },
            "defmacro snoc(q)",
            "defmacro snoc(q, x \\\\ nil)",
            "def f(c)",
            "def f(a, c)",
            "def f(a \\\\ 1, b \\\\ 2, c)"
        )
    }

    fun testSignatureOfALowerArityADefaultCovers() {
        assertEquals(listOf("q"), Signature.of(definition("Elixir.DocsDefaults.beam", "snoc", 1)).parameters)
    }

    // `f(a \\ 1, b \\ 2, c)` called with two arguments binds `a` and `c`: Elixir fills the last defaults first.
    fun testSignatureOfALowerArityDropsTheLastDefaultsFirst() {
        assertEquals(listOf("a", "c"), Signature.of(definition("Elixir.DocsDefaults.beam", "f", 2)).parameters)
        assertEquals(listOf("c"), Signature.of(definition("Elixir.DocsDefaults.beam", "f", 1)).parameters)
    }

    fun testCompletionInsertsTheNamesOfTheEntryTheDefaultsCover() {
        assertEquals("DocsDefaults.snoc(q)", insertedCall("DocsDefaults", "snoc", 1))
    }

    fun testCompletionInsertsTheNamesLeftAfterTheLastDefaultsDrop() {
        assertEquals("DocsDefaults.f(a, c)", insertedCall("DocsDefaults", "f", 2))
    }

    fun testUsageViewTextOfALowerArityADefaultCovers() {
        assertEquals("defmacro snoc(q), do: ...", usageViewText(definition("Elixir.DocsDefaults.beam", "snoc", 1)))
    }

    fun testUsageViewTextWithDocsNames() {
        assertEquals(
            "def f(a \\\\ 1, b \\\\ 2, c), do: ...",
            usageViewText(definition("Elixir.DocsDefaults.beam", "f", 3))
        )
    }

    // `g/1` fills both of `g/3`'s defaults, so it is `g/3`'s, although `g/2` is the next arity up.

    fun testDecompiledHeadOfALowerArityTakesTheEntryItsDefaultsFill() {
        assertSameElements(
            heads("Elixir.DocsDefaults.beam").filter { it.contains(" g(") },
            "def g(a)",
            "def g(a, b)",
            "def g(a, b \\\\ 1, c \\\\ 2)"
        )
    }

    fun testQuickDocumentationOfALowerArityShowsTheEntryItsDefaultsFill() {
        myFixture.configureByText("usage.ex", "defmodule Usage do\n  def usage, do: DocsDefaults.<caret>g(1)\nend\n")

        val documentation = ElixirDocumentationProvider().generateDoc(myFixture.elementAtCaret, null)

        assertNotNull("Expected documentation for DocsDefaults.g/1", documentation)
        assertTrue("Expected g/3's @doc, got: $documentation", documentation!!.contains("Takes three."))
        assertFalse("Expected not g/2's @doc, got: $documentation", documentation.contains("Takes two."))
    }

    // Quick documentation reads the decompiled source first; this is the `Docs` chunk's answer when that has none.
    fun testBeamDocsOfALowerArityAreTheEntryItsDefaultsFill() {
        val docs = BeamDocsHelper.fetchDocs(definition("Elixir.DocsDefaults.beam", "g", 1))

        assertEquals(
            listOf("g(a, b \\\\ 1, c \\\\ 2)"),
            (docs as FetchedDocs.FunctionOrMacroDocumentation).heads
        )
    }

    private fun compiledFile(beamName: String): PsiCompiledFile {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(ebinDirectory, beamName))
        assertNotNull("Could not find $beamName in VFS", virtualFile)

        return PsiManager.getInstance(project).findFile(virtualFile!!) as PsiCompiledFile
    }

    private fun heads(beamName: String): List<String> =
        compiledFile(beamName)
            .mirror
            .text
            .lines()
            .filter { HEAD.matches(it) }
            .map { it.trim().removeSuffix(" do") }

    private fun definition(beamName: String, name: String, arity: Int): CallDefinition =
        (compiledFile(beamName).children.single() as ModuleImpl<*>)
            .callDefinitions()
            .single { it.nameArityInterval.name == name && it.nameArityInterval.arityInterval.minimum == arity }

    private fun usageViewText(definition: CallDefinition): String =
        ElementDescriptionUtil.getElementDescription(definition, UsageViewNodeTextLocation.INSTANCE)

    /** Completes `module.` and accepts [name] at [arity], returning the call it inserted. */
    private fun insertedCall(module: String, name: String, arity: Int): String {
        myFixture.configureByText("usage.ex", "defmodule Usage do\n  def usage, do: $module.<caret>\nend\n")

        val candidates = myFixture.completeBasic()
        assertNotNull("Expected a completion lookup to open, but a single candidate was auto-inserted", candidates)
        val candidate = candidates!!.singleOrNull { candidate ->
            (candidate.psiElement as? CallDefinition)?.nameArityInterval?.let {
                it.name == name && it.arityInterval.minimum == arity
            } == true
        }
        assertNotNull("Expected one $name/$arity candidate, got ${candidates.map { it.lookupString }}", candidate)

        (myFixture.lookup as LookupImpl).currentItem = candidate
        myFixture.finishLookup('\t')

        return myFixture.file.text.lines()[1].substringAfter("def usage, do: ")
    }

    private companion object {
        val HEAD = Regex("^  (def|defp|defmacro|defmacrop) .*")
    }
}
