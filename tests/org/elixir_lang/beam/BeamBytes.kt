package org.elixir_lang.beam

import org.elixir_lang.beam.chunk.Chunk
import org.junit.Assert
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File

/** Byte-level surgery on BEAM files, for tests that need one specific corruption. */
object BeamBytes {
    /** `FOR1` + total size + `BEAM`. */
    const val HEADER_BYTE_COUNT = 12

    /** Chunk id + chunk size. */
    const val CHUNK_HEADER_BYTE_COUNT = 8

    data class ChunkSpan(val id: String, val header: Int, val data: Int, val size: Int)

    fun elixirSystemBeam(): ByteArray {
        val root = System.getenv("ELIXIR_LANG_ELIXIR_PATH")
        Assert.assertNotNull("ELIXIR_LANG_ELIXIR_PATH not set for the test JVM", root)
        return File(root, "lib/elixir/ebin/Elixir.System.beam").readBytes()
    }

    /** A BEAM file holding [chunks], each padded to four bytes. */
    fun beam(vararg chunks: Pair<String, ByteArray>): ByteArray =
        chunks.fold(bytesOf('F', 'O', 'R', '1', unsignedInt(0), 'B', 'E', 'A', 'M')) { acc, (id, data) ->
            acc + id.toByteArray() + unsignedInt(data.size) + data + ByteArray((4 - data.size % 4) % 4)
        }.also { writeUnsignedInt(it, 4, it.size - 8) }

    /**
     * Walks the chunk directory rather than searching for the bytes: `"Code"` occurs inside the atom
     * table of most modules, so a naive search patches the wrong offset.
     */
    fun chunks(bytes: ByteArray): List<ChunkSpan> {
        if (bytes.size < HEADER_BYTE_COUNT || String(bytes, 0, 4, Charsets.ISO_8859_1) != "FOR1") return emptyList()

        val spans = mutableListOf<ChunkSpan>()
        var offset = HEADER_BYTE_COUNT

        while (offset + CHUNK_HEADER_BYTE_COUNT <= bytes.size) {
            val size = readUnsignedInt(bytes, offset + 4)
            if (size < 0 || offset + CHUNK_HEADER_BYTE_COUNT + size > bytes.size) break

            spans += ChunkSpan(String(bytes, offset, 4, Charsets.ISO_8859_1), offset, offset + CHUNK_HEADER_BYTE_COUNT, size)
            offset += CHUNK_HEADER_BYTE_COUNT + size + (4 - size % 4) % 4
        }

        return spans
    }

    /** [content] without its chunk [chunkId]. */
    fun withoutChunk(content: ByteArray, chunkId: String): ByteArray =
        beam(
            *chunks(content)
                .filter { it.id != chunkId }
                .map { it.id to content.copyOfRange(it.data, it.data + it.size) }
                .toTypedArray()
        )

    /** Rewrites the [fieldIndex]th unsigned int of chunk [chunkId]'s data. */
    fun withChunkField(content: ByteArray, chunkId: String, fieldIndex: Int, value: Int): ByteArray {
        val span = chunks(content).firstOrNull { it.id == chunkId }
            ?: throw AssertionError("no $chunkId chunk in the BEAM under test")

        return content.copyOf().also { writeUnsignedInt(it, span.data + fieldIndex * 4, value) }
    }

    /**
     * Flips LitT between its compressed and uncompressed forms without touching the literals, so neither form
     * decodes. OTP 28 onwards writes a zero inflated size and uncompressed literals.
     */
    fun withUndecodableLiteralTable(content: ByteArray): ByteArray {
        val span = chunks(content).first { it.id == "LitT" }
        val declared = readUnsignedInt(content, span.data)

        return withChunkField(content, "LitT", fieldIndex = 0, value = if (declared == 0) 1 else 0)
    }

    fun lineChunk(lineCount: Int, fileNameCount: Int, vararg body: Any, version: Int = 0, flags: Int = 0): Chunk =
        chunk("Line", lineChunkData(lineCount, fileNameCount, *body, version = version, flags = flags))

    /** A `Line` chunk's five-int header, of which only the version, flags and two counts matter here, then [body]. */
    fun lineChunkData(lineCount: Int, fileNameCount: Int, vararg body: Any, version: Int = 0, flags: Int = 0): ByteArray {
        val header = ByteArray(5 * 4)
        writeUnsignedInt(header, 0, version)
        writeUnsignedInt(header, 4, flags)
        writeUnsignedInt(header, 3 * 4, lineCount)
        writeUnsignedInt(header, 4 * 4, fileNameCount)

        return header + bytesOf(*body)
    }

    fun chunk(id: String, data: ByteArray): Chunk =
        Chunk.from(DataInputStream(ByteArrayInputStream(id.toByteArray() + unsignedInt(data.size) + data)))!!

    /** [Int]s are single bytes, [Char]s their Latin-1 byte, [ByteArray]s appended whole. */
    fun bytesOf(vararg parts: Any): ByteArray =
        parts.fold(ByteArray(0)) { acc, part ->
            acc + when (part) {
                is ByteArray -> part
                is Char -> byteArrayOf(part.code.toByte())
                else -> byteArrayOf((part as Int).toByte())
            }
        }

    fun unsignedInt(value: Int): ByteArray = ByteArray(4).also { writeUnsignedInt(it, 0, value) }

    fun readUnsignedInt(bytes: ByteArray, offset: Int): Int =
        (0 until 4).fold(0) { acc, i -> (acc shl 8) or (bytes[offset + i].toInt() and 0xFF) }

    fun writeUnsignedInt(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) {
            bytes[offset + i] = (value ushr (8 * (3 - i))).toByte()
        }
    }
}
