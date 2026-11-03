package org.elixir_lang.beam

import com.intellij.testFramework.BinaryLightVirtualFile
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.util.indexing.FileContentImpl
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamBytes.bytesOf
import org.elixir_lang.beam.BeamBytes.chunks
import org.elixir_lang.beam.BeamBytes.elixirSystemBeam
import org.elixir_lang.beam.BeamBytes.unsignedInt
import org.elixir_lang.beam.BeamBytes.withChunkField
import org.elixir_lang.beam.BeamBytes.writeUnsignedInt
import org.elixir_lang.beam.chunk.Code
import org.elixir_lang.beam.chunk.imports.Import
import org.elixir_lang.beam.chunk.lines.file_names.Indexer
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.junit.logs.GuardedLoggedErrorProcessor
import org.elixir_lang.onSmallStack
import org.junit.Assert
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/**
 * Corrupt copies of real modules, through every way the plugin reads a BEAM. A corrupt file may be
 * reported through [reportUnexpectedBeamData]; nothing may be thrown out of a reader, and nothing else
 * may be logged as an error.
 */
class CorruptBeamTest : PlatformTestCase() {
    fun testNoCorruptionEscapesAReader() {
        val modules = representativeModules()
        val corrupted = modules.flatMap { (_, bytes) -> chunks(bytes).map { it.id } }.toSet()
        Assert.assertEquals("every decoded chunk kind must be corrupted somewhere", emptySet<String>(), CHUNK_IDS - corrupted)

        val escapes = Escapes(allowReports = true)
        var reads = 0

        for ((label, original) in modules) {
            val random = Random(label.hashCode())

            for ((mutation, bytes) in mutants(original, random)) {
                for ((reader, read) in READERS) {
                    escapes.record(reader, "$label, $mutation") { read(bytes) }
                    reads++
                }
            }
        }

        Assert.assertTrue("only $reads reads; the sweep is not covering the readers", reads > 2000)
        escapes.assertNone("corrupted")
    }

    /** Nothing real may be reported either, so a bound set too tight fails here. */
    fun testEveryRealModuleReadsCleanly() {
        val elixir = SdkBeams.forSdk(System.getenv("ELIXIR_LANG_ELIXIR_PATH"), "elixir")
        val erlang = SdkBeams.forSdk(System.getenv("ERLANG_SDK_HOME"), "erlang")
        // Floors, not counts: Elixir 1.11 onwards ships at least 413 modules and OTP 24 onwards 1247.
        Assert.assertTrue("only ${elixir.size} Elixir modules found", elixir.size > 400)
        Assert.assertTrue("only ${erlang.size} Erlang modules found", erlang.size > 1200)

        val escapes = Escapes(allowReports = false)

        for ((label, file) in elixir + erlang) {
            val bytes = file.readBytes()

            for ((reader, read) in REAL_READERS) {
                escapes.record(reader, label) { read(bytes) }
            }
        }

        escapes.assertNone("real")
    }

    /** An array the JVM refuses whatever the heap, so reading it unbounded fails on any machine. */
    fun testAChunkLongerThanTheFileIsNotAllocated() {
        val content = bytesOf('F', 'O', 'R', '1', unsignedInt(12), 'B', 'E', 'A', 'M', 'A', 't', 'U', '8', unsignedInt(Int.MAX_VALUE))

        val atoms = read(content) { it.atomsResult }

        Assert.assertTrue("a chunk longer than the file is cut off, not missing; got $atoms", atoms is ReadResult.Unreadable)
    }

    /** The chunks before the cut are still there, as `beam_lib` reads them; one not found may be one the cut removed. */
    fun testAFileCutOffInsideAChunkKeepsTheChunksBeforeIt() = assertCutOff { litT -> litT.data + minOf(100, litT.size / 2) }

    /** The chunk list ends cleanly here, so only the form's declared length shows that the rest is missing. */
    fun testAFileCutOffBetweenChunksKeepsTheChunksBeforeIt() = assertCutOff { litT -> litT.header }

