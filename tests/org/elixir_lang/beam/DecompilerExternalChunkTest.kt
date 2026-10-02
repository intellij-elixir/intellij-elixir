package org.elixir_lang.beam

import com.google.common.io.Files
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.golden.CommittedGolden
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Golden-file tests for decompiling Erlang `.beam` files that use external EEP-48
 * documentation chunks (stored in `<app>/doc/chunks/<module>.chunk`) or embedded
 * documentation chunks.
 *
 * The test data directories mirror the OTP application layout:
 * ```
 * OTP23_external_chunks/     -- OTP 23 with external doc/chunks/ files
 *   stdlib/
 *     ebin/
 *       base64.beam
 *       queue.beam
 *     doc/
 *       chunks/
 *         base64.chunk
 *         queue.chunk
 *
 * OTP26_external_chunks/     -- OTP 26 with external doc/chunks/ files
 *   stdlib/
 *     ebin/ + doc/chunks/    (same layout)
 *
 * OTP27_embedded_docs/       -- OTP 27 with docs embedded in .beam files
 *   stdlib/
 *     ebin/                  (no doc/chunks/ directory)
 *
 * OTP29_embedded_docs/       -- OTP 29 with docs embedded in .beam files;
 *   stdlib/                     exercises abstract-code type rendering
 *     ebin/                     (parametrized types, -opaque, dynamic())
 * ```
 *
 * Beam files and chunk files are copied from real OTP installations
 * built with `--enable-docs` (via mise/kerl). The decompiler picks up
 * external chunks via
 * [org.elixir_lang.beam.chunk.beam_documentation.Documentation.fromExternalChunk]
 * and embedded docs via [org.elixir_lang.beam.BeamReader.documentation].
 *
 * Each test decompiles a `.beam` file and compares the result against
 * a golden `.ex` file in the same `ebin/` directory.
 */
class DecompilerExternalChunkTest : PlatformTestCase() {

    // --- OTP 23: external chunks (application/erlang+html format) ---

    fun testOTP23Base64WithExternalChunks() {
        assertDecompiledWithAppLayout("OTP23_external_chunks", "base64")
    }

    fun testOTP23QueueWithExternalChunks() {
        assertDecompiledWithAppLayout("OTP23_external_chunks", "queue")
    }

    // --- OTP 26: external chunks (application/erlang+html format, newer OTP) ---

    fun testOTP26Base64WithExternalChunks() {
        assertDecompiledWithAppLayout("OTP26_external_chunks", "base64")
    }

    fun testOTP26QueueWithExternalChunks() {
        assertDecompiledWithAppLayout("OTP26_external_chunks", "queue")
    }

    // --- OTP 27: embedded docs (docs chunk inside .beam file) ---

    fun testOTP27Base64WithEmbeddedDocs() {
        assertDecompiledWithAppLayout("OTP27_embedded_docs", "base64")
    }

    fun testOTP27QueueWithEmbeddedDocs() {
        assertDecompiledWithAppLayout("OTP27_embedded_docs", "queue")
    }

    // --- OTP 29: embedded docs (exercises abstract-code type rendering:
    //     parametrized types, `-opaque`, and the `dynamic()` builtin) ---

    fun testOTP29Base64WithEmbeddedDocs() {
        assertDecompiledWithAppLayout("OTP29_embedded_docs", "base64")
    }

    fun testOTP29QueueWithEmbeddedDocs() {
        assertDecompiledWithAppLayout("OTP29_embedded_docs", "queue")
    }

    fun testOTP29ArrayWithEmbeddedDocs() {
        assertDecompiledWithAppLayout("OTP29_embedded_docs", "array")
    }

    fun testOTP29DictWithEmbeddedDocs() {
        assertDecompiledWithAppLayout("OTP29_embedded_docs", "dict")
    }

    fun testOTP29GbSetsWithEmbeddedDocs() {
        assertDecompiledWithAppLayout("OTP29_embedded_docs", "gb_sets")
    }

    fun testOTP29GbTreesWithEmbeddedDocs() {
        assertDecompiledWithAppLayout("OTP29_embedded_docs", "gb_trees")
    }

    fun testOTP29MapsWithEmbeddedDocs() {
        assertDecompiledWithAppLayout("OTP29_embedded_docs", "maps")
    }

    fun testOTP29OrddictWithEmbeddedDocs() {
        assertDecompiledWithAppLayout("OTP29_embedded_docs", "orddict")
    }

    fun testOTP29OrdsetsWithEmbeddedDocs() {
        assertDecompiledWithAppLayout("OTP29_embedded_docs", "ordsets")
    }

    fun testOTP29SetsWithEmbeddedDocs() {
        assertDecompiledWithAppLayout("OTP29_embedded_docs", "sets")
    }

    fun testOTP29JsonWithEmbeddedDocs() {
        assertDecompiledWithAppLayout("OTP29_embedded_docs", "json")
    }

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/beam/decompiler"

