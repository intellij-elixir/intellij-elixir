package org.elixir_lang.beam

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.FileContent
import org.elixir_lang.beam.chunk.*
import org.elixir_lang.beam.chunk.Chunk.Companion.length
import org.elixir_lang.beam.chunk.Chunk.Companion.typeID
import org.elixir_lang.beam.chunk.Chunk.Companion.unsignedInt
import org.elixir_lang.beam.chunk.Chunk.TypeID.*
import org.elixir_lang.beam.chunk.beam_documentation.Documentation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

typealias CachedBeamReader = BeamReader.Cached

/**
 * The one way into a BEAM file's chunks, and only inside [read] or [readResult]: whatever corrupt input makes a
 * decoder, or the block's own work, throw is contained there, and whether it is reported is decided once for the
 * whole read.
 */
class BeamReader private constructor(private val beam: Beam, val path: String, private val reporting: Boolean) {
    val atomsResult: ReadResult<Atoms> by lazy { contain(beam.atomChunkId) { beam.atoms() } }
    val attributesResult: ReadResult<Keyword> by lazy { contain(ATTR.toString()) { beam.attributes() } }
    val codeResult: ReadResult<Code> by lazy { contain(CODE.toString()) { beam.code(::warn) } }
    val compileInfoResult: ReadResult<Keyword> by lazy { contain(CINF.toString()) { beam.compileInfo() } }
    val debugInfoResult: ReadResult<DebugInfo> by lazy { contain(DBGI.toString()) { beam.debugInfo() } }
    val documentationResult: ReadResult<Documentation> by lazy { contain(DOCS.toString()) { beam.documentation() } }
    val elixirDocumentationResult: ReadResult<ElixirDocumentation> by lazy {
        contain(EXDC.toString()) { beam.elixirDocumentation() }
    }
    val exportsResult: ReadResult<CallDefinitions> by lazy {
        contain(EXPT.toString()) { beam.callDefinitions(EXPT, atomsResult.valueOrNull) }
    }
    val functionsResult: ReadResult<Functions> by lazy { contain(FUNT.toString()) { beam.functions(atomsResult.valueOrNull) } }
    val importsResult: ReadResult<Imports> by lazy { contain(IMPT.toString()) { beam.imports(atomsResult.valueOrNull) } }
    val linesResult: ReadResult<Lines> by lazy { contain(LINE.toString()) { beam.lines() } }
    val literalsResult: ReadResult<Literals> by lazy { contain(LITT.toString()) { beam.literals() } }
    val localsResult: ReadResult<CallDefinitions> by lazy {
        contain(LOCT.toString()) { beam.callDefinitions(LOCT, atomsResult.valueOrNull) }
    }
    val stringsResult: ReadResult<Strings> by lazy { contain(STRT.toString()) { beam.strings() } }

    val atoms: Atoms? by lazy { reported(atomsResult) }
    val attributes: Keyword? by lazy { reported(attributesResult) }
    val code: Code? by lazy { reported(codeResult) }
    val compileInfo: Keyword? by lazy { reported(compileInfoResult) }
    val debugInfo: DebugInfo? by lazy { reported(debugInfoResult) }
    val documentation: Documentation? by lazy { reported(documentationResult) }
    val elixirDocumentation: ElixirDocumentation? by lazy { reported(elixirDocumentationResult) }
    val exports: CallDefinitions? by lazy { reported(exportsResult) }
    val functions: Functions? by lazy { reported(functionsResult) }
    val imports: Imports? by lazy { reported(importsResult) }
    val lines: Lines? by lazy { reported(linesResult) }
    val literals: Literals? by lazy { reported(literalsResult) }
    val locals: CallDefinitions? by lazy { reported(localsResult) }
    val strings: Strings? by lazy { reported(stringsResult) }

    fun chunkCollection(): Collection<Chunk> = beam.chunkCollection()

    private fun <T : Any> reported(read: ReadResult<T>): T? = if (reporting) read.orReported(path) else read.valueOrNull