    /** The VM refuses a chunk that runs past the form's end, even when the file goes on after it. */
    fun testAChunkCrossingTheFormsEndIsNotReadFromTheBytesAfterIt() {
        val original = elixirSystemBeam()
        val (id, result) = CHUNK_RESULTS.entries.mapNotNull { (id, result) -> chunks(original).lastOrNull { it.id == id }?.let { it to result } }
            .maxBy { (span, _) -> span.data }
            .let { (span, result) ->
                val formEnd = span.data + span.size / 2
                val shortened = original.copyOf().also { writeUnsignedInt(it, 4, formEnd - 8) }

                span.id to read(shortened) { result(it) }
            }

        Assert.assertTrue("$id crosses the form's end, got $result", result is ReadResult.Unreadable && "$result".contains("past its form"))
    }

    /** As in the VM, which reads a form to its declared length and ignores what follows. */
    fun testBytesAfterTheFormAreIgnored() {
        val documentation = read(elixirSystemBeam() + ByteArray(4)) { it.elixirDocumentationResult }

        Assert.assertEquals("no ExDc chunk is written since Elixir 1.7", ReadResult.Absent, documentation)
    }

    /** The form's length counts its `BEAM` type, so a shorter one is a corrupt header, which the VM refuses. */
    fun testAFormShorterThanItsTypeIsNotABeam() =
        Assert.assertNull(read(bytesOf('F', 'O', 'R', '1', unsignedInt(0), 'B', 'E', 'A', 'M')) { true })

    /** Term decoding recurses once per nesting level, and a typed register nests two more terms. */
    fun testLineItemsNestedPastTheStackAreUnreadable() {
        val data = BeamBytes.lineChunkData(lineCount = 1, fileNameCount = 0, ByteArray(200_000) { TYPED_REGISTER })

        val lines = onSmallStack { read(BeamBytes.beam("Line" to data)) { it.linesResult } }

        Assert.assertTrue("got $lines", lines is ReadResult.Unreadable && lines.cause is StackOverflowError)
    }

    /** Inflated whole, this is more bytes than an array can hold, so the header has to be checked first. */
    fun testAGzippedFileThatIsNotABeamIsNotInflated() {
        val zeros = ByteArray(64 shl 20)
        val gzip = gzipped { repeat(35) { write(zeros) } }

        Assert.assertNull(read(gzip) { true })
    }

    /** The largest real BEAM is 1.2 MB, so a form declaring more than the cap is not inflated. */
    fun testAGzippedFormBeyondTheCapIsUnreadable() {
        val beam = elixirSystemBeam().also { writeUnsignedInt(it, 4, (32 shl 20) + 1) }

        val atoms = read(gzipped { write(beam) }) { it.atomsResult }

        Assert.assertTrue("got $atoms", atoms is ReadResult.Unreadable && "$atoms".contains("beyond"))
    }

    /** Like a plain file cut at the same point: what inflated before the cut is still there. */
    fun testAGzippedBeamCutOffMidStreamKeepsTheChunksBeforeTheCut() {
        val gzip = gzipped { write(elixirSystemBeam()) }

        val atoms = read(gzip.copyOf(gzip.size / 2)) { it.atomsResult }

        Assert.assertTrue("AtU8 is the first chunk, got $atoms", atoms is ReadResult.Present)
    }

    fun testAGzippedBeamReads() {
        val beam = elixirSystemBeam()

        val moduleName = read(gzipped { write(beam) }) { it.atomsResult.valueOrNull?.moduleName() }

        Assert.assertEquals("Elixir.System", moduleName)
    }

    fun testADocsChunkThatDoesNotDecodeIsUnreadable() {
        val documentation = read(corruptChunk("Docs")) { it.documentationResult }

        Assert.assertTrue("got $documentation", documentation is ReadResult.Unreadable)
    }