    /**
     * Decompiles a `.beam` file from the `<otpDir>/<app>/ebin/` directory and compares
     * the output against a golden `.ex` file alongside the `.beam` file.
     *
     * The directory layout ensures that
     * [org.elixir_lang.beam.chunk.beam_documentation.Documentation.fromExternalChunk]
     * can navigate from `<app>/ebin/<module>.beam` to `<app>/doc/chunks/<module>.chunk`.
     */
    private fun assertDecompiledWithAppLayout(otpDir: String, moduleName: String) {
        val testDataPath = getTestDataPath()
        val ebinDir = "$testDataPath/$otpDir/stdlib/ebin"
        val beamPath = "$ebinDir/$moduleName.beam"
        val goldenPath = "$ebinDir/$moduleName.ex"

        VfsRootAccess.allowRootAccess(getTestRootDisposable(), testDataPath)

        val virtualFile = LocalFileSystem
            .getInstance()
            .findFileByIoFile(File(beamPath))

        assertNotNull("Beam file not found: $beamPath", virtualFile)

        val decompiler = Decompiler()
        val actual = decompiler.decompile(virtualFile!!).toString()

        assertParseable(moduleName, virtualFile, actual)

        val goldenFile = File(goldenPath)
        if (!goldenFile.exists()) {
            // Generate the golden file on first run
            goldenFile.writeText(actual, StandardCharsets.UTF_8)
            fail(
                "Golden file did not exist, generated it at: $goldenPath\n" +
                        "Review the generated file, then re-run the test."
            )
        }

        val expected = Files.asCharSource(goldenFile, StandardCharsets.UTF_8).read()

        if (OVERWRITE_TESTDATA) {
            CommittedGolden.assertMatches(goldenPath, actual, REGENERATE)
        } else if (expected != actual) {
            fail(buildCompactDiffMessage(moduleName, expected, actual))
        }
    }

    private fun assertParseable(name: String, virtualFile: com.intellij.openapi.vfs.VirtualFile, decompiledText: String) {
        val compiledPsiFile = PsiManager.getInstance(project).findFile(virtualFile)
        assertNotNull("Compiled PSI file is null for $name", compiledPsiFile)
        assertTrue("PSI file is not compiled for $name", compiledPsiFile is PsiCompiledFile)

        val decompiledPsiFile = (compiledPsiFile as PsiCompiledFile).decompiledPsiFile
        assertNotNull("Decompiled PSI file is null for $name", decompiledPsiFile)

        val firstParseError = PsiTreeUtil.findChildOfType(decompiledPsiFile, PsiErrorElement::class.java)
        if (firstParseError != null) {
            val errorOffset = firstParseError.textRange.startOffset
            val failingLine = parseErrorLine(decompiledText, errorOffset)
            val description = firstParseError.errorDescription
            val context = parseErrorContext(decompiledText, errorOffset)

            fail(
                "DECOMPILATION PARSE ERROR: $name" +
                        (if (description.isBlank()) "" else "\nDescription: $description") +
                        (if (failingLine.isNullOrBlank()) "" else "\nLine: $failingLine") +
                        "\nError offset: $errorOffset" +
                        "\n\n--- Context (20 lines before/after error) ---\n$context" +
                        "\n--- End context ---"
            )
        }
    }

    private fun parseErrorLine(text: String, offset: Int): String? {
        if (text.isEmpty() || offset < 0 || offset > text.length) return null
        val lineStart = text.lastIndexOf('\n', maxOf(offset - 1, 0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return text.substring(lineStart, lineEnd)
    }

    private fun parseErrorContext(text: String, offset: Int): String {
        val surroundingLines = 20
        if (text.isEmpty() || offset < 0 || offset > text.length) return "(no context available)"

        val lines = text.split("\n")
        var charCount = 0
        var errorLine = 0
        for (i in lines.indices) {
            val lineLen = lines[i].length + 1
            if (charCount + lineLen > offset) {
                errorLine = i
                break
            }
            charCount += lineLen
        }

        val startLine = maxOf(0, errorLine - surroundingLines)
        val endLine = minOf(lines.size - 1, errorLine + surroundingLines)

        return (startLine..endLine).joinToString("\n") { i ->
            val marker = if (i == errorLine) ">>> " else "    "
            "$marker${"%4d".format(i + 1)} | ${lines[i]}"
        }
    }

    private fun buildCompactDiffMessage(name: String, expected: String, actual: String): String {
        val minLen = minOf(expected.length, actual.length)

        var diffPos = 0
        while (diffPos < minLen && expected[diffPos] == actual[diffPos]) diffPos++

        var line = 1
        var col = 1
        var lineStart = 0
        for (i in 0 until diffPos) {
            if (i < expected.length && expected[i] == '\n') {
                line++; col = 1; lineStart = i + 1
            } else {
                col++
            }
        }

        val expectedLineEnd = expected.indexOf('\n', diffPos).let { if (it == -1) expected.length else it }
        val actualLineEnd = actual.indexOf('\n', diffPos).let { if (it == -1) actual.length else it }
        val expectedLine = expected.substring(lineStart, minOf(expectedLineEnd, expected.length))
        val actualLine = actual.substring(lineStart, minOf(actualLineEnd, actual.length))

        val expectedChar = if (diffPos < expected.length) expected[diffPos] else '�'
        val actualChar = if (diffPos < actual.length) actual[diffPos] else '�'

        return """
            |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            |DECOMPILATION MISMATCH: $name
            |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            |Position: char $diffPos, line $line, col $col
            |Length:   expected=${expected.length}, actual=${actual.length} (diff: ${expected.length - actual.length})
            |
            |Character at difference:
            |  Expected: '${escapeChar(expectedChar)}' (0x${"%04x".format(expectedChar.code)})
            |  Actual:   '${escapeChar(actualChar)}' (0x${"%04x".format(actualChar.code)})
            |
            |Line $line context:
            |  Expected: $expectedLine
            |  Actual:   $actualLine
            |━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        """.trimMargin()
    }

    private fun escapeChar(c: Char): String = when (c) {
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        ' ' -> "SPACE"
        '�' -> "EOF"
        else -> c.toString()
    }

    private companion object {
        const val REGENERATE =
            "./gradlew test --tests org.elixir_lang.beam.DecompilerExternalChunkTest -PoverwriteTestData=true"
    }
}
