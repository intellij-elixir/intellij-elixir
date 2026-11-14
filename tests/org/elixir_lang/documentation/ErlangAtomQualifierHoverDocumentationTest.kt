package org.elixir_lang.documentation

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import org.elixir_lang.beam.BeamLibraryFixture
import java.io.File

/**
 * Tests that Quick Doc / hover documentation works for Erlang modules and functions
 * referenced via atom qualifier syntax (`:module` and `:module.function()`).
 *
 * Uses a real `queue.beam` file (OTP 27 with embedded docs) as a library class root.
 *
 * Covers Case 11 from issue #2691: Quick Doc for Erlang modules/functions via atom.
 *
 * Drives the real Ctrl+Q gesture at the caret (see [QuickDocumentationTestCase]) rather than
 * hand-resolving the atom/call reference, so it locks the user-visible behaviour.
 *
 * @see ErlangAtomQualifierHoverDocumentationOTP23Test for the OTP 23 decompiled-mirror variant
 */
class ErlangAtomQualifierHoverDocumentationTest : QuickDocumentationTestCase() {
    override fun setUp() {
        super.setUp()
        addBeamLibrary()
    }

    fun testModuleAtomHoverShowsModuleDocs() {
        myFixture.configureByFiles("module_atom_hover.ex")

        val hover = quickDocumentationAtCaret()
        assertNotNull("Hover documentation is null", hover)

        assertTrue("Expected module definition for :queue", hover!!.contains("<i>module</i> <b>:queue</b>"))
        assertTrue(
            "Expected non-empty module documentation content",
            hover.contains("This module provides (double-ended) FIFO queues")
        )
    }

    fun testModuleAtomHoverShowsModuleDocsWithTheExpanderOn() = withTheExpanderOn(::testModuleAtomHoverShowsModuleDocs)

    fun testAtomQualifiedFunctionHoverShowsSpecHead() {
        myFixture.configureByFiles("function_hover.ex")

        val hover = quickDocumentationAtCaret()
        assertNotNull("Hover documentation is null", hover)

        assertTrue("Expected module definition for :queue", hover!!.contains("<i>module</i> <b>:queue</b>"))
        assertTrue("Expected function doc body", hover.contains("Inserts"))
    }

    /**
     * Rendering a function whose docs chunk carries `deprecated` metadata once threw
     * `kotlin.NotImplementedError` from a bare `TODO()` (issue #2412), until "Implement Deprecated
     * metadata handling for docs from BEAM files" shipped in v13.1.1. `:queue.lait/1` supplies that
     * metadata as an `OtpErlangBinary`, the one shape the renderer converts rather than logs for.
     */
    fun testDeprecatedFunctionHoverShowsDeprecatedSection() {
        myFixture.configureByFiles("deprecated_function_hover.ex")

        val hover = quickDocumentationAtCaret()
        assertNotNull("Hover documentation is null", hover)

        assertTrue("Expected module definition for :queue", hover!!.contains("<i>module</i> <b>:queue</b>"))
        assertTrue(
            "Expected a Deprecated section header for :queue.lait/1",
            hover.contains(DocumentationMarkup.SECTION_HEADER_START + "Deprecated")
        )
        assertTrue(
            "Expected the deprecation text from the docs chunk metadata",
            hover.contains("queue:lait/1 is deprecated")
        )
    }

    private fun addBeamLibrary() {
        val beamFile = File(testDataPath, "queue.beam")
        assertTrue("queue.beam not found at ${beamFile.absolutePath}", beamFile.exists())

        val beamDir = beamFile.parentFile.absolutePath
        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, beamDir)

        val beamDirVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(beamDir))
        assertNotNull("Could not find beam test data directory: $beamDir", beamDirVf)

        BeamLibraryFixture.addLibrary(project, myFixture.module, LIBRARY_NAME, listOf(beamDirVf!!))
    }

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/documentation/erlang_atom_qualifier_hover"

    companion object {
        private const val LIBRARY_NAME = "erlang_hover_test_lib"
    }
}
