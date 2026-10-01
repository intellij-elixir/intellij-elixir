package org.elixir_lang.lowering

import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.beam.psi.BeamFileImpl

/** [ElementLowering] on a decompiled `.beam`'s mirror. */
class BeamMirrorRootTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/type"

    fun testDecompiledMirror() {
        openBeam("queue.beam")
        val mirror = (myFixture.file as BeamFileImpl).mirror

        RootGoldens.assertQuotes("beamMirror", mirror.containingFile)
    }
}
