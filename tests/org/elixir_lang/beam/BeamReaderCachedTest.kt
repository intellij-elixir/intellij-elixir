package org.elixir_lang.beam

import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.BinaryLightVirtualFile
import org.elixir_lang.Module
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.documentation.CompiledBuild
import org.elixir_lang.documentation.OV_FULL_LIB
import org.elixir_lang.documentation.addCompiledLibrary
import java.io.IOException
import java.io.InputStream

/** [BeamReader.Cached] keeps its reader on a `.beam` until the file's content changes. */
class BeamReaderCachedTest : PlatformTestCase() {
    /** A local file's timestamp is what `modificationCount` reads, and a rewrite can keep it. */
    fun testABeamRewrittenUnderItsOldTimestampIsReadAgain() {
        val directory = myFixture.addCompiledLibrary(OV_FULL_LIB, WITH_DEBUG_INFO, WITHOUT_DEBUG_INFO)
        val file = directory.findChild("${Module.atom(WITH_DEBUG_INFO.name)}.beam")!!
        assertNotNull(BeamReader.Cached.from(file)!!.debugInfo)

        val rewritten = directory.findChild("${Module.atom(WITHOUT_DEBUG_INFO.name)}.beam")!!.contentsToByteArray()
        WriteAction.run<Throwable> { file.setBinaryContent(rewritten, -1, file.timeStamp) }

        assertNull(BeamReader.Cached.from(file)!!.debugInfo)
    }

    fun testAFailedReadIsReadAgain() {
        val directory = myFixture.addCompiledLibrary(OV_FULL_LIB, WITH_DEBUG_INFO)
        val beam = directory.findChild("${Module.atom(WITH_DEBUG_INFO.name)}.beam")!!
        val file = UnreadableBeam(beam.name, beam.contentsToByteArray())
        assertNull(BeamReader.Cached.from(file))

        file.unreadable = false

        assertNotNull(BeamReader.Cached.from(file))
    }

    companion object {
        private val WITH_DEBUG_INFO = CompiledBuild("BeamReaderCachedDebugInfo")
        private val WITHOUT_DEBUG_INFO = CompiledBuild("BeamReaderCachedNoDebugInfo", debugInfo = false)
    }
}

/** A `.beam` whose reads fail while [unreadable]. */
internal class UnreadableBeam(name: String, bytes: ByteArray) : BinaryLightVirtualFile(name, bytes) {
    var unreadable = true

    override fun getInputStream(): InputStream = if (unreadable) throw IOException("unreadable") else super.getInputStream()
}
