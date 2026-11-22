package org.elixir_lang.beam

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.Module
import org.elixir_lang.NameArity
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.CompiledOrigin
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Definer
import org.elixir_lang.documentation.CompiledBuild
import org.elixir_lang.documentation.OV_FULL_LIB
import org.elixir_lang.documentation.addCompiledLibrary
import org.elixir_lang.expander.CompiledDefaults

/**
 * [CompiledIdentity] and [CompiledDefaults] over one module the leg's Elixir compiles, whose `defoverridable` override
 * drops the defaults of the function it overrides: debug info keeps the generated arity as a definition of its own,
 * while `Docs` merges the override into the original's entry, defaults and all.
 */
class CompiledIdentityTest : PlatformTestCase() {
    fun testWithBothChunksADefaultArityIsTheFunctionDocsRecords() =
        assertEquals(NameArity("o", 2), CompiledIdentity.of(beam(BOTH), "o", 1))

    fun testWithDocsOnlyADefaultArityIsTheFunctionDocsRecords() =
        assertEquals(NameArity("o", 2), CompiledIdentity.of(beam(DOCS_ONLY), "o", 1))

    /** The debug info keeps `o/1` as a definition of its own, whose one clause `elixir_def` generated to call `o/2`. */
    fun testWithDebugInfoOnlyADefaultArityIsTheFunctionItsClauseCalls() =
        assertEquals(NameArity("o", 2), CompiledIdentity.of(beam(DEBUG_INFO_ONLY), "o", 1))

    /** As an Erlang export: neither chunk holds Elixir's definitions, so the export is a function of its own. */
    fun testAnExportNeitherChunkPlacesIsAFunctionOfItsOwn() {
        val origin = CompiledOrigin(beam(OTHER_LANGUAGE), OTHER_LANGUAGE.name, "o", 1)

        assertEquals(NameArity("o", 1), Declaration("o", ArityKnowledge.Exact(1), Definer.DEF.capabilities, Declared.Compiled(origin)).identity())
    }

    /** A `.beam` rewritten in place is read again: what its earlier bytes placed does not answer for the new ones. */
    fun testARewrittenBeamIsReadAgain() = assertRewrittenBeamIsReadAgain { file, bytes -> file.setBinaryContent(bytes) }

    /** A local file's timestamp is what `modificationCount` reads, and a rewrite can keep it. */
    fun testABeamRewrittenUnderItsOldTimestampIsReadAgain() =
        assertRewrittenBeamIsReadAgain { file, bytes -> file.setBinaryContent(bytes, -1, file.timeStamp) }

    fun testAFailedReadIsReadAgain() {
        val beam = beam(BOTH)
        val file = UnreadableBeam(beam.name, beam.contentsToByteArray())
        assertEquals(NameArity("o", 1), CompiledIdentity.of(file, "o", 1))

        file.unreadable = false

        assertEquals(NameArity("o", 2), CompiledIdentity.of(file, "o", 1))
    }

    fun testWithBothChunksTheDefaultsAreTheDebugInfos() =
        assertEquals(mapOf(NameArity("o", 2) to 0, NameArity("o", 1) to 0), defaults(BOTH))

    fun testWithDebugInfoOnlyTheDefaultsAreTheDebugInfos() =
        assertEquals(mapOf(NameArity("o", 2) to 0, NameArity("o", 1) to 0), defaults(DEBUG_INFO_ONLY))

    fun testWithDocsOnlyTheDefaultsAreDocs() =
        assertEquals(mapOf(NameArity("o", 2) to 1), defaults(DOCS_ONLY))

    /** [BOTH]'s `.beam`, once read, gives [OTHER_LANGUAGE]'s identity after [rewrite] writes that `.beam`'s bytes over it. */
    private fun assertRewrittenBeamIsReadAgain(rewrite: (VirtualFile, ByteArray) -> Unit) {
        val directory = myFixture.addCompiledLibrary(OV_FULL_LIB, BOTH, OTHER_LANGUAGE)
        val file = directory.findChild("${Module.atom(BOTH.name)}.beam")!!
        assertEquals(NameArity("o", 2), CompiledIdentity.of(file, "o", 1))

        val erlang = directory.findChild("${Module.atom(OTHER_LANGUAGE.name)}.beam")!!.contentsToByteArray()
        WriteAction.run<Throwable> { rewrite(file, erlang) }

        assertEquals(NameArity("o", 1), CompiledIdentity.of(file, "o", 1))
    }

    private fun defaults(build: CompiledBuild): Map<NameArity, Int>? =
        BeamReader.read(beam(build)) { CompiledDefaults.of(it) }

    /** The `.beam` of [build] alone, compiled with the chunks its name keeps. */
    private fun beam(build: CompiledBuild): VirtualFile {
        val directory = myFixture.addCompiledLibrary(OV_FULL_LIB, build)

        return directory.findChild("${Module.atom(build.name)}.beam")!!
    }

    companion object {
        private val BOTH = CompiledBuild("CompiledIdentityBoth")
        private val DEBUG_INFO_ONLY = CompiledBuild("CompiledIdentityDebugInfo", docs = false)
        private val DOCS_ONLY = CompiledBuild("CompiledIdentityDocs", debugInfo = false)
        private val OTHER_LANGUAGE = CompiledBuild("CompiledIdentityOtherLanguage", debugInfo = false, docsLanguage = "erlang")
    }
}