    fun testTheStubBuilderSkipsAModuleWhoseAtomTableIsCorrupt() {
        val (stub, errors) = captureLoggedErrors { BeamFileImpl.buildFileStub(corruptAtomTable(), PATH) }

        Assert.assertNull("a module whose atom table cannot be read has no name to stub", stub)
        assertReported(errors, "AtU8")
    }

    fun testTheStubBuilderKeepsTheDefinitionsOfAModuleWhoseDebugInfoIsCorrupt() {
        val (stub, errors) = captureLoggedErrors { BeamFileImpl.buildFileStub(corruptChunk("Dbgi"), PATH) }

        Assert.assertTrue(
            "the call definitions do not come from Dbgi, got $stub",
            stub?.childrenStubs?.singleOrNull()?.childrenStubs?.isNotEmpty() == true,
        )
        assertReported(errors, "Dbgi")
    }

    fun testTheDecompilerExplainsAModuleWhoseAtomTableIsCorrupt() {
        val (text, errors) = captureLoggedErrors { Decompiler().decompile(file(corruptAtomTable())).toString() }

        Assert.assertTrue(text, text.startsWith("# Decompilation Error: "))
        assertReported(errors, "AtU8")
    }

    fun testTheDecompilerRendersAModuleWhoseDebugInfoIsCorrupt() {
        val (text, errors) = captureLoggedErrors { Decompiler().decompile(file(corruptChunk("Dbgi"))).toString() }

        Assert.assertTrue(text, text.contains("defmodule System do"))
        assertReported(errors, "Dbgi")
    }

    fun testTheLineIndexSkipsAModuleWhoseLineTableIsCorrupt() {
        val bytes = withChunkField(elixirSystemBeam(), "Line", fieldIndex = 0, value = 1)

        val (entries, errors) = captureLoggedErrors { Indexer().map(FileContentImpl.createByContent(file(bytes), bytes)) }

        Assert.assertEquals(emptyMap<String, Void?>(), entries)
        assertReported(errors, "Line")
    }

    fun testAnExportAtomIndexBeyondIntNamesNothing() {
        val exports = read(withChunkField(elixirSystemBeam(), "ExpT", fieldIndex = 1, value = -1)) { it.exportsResult.valueOrNull }

        Assert.assertTrue(
            "an index past the atom table is an unnamed definition, as it always was below Int.MAX_VALUE",
            exports!!.callDefinitionCollection.any { it.atomIndex == 0xFFFFFFFFL && it.name == null },
        )
    }

    fun testAnImportModuleAtomIndexBeyondIntNamesNothing() = assertImportNamesNothing(fieldIndex = 1) { it.moduleName }

    fun testAnImportFunctionAtomIndexBeyondIntNamesNothing() = assertImportNamesNothing(fieldIndex = 2) { it.functionName }

    /** `MAX_ARG` in OTP's `erl_vm.h`. The decompiler builds one parameter name per unit of arity. */
    fun testAnArityBeyondWhatTheVmAllowsIsRefused() {
        val exports = read(withChunkField(elixirSystemBeam(), "ExpT", fieldIndex = 2, value = 256)) { it.exportsResult }

        Assert.assertTrue(
            "got $exports",
            exports is ReadResult.Unreadable && exports.cause is RefusedBeamData && "$exports".contains("arity 256"),
        )
    }

    fun testTheLargestArityTheVmAllowsReads() {
        val exports = read(withChunkField(elixirSystemBeam(), "ExpT", fieldIndex = 2, value = 255)) { it.exportsResult.valueOrNull }

        Assert.assertTrue(exports!!.callDefinitionCollection.any { it.arity == 255L })
    }

    private fun assertImportNamesNothing(fieldIndex: Int, name: (Import) -> String?) {
        val imports = read(withChunkField(elixirSystemBeam(), "ImpT", fieldIndex, value = -1)) { it.importsResult.valueOrNull }

        Assert.assertNull(name(imports!![0]))
    }

