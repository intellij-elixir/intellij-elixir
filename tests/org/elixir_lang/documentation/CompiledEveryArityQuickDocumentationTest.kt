package org.elixir_lang.documentation

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.beam.psi.impl.ModuleImpl
import java.io.File

/**
 * A compiled function's documentation shows every arity of its name too, with debug info (read from its decompiled
 * source) and without (read from its docs chunk). `build.exs` rebuilds the fixture.
 */
class CompiledEveryArityQuickDocumentationTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/beam/every_arity"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
    }

    fun testEveryArityIsShownWithDebugInfo() {
        val (documentation, anchor) = documentationAndAnchor("EveryArity")

        assertEveryArityShown(documentation)
        assertNotNull("Expected an anchor", anchor)
        assertEquals("Expected a link per head, got: $documentation", 3, linkedIds(documentation).distinct().size)
        assertTrue(
            "Expected the entry anchored to be the called arity's, got: $documentation",
            documentation.substringAfter("""id="$anchor"""").substringBefore("id=\"").contains("<b>push(a)</b>")
        )
    }

    fun testEveryArityIsShownFromTheDocsChunk() {
        val (documentation, anchor) = documentationAndAnchor("EveryArityDocsOnly")

        assertEveryArityShown(documentation)
        assertEquals("Expected a link per function, got: $documentation", 2, linkedIds(documentation).distinct().size)
        assertTrue(
            "Expected the entry anchored to hold the defaults' doc, got: $documentation",
            documentation.substringAfter("""id="$anchor"""").substringBefore("id=\"").contains("One or two.")
        )
    }

    /** From outside the module, a private arity is not listed and one marked `@doc false` is not either. */
    fun testAHiddenAndAPrivateArityAreNotListedWithDebugInfo() {
        assertOnlyVisibleArities(documentationAndAnchor("EveryArityHidden").first)
    }

    fun testAHiddenArityIsNotListedFromTheDocsChunk() {
        assertOnlyVisibleArities(documentationAndAnchor("EveryArityHiddenDocsOnly").first)
    }

    // Quick documentation reads the decompiled source first, which writes `@doc false` itself; this is the `Docs` chunk's own answer.
    fun testTheDocsChunkListsNoHiddenArityBesideTheOthers() {
        assertEquals(listOf(1, 3), docsChunkArities(arity = 1))
    }

    fun testTheDocsChunkListsAHiddenArityAskedAboutDirectly() {
        assertEquals(listOf(1, 2, 3), docsChunkArities(arity = 2))
    }

    private fun docsChunkArities(arity: Int): List<Int> {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(ebinDirectory, "Elixir.EveryArityHiddenDocsOnly.beam"))!!
        val module = (PsiManager.getInstance(project).findFile(virtualFile) as PsiCompiledFile).children.single() as ModuleImpl<*>
        val definition = module.callDefinitions().single {
            it.nameArityInterval.name == "push" && it.nameArityInterval.arityInterval.minimum == arity
        }
        val docs = BeamDocsHelper.fetchDocs(definition) as FetchedDocs.Arities

        return docs.entries.map { it.arities.minimum }
    }

    private fun assertOnlyVisibleArities(documentation: String) {
        assertTrue("Expected the visible arities, got: $documentation", "Visible one." in documentation && "Visible three." in documentation)
        assertEquals("Expected a link per visible arity, got: $documentation", 2, linkedIds(documentation).distinct().size)
        assertFalse("Expected no entry for push(a, b, c, d), got: $documentation", "push(a, b, c, d)" in documentation)
        assertFalse("Expected no entry for push(a, b), got: $documentation", "push(a, b)" in documentation)
    }

    private fun assertEveryArityShown(documentation: String) {
        val order = listOf("One or two.", "Three.").map { documentation.indexOf(it) }

        assertTrue("Expected both arities, got: $documentation", order.all { it >= 0 })
        assertEquals("Expected ascending arity, got: $documentation", order.sorted(), order)
        assertFalse("Expected no other function, got: $documentation", "Other." in documentation)
        assertTrue("Expected links to the entries, got: $documentation", linkedIds(documentation).isNotEmpty())
        for (id in linkedIds(documentation)) {
            assertTrue("Expected an entry with id $id, got: $documentation", """id="$id"""" in documentation)
        }
    }

    private fun linkedIds(documentation: String): List<String> =
        Regex("""href="#([^"]+)"""").findAll(documentation).map { it.groupValues[1] }.toList()

    /** The documentation and anchor for a call of the module's `push/1`. */
    private fun documentationAndAnchor(module: String): Pair<String, String?> {
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  def calls(a), do: $module.pu<caret>sh(a)\nend\n")
        // Let the library's roots change and refresh finish before a gesture's background read waits on them.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        return myFixture.quickDocumentationAtCaret(project).orEmpty() to myFixture.documentationAnchorAtCaret(project)
    }
}