    private fun warn(message: String) {
        if (reporting) LOGGER.reportUnexpectedBeamData("$path: $message")
    }

    companion object {
        private val LOGGER = Logger.getInstance(BeamReader::class.java)

        /**
         * Runs [block] on a reader of [content]. Null when the bytes are not a BEAM at all, which is ordinary input,
         * or when the block fails on corrupt data, which is reported like an unreadable chunk.
         */
        fun <T : Any> read(content: ByteArray, path: String, block: (BeamReader) -> T?): T? =
            open(content, path, reporting = true)?.let { reader -> reader.reported(contain("reading") { block(reader) }) }

        fun <T : Any> read(fileContent: FileContent, block: (BeamReader) -> T?): T? =
            read(fileContent.content, fileContent.file.path, block)

        fun <T : Any> read(virtualFile: VirtualFile, block: (BeamReader) -> T?): T? =
            bytes(virtualFile)?.let { read(it, virtualFile.path, block) }

        /**
         * [read] that hands back what went wrong instead of reporting it, for SDK detection: a corrupt BEAM in an SDK
         * home is the user's file rather than the plugin's fault.
         */
        fun <T : Any> readResult(content: ByteArray, path: String, block: (BeamReader) -> T?): ReadResult<T>? =
            open(content, path, reporting = false)?.let { reader -> contain("reading") { block(reader) } }

        fun <T : Any> readResult(virtualFile: VirtualFile, block: (BeamReader) -> T?): ReadResult<T>? =
            bytes(virtualFile)?.let { readResult(it, virtualFile.path, block) }

        private fun open(content: ByteArray, path: String, reporting: Boolean): BeamReader? =
            Beam.from(content, path)?.let { BeamReader(it, path, reporting) }

        private fun bytes(virtualFile: VirtualFile): ByteArray? =
            try {
                virtualFile.inputStream.use { it.readAllBytes() }
            } catch (_: IOException) {
                null
            }
    }

    /** A reader kept on its file until the file changes, for the chunk viewer and the Code view. */
    class Cached private constructor(private val reader: BeamReader) {
        val atoms: Atoms? get() = reader.atoms
        val attributes: Keyword? get() = reader.attributes
        val code: Code? get() = reader.code
        val compileInfo: Keyword? get() = reader.compileInfo
        val debugInfo: DebugInfo? get() = reader.debugInfo
        val elixirDocumentation: ElixirDocumentation? get() = reader.elixirDocumentation
        val exports: CallDefinitions? get() = reader.exports
        val functions: Functions? get() = reader.functions
        val imports: Imports? get() = reader.imports
        val lines: Lines? get() = reader.lines
        val literals: Literals? get() = reader.literals
        val locals: CallDefinitions? get() = reader.locals
        val strings: Strings? get() = reader.strings

        private val assemblyFailureReported = AtomicBoolean()

        fun chunkCollection(): Collection<Chunk> = reader.chunkCollection()

        /** The Code view asks again on every change of options, and a chunk that fails once fails every time. */
        fun assembly(options: Code.Options): String? =
            when (val assembly = contain("Code assembly") { code?.assembly(this, options) }) {
                is ReadResult.Unreadable -> {
                    if (assemblyFailureReported.compareAndSet(false, true)) reader.reported(assembly)
                    null
                }
                else -> assembly.valueOrNull
            }

        companion object {
            private val KEY = Key.create<Pair<Long, Cached>>("beam.cache")

            fun from(virtualFile: VirtualFile): Cached? =
                virtualFile.keptUntilChanged(KEY) {
                    bytes(virtualFile)?.let { open(it, virtualFile.path, reporting = true) }?.let(::Cached)
                }
        }
    }
}

/**
 * The value kept on [this] under [key] while its content is unchanged, else [compute]'s, which is kept in turn unless it
 * is `null`.
 */
internal fun <T> VirtualFile.keptUntilChanged(key: Key<Pair<Long, T & Any>>, compute: () -> T): T {
    // `modificationCount` is a local file's timestamp, which a rewrite can keep.
    val stamp = modificationStamp
    getUserData(key)?.let { (kept, value) -> if (kept == stamp) return value }

    return compute().also { putUserData(key, it?.let { value -> stamp to value }) }
}

