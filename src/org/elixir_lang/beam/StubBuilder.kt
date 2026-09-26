package org.elixir_lang.beam

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.stubs.BinaryFileStubBuilder
import com.intellij.psi.stubs.Stub
import com.intellij.util.indexing.FileContent
import org.elixir_lang.beam.psi.BeamFileImpl.Companion.buildFileStub

internal class StubBuilder : BinaryFileStubBuilder {
    /**
     * @param file a .beam file
     * @return `true` (accepts all files because it is only registered for BEAM file type
     */
    override fun acceptsFile(file: VirtualFile): Boolean = true

    override fun buildStubTree(fileContent: FileContent): Stub? {
        val content = fileContent.content
        val stub = buildFileStub(content, fileContent.file.path)

        return if (stub != null) {
            stub
        } else {
            LOGGER.debug("No stub built for file $fileContent")
            null
        }
    }

    override fun getStubVersion(): Int = STUB_VERSION

    companion object {
        private val LOGGER = Logger.getInstance(StubBuilder::class.java)
        private const val STUB_VERSION = 6
    }
}