    private fun assertCutOff(at: (BeamBytes.ChunkSpan) -> Int) {
        val original = elixirSystemBeam()
        val cut = original.copyOf(at(chunks(original).first { it.id == "LitT" }))

        val (atoms, literals) = read(cut) { it.atomsResult to it.literalsResult }!!

        Assert.assertTrue("AtU8 comes before LitT, got $atoms", atoms is ReadResult.Present)
        Assert.assertTrue("got $literals", literals is ReadResult.Unreadable && "$literals".contains("cut off"))
    }

    private fun assertReported(errors: List<Any>, chunkId: String) {
        Assert.assertTrue("the unreadable $chunkId must be reported, got $errors", errors.any { "$it".contains(chunkId) })
    }

    private fun <T : Any> read(bytes: ByteArray, block: (BeamReader) -> T?): T? = BeamReader.read(bytes, PATH, block)

    private fun gzipped(write: GZIPOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use(write) }.toByteArray()

    private fun corruptAtomTable(): ByteArray = withChunkField(elixirSystemBeam(), "AtU8", fieldIndex = 0, value = Int.MAX_VALUE)

    /** Zeroes the ETF version byte, so the first tag JInterface reads is not a term. */
    private fun corruptChunk(chunkId: String): ByteArray = withChunkField(elixirSystemBeam(), chunkId, fieldIndex = 0, value = 0)

    private class Escapes(private val allowReports: Boolean) {
        private val inputsByKind = sortedMapOf<String, MutableList<String>>()

        fun record(reader: String, input: String, read: () -> Unit) {
            val processor = object : GuardedLoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    t: Throwable?
                ): Set<Action> {
                    val frames = Throwable().stackTrace
                    val reported = frames.any { it.className == REPORTER }

                    if (!reported || !allowReports) {
                        add("$reader ${if (reported) "reported" else "logged"} at ${site(frames)}", input, message)
                    }

                    return Action.NONE
                }
            }

            LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
                try {
                    read()
                } catch (thrown: Throwable) {
                    add("$reader threw ${thrown.javaClass.simpleName} at ${site(thrown.stackTrace)}", input, thrown.message)
                }
            }
        }

        fun assertNone(what: String) {
            Assert.assertTrue(
                inputsByKind.entries.joinToString("\n", "${inputsByKind.size} ways a $what BEAM escaped:\n") { (kind, inputs) ->
                    "${inputs.size} x $kind\n    e.g. ${inputs.first()}"
                },
                inputsByKind.isEmpty(),
            )
        }

        private fun add(kind: String, input: String, message: String?) {
            inputsByKind.getOrPut(kind) { mutableListOf() } += "$input: ${message.orEmpty().lineSequence().firstOrNull().orEmpty().take(200)}"
        }

        /** The plugin frame that threw or logged, skipping the logging wrappers and this test. */
        private fun site(frames: Array<StackTraceElement>): String =
            frames.firstOrNull { frame ->
                frame.className.startsWith("org.elixir_lang.") &&
                    !frame.className.startsWith("org.elixir_lang.errorreport.") &&
                    !frame.className.startsWith(CorruptBeamTest::class.java.name) &&
                    frame.className != REPORTER
            }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "no plugin frame"
    }

    private companion object {
        const val PATH = "Elixir.Corrupt.beam"
        const val FLIPS_PER_CHUNK = 12
        const val TRUNCATIONS = 12

        /** Extended tag 5, a typed register: two more terms, each of which can be another. */
        const val TYPED_REGISTER: Byte = 0x57

        val REPORTER: String = Class.forName("org.elixir_lang.beam.UnexpectedBeamDataKt").name

        /** Rich modules first; [representativeModules] adds the smallest carrier of any kind they lack. */
        val NAMED = listOf("Elixir.System.beam", "lists.beam", "gen_server.beam")

        val CHUNK_RESULTS: Map<String, (BeamReader) -> ReadResult<*>> = mapOf(
            "AtU8" to { it.atomsResult }, "Attr" to { it.attributesResult }, "CInf" to { it.compileInfoResult },
            "Code" to { it.codeResult }, "Dbgi" to { it.debugInfoResult }, "Docs" to { it.documentationResult },
            "ExpT" to { it.exportsResult }, "FunT" to { it.functionsResult }, "ImpT" to { it.importsResult },
            "Line" to { it.linesResult }, "LitT" to { it.literalsResult }, "LocT" to { it.localsResult },
            "StrT" to { it.stringsResult },
        )

        val CHUNK_IDS = setOf(
            "AtU8", "Attr", "CInf", "Code", "Dbgi", "Docs", "ExpT", "FunT", "ImpT", "Line", "LitT", "LocT", "StrT",
        )

        val READERS: List<Pair<String, (ByteArray) -> Unit>> = listOf(
            "stub builder" to { bytes -> BeamFileImpl.buildFileStub(bytes, PATH) },
            "decompiler" to { bytes -> Decompiler().decompile(file(bytes)) },
            "Line index" to { bytes -> Indexer().map(FileContentImpl.createByContent(file(bytes), bytes)) },
            "chunk viewer" to { bytes -> CachedBeamReader.from(file(bytes))?.let { readEverything(it, assembly = true) } },
        )

        val REAL_READERS: List<Pair<String, (ByteArray) -> Unit>> = listOf(
            "stub builder" to { bytes -> BeamFileImpl.buildFileStub(bytes, PATH) },
            "Line index" to { bytes -> Indexer().map(FileContentImpl.createByContent(file(bytes), bytes)) },
            "chunk viewer" to { bytes -> CachedBeamReader.from(file(bytes))?.let { readEverything(it, assembly = false) } },
        )

        fun file(bytes: ByteArray) = BinaryLightVirtualFile(PATH, bytes)

        fun readEverything(cache: CachedBeamReader, assembly: Boolean) {
            listOf(
                cache.atoms, cache.attributes, cache.compileInfo, cache.debugInfo, cache.elixirDocumentation,
                cache.exports, cache.functions, cache.lines, cache.literals, cache.locals, cache.strings,
            )
            cache.imports?.let { imports ->
                repeat(imports.size()) { listOf(imports[it].moduleName, imports[it].functionName) }
            }
            if (assembly) {
                cache.assembly(Code.Options())
            } else {
                cache.code
            }
        }

        fun representativeModules(): List<Pair<String, ByteArray>> {
            val beams = (SdkBeams.forSdk(System.getenv("ELIXIR_LANG_ELIXIR_PATH"), "elixir") +
                SdkBeams.forSdk(System.getenv("ERLANG_SDK_HOME"), "erlang"))
                .map { it.label to it.file.readBytes() }
            val named = beams.filter { (label, _) -> label.substringAfterLast('/') in NAMED }
            Assert.assertEquals("modules named for the sweep", NAMED.size, named.size)

            val present = named.flatMap { (_, bytes) -> chunks(bytes).map { it.id } }.toSet()
            val carriers = (CHUNK_IDS - present).mapNotNull { id ->
                beams.filter { (_, bytes) -> chunks(bytes).any { it.id == id } }.minByOrNull { it.second.size }
            }

            return (named + carriers).distinctBy { it.first }
        }

        fun mutants(original: ByteArray, random: Random): List<Pair<String, ByteArray>> = buildList {
            for ((id, header, data, size) in chunks(original)) {
                if (size > 0) {
                    repeat(FLIPS_PER_CHUNK) {
                        val at = data + random.nextInt(size)
                        val flipped = (original[at].toInt() xor (1 + random.nextInt(255))).toByte()
                        add("$id byte ${at - data} flipped" to original.copyOf().also { it[at] = flipped })
                    }
                }
                if (size >= 4) {
                    add("$id leading int maxed" to original.copyOf().also { writeUnsignedInt(it, data, -1) })
                }
                val shortened = random.nextInt(size + 1)
                add("$id size shortened to $shortened" to original.copyOf().also { writeUnsignedInt(it, header + 4, shortened) })
            }

            repeat(TRUNCATIONS) {
                val length = random.nextInt(original.size)
                add("truncated to $length bytes" to original.copyOf(length))
            }
        }
    }
}