/**
 * See http://beam-wisdoms.clau.se/en/latest/indepth-beam-file.html
 *
 * [refusal] is why a chunk not found is not an absence: the file is cut off, or was not read at all.
 */
private class Beam private constructor(chunkCollection: Collection<Chunk>, private val refusal: String?) {
    private val chunkByTypeID: Map<String, Chunk> = chunkCollection.associateBy { it.typeID }

    val atomChunkId: String
        get() = if (chunkByTypeID.containsKey(ATOM.toString())) ATOM.toString() else ATU8.toString()

    fun atoms(): Atoms? =
        chunkByTypeID[ATOM.toString()]?.let { Atoms.from(it, ATOM, Charsets.ISO_8859_1) }
            ?: chunk(ATU8)?.let { Atoms.from(it, ATU8, Charsets.UTF_8) }

    fun attributes(): Keyword? = chunk(ATTR)?.let(::from)
    fun callDefinitions(typeID: Chunk.TypeID, atoms: Atoms?): CallDefinitions? =
        chunk(typeID)?.let { CallDefinitions.from(it, typeID, atoms) }

    fun chunkCollection(): Collection<Chunk> = chunkByTypeID.values
    fun code(warn: (String) -> Unit): Code? = chunk(CODE)?.let { Code.from(it, warn) }
    fun compileInfo(): Keyword? = chunk(CINF)?.let(::from)
    fun debugInfo(): DebugInfo? = chunk(DBGI)?.let { org.elixir_lang.beam.chunk.debug_info.from(it) }
    fun documentation(): Documentation? = chunk(DOCS)?.let { Documentation.from(it) }
    fun elixirDocumentation(): ElixirDocumentation? = chunk(EXDC)?.let { ElixirDocumentation.from(it) }
    fun functions(atoms: Atoms?): Functions? = chunk(FUNT)?.let { Functions.from(it, atoms) }
    fun imports(atoms: Atoms?): Imports? = chunk(IMPT)?.let { Imports.from(it, atoms) }
    fun lines(): Lines? = chunk(LINE)?.let { Lines.from(it) }
    fun literals(): Literals? = chunk(LITT)?.let { Literals.from(it) }
    fun strings(): Strings? = chunk(STRT)?.let { Strings.from(it) }

    private fun chunk(typeID: Chunk.TypeID): Chunk? =
        chunkByTypeID[typeID.toString()] ?: refusal?.let { throw RefusedBeamData(it) }

