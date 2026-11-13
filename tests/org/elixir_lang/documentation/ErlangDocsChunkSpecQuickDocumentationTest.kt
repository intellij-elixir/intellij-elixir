package org.elixir_lang.documentation

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import org.elixir_lang.beam.BeamLibraryFixture
import java.io.File

/**
 * An Erlang module's `Docs` chunk, as OTP 23 to 26 write it, records each `-spec` in its `signature` metadata, which
 * Quick Documentation shows where the module has no debug info to read one from. The `queue.beam` here is
 * `erlang_atom_qualifier_hover_otp23`'s, its `Dbgi` chunk removed and its `doc/chunks/queue.chunk` added as its `Docs`
 * chunk.
 */
class ErlangDocsChunkSpecQuickDocumentationTest : QuickDocumentationTestCase() {
    override fun setUp() {
        super.setUp()

        val ebin = File(testDataPath).absoluteFile
        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, ebin.path)
        BeamLibraryFixture.addLibrary(
            project,
            myFixture.module,
            "erlang_docs_chunk_spec_otp23",
            listOf(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ebin)!!)
        )
    }

    fun testAFunctionWithoutDebugInfoShowsTheSpecItsDocsChunkRecords() {
        myFixture.configureByFiles("function_hover.ex")

        val hover = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected cons/2's spec, got: $hover", "@spec cons(" in hover)
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/documentation/erlang_docs_chunk_spec_otp23"
}
