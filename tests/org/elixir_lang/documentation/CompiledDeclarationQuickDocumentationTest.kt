package org.elixir_lang.documentation

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.beam.BeamLibraryTestCase

/** Quick Documentation at the name of a `def` in a decompiled `.beam` shows that function's documentation. */
class CompiledDeclarationQuickDocumentationTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/beam/every_arity"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(testRootDisposable)
    }

    fun testADefHeadInADecompiledModuleShowsItsDoc() {
        openBeamAndMoveCaretTo("Elixir.EveryArity.beam", "def pus")
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val documentation = myFixture.quickDocumentationAtCaret(project)

        assertTrue("Expected every arity's @doc, got: $documentation", documentation?.contains("One or two.") == true)
        assertTrue("Expected every arity's @doc, got: $documentation", documentation!!.contains("Three."))
        assertFalse("Expected no other function, got: $documentation", documentation.contains("Other."))
    }
}