    companion object {
        private val LOGGER = Logger.getInstance(Beam::class.java)

        private const val GZIP_FIRST_UNSIGNED_BYTE = 0x1f
        private const val GZIP_SECOND_UNSIGNED_BYTE = 0x8b
        private const val HEADER = "FOR1"

        /** `FOR1` and the length of the form that follows. */
        private const val FORM_HEADER_BYTE_COUNT = 8

        /** The largest real BEAM is 1.2 MB, gzipped or not; releases before Elixir 1.14 gzip every BEAM they strip. */
        private const val MAX_GUNZIPPED_FORM_BYTES = 32L * 1024 * 1024

        fun from(content: ByteArray, path: String): Beam? =
            if (isGzipped(content)) gunzipped(content, path) else fromForm(content, path)

        /**
         * The file type gates on the extension alone, so every `*.beam` reaches us whether or not its bytes
         * are one - rejecting those is not something the user can act on, hence debug.
         */
        private fun fromForm(content: ByteArray, path: String): Beam? {
            val dataInputStream = DataInputStream(ByteArrayInputStream(content))
            val header: String?

            try {
                header = typeID(dataInputStream)
            } catch (ioException: IOException) {
                LOGGER.debug("Could not read header from BEAM DataInputStream from $path", ioException)
                return null
            }

            if (HEADER != header) {
                // Guarded because the message stats the file, which is not free on the reject path.
                if (header != null && !testCase(header) && LOGGER.isDebugEnabled) {
                    LOGGER.debug(
                            "header typeID ($header) did not match expected ($HEADER) from $path. " +
                                    "There are ${dataInputStream.available()} bytes available on the " +
                                    "dataInputStream. File size is ${File(path).length()} bytes."
                    )
                }
                return null
            }

            val formLength: Long?

            try {
                formLength = length(dataInputStream)
            } catch (ioException: IOException) {
                LOGGER.debug("Could not read length from BEAM DataInputStream from $path", ioException)
                return null
            }

            val section: String?

            try {
                section = typeID(dataInputStream)
            } catch (ioException: IOException) {
                LOGGER.debug("Could not read section header from BEAM DataInputStream from $path", ioException)
                return null
            }


            if ("BEAM" != section) {
                LOGGER.debug("Section header is not BEAM in $path")
                return null
            }

            // The form's length counts the `BEAM` type before the chunks.
            val chunkByteCount = formLength?.minus(4)?.takeIf { it >= 0 } ?: run {
                LOGGER.debug("Form length ($formLength) is shorter than its BEAM type in $path")
                return null
            }
            val cutOff = chunkByteCount > dataInputStream.available()
            var runsPastForm = false
            // As in the VM, a chunk must end within the form, and bytes after the form are ignored.
            val form = DataInputStream(ByteArrayInputStream(content, content.size - dataInputStream.available(), minOf(chunkByteCount, dataInputStream.available().toLong()).toInt()))
            val chunkList = ArrayList<Chunk>()
            val i = 1

            while (form.available() > 0) {
                val chunk: Chunk?

                try {
                    chunk = Chunk.from(form)
                } catch (ioException: IOException) {
                    LOGGER.debug(
                            "Could not read chunk number $i from BEAM DataInputStream from $path.  Returning truncated Beam object",
                            ioException
                    )
                    runsPastForm = true
                    break
                }

                if (chunk != null) {
                    chunkList.add(chunk)
                } else {
                    runsPastForm = true
                    break
                }
            }

            val refusal = when {
                cutOff -> "missing from a file that is cut off"
                runsPastForm -> "missing from a file whose chunks run past its form"
                else -> null
            }

            return Beam(chunkList, refusal)
        }

        private fun isGzipped(content: ByteArray): Boolean =
            content.size >= 2 &&
                (content[0].toInt() and 0xFF) == GZIP_FIRST_UNSIGNED_BYTE &&
                (content[1].toInt() and 0xFF) == GZIP_SECOND_UNSIGNED_BYTE

        /** The header is checked before anything else is inflated, and nothing past the form's declared length is read. */
        private fun gunzipped(content: ByteArray, path: String): Beam? =
            try {
                GZIPInputStream(ByteArrayInputStream(content)).use { stream ->
                    val header = stream.readNBytes(FORM_HEADER_BYTE_COUNT)
                    val formLength = header
                        .takeIf { it.size == FORM_HEADER_BYTE_COUNT && String(it, 0, 4, Charsets.ISO_8859_1) == HEADER }
                        ?.let { unsignedInt(it, 4).first }

                    when {
                        formLength == null -> null
                        formLength > MAX_GUNZIPPED_FORM_BYTES ->
                            Beam(emptyList(), "the gzipped form declares $formLength bytes, beyond the $MAX_GUNZIPPED_FORM_BYTES byte cap")
                        else -> fromForm(header + stream.readUpTo(formLength.toInt()), path)
                    }
                }
            } catch (_: IOException) {
                null
            }

        /** A stream cut off part way still holds what inflated before the cut, as a plain file cut there does. */
        private fun InputStream.readUpTo(byteCount: Int): ByteArray {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)

            try {
                while (bytes.size() < byteCount) {
                    val read = read(buffer, 0, minOf(buffer.size, byteCount - bytes.size()))
                    if (read < 0) break
                    bytes.write(buffer, 0, read)
                }
            } catch (_: EOFException) {
            }

            return bytes.toByteArray()
        }

        private fun testCase(header: String?): Boolean = header == "baz "
    }
}
