package org.elixir_lang.golden

import com.intellij.platform.testFramework.core.FileComparisonFailedError
import com.intellij.testFramework.UsefulTestCase
import com.intellij.util.SystemProperties
import org.elixir_lang.junit.UnitTestCase
import java.io.File
import kotlin.io.path.createTempDirectory

class CommittedGoldenTest : UnitTestCase() {
    private lateinit var directory: File
    private lateinit var golden: File
    private lateinit var summary: File
    private var savedSummary: String? = null

    override fun setUp() {
        super.setUp()
        directory = createTempDirectory("committed-golden").toFile()
        golden = File(directory, "golden.txt")
        summary = File(directory, "summary.md")
        savedSummary = SystemProperties.setProperty(CommittedGolden.STEP_SUMMARY_PROPERTY, summary.path)
    }

    override fun tearDown() {
        try {
            SystemProperties.setProperty(CommittedGolden.STEP_SUMMARY_PROPERTY, savedSummary)
            directory.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    // Under -PoverwriteTestData=true the platform rewrites each golden before comparing, so nothing can mismatch.
    override fun runTest() {
        if (!UsefulTestCase.OVERWRITE_TESTDATA) super.runTest()
    }

    fun testMatchPassesAndWritesNothing() {
        golden.writeText("a\nb\n")

        CommittedGolden.assertMatches(golden.path, "a\nb\n", REGENERATE)

        assertFalse(summary.exists())
    }

    fun testMismatchFailsWithTheRegenerateCommand() {
        golden.writeText("a\nb\n")

        val error = mismatch("a\nB\n")

        assertEquals("a\nb\n", error.expectedStringPresentation)
        assertEquals("a\nB\n", error.actualStringPresentation)
        assertTrue(error.message, error.message!!.contains(REGENERATE))
    }

    fun testMismatchAppendsTheDiffAndCountToTheStepSummary() {
        golden.writeText("a\nb\nc\nd\n")
        summary.writeText("Running tests in 2 fork(s)\n")

        mismatch("a\nB\nc\nd\ne\n")

        assertEquals(
            """
            |Running tests in 2 fork(s)
            |### `${golden.path}` differs
            |
            |3 lines moved: 1 removed, 2 added. Review each moved line, then regenerate with `$REGENERATE`.
            |
            |```diff
            |--- a/${golden.path}
            |+++ b/${golden.path}
            |@@ -2,1 +2,1 @@
            |-b
            |+B
            |@@ -4,0 +5,1 @@
            |+e
            |```
            |
            """.trimMargin(),
            summary.readText()
        )
    }

    fun testLongDiffIsCappedButCountsEveryMovedLine() {
        golden.writeText((1..300).joinToString("") { "old $it\n" })

        mismatch((1..300).joinToString("") { "new $it\n" })

        assertTrue("No step summary was written", summary.isFile)
        val text = summary.readText()
        assertTrue(text, text.contains("600 lines moved: 300 removed, 300 added."))
        assertTrue(text, text.contains("Showing the first 200 of 601 diff lines."))
        val shown = text.substringAfter("```diff\n").substringBefore("```").lines().dropLast(1)
        assertEquals(2 + 200, shown.size)
        assertEquals("-old 199", shown.last())
    }

    fun testMismatchWithoutAStepSummaryStillFails() {
        System.clearProperty(CommittedGolden.STEP_SUMMARY_PROPERTY)
        golden.writeText("a\n")

        mismatch("b\n")
    }

    fun testUnwritableStepSummaryDoesNotHideTheMismatch() {
        summary.mkdir()
        golden.writeText("a\n")

        val error = mismatch("b\n")

        assertEquals(1, error.suppressed.size)
    }

    private fun mismatch(actual: String): FileComparisonFailedError =
        try {
            CommittedGolden.assertMatches(golden.path, actual, REGENERATE)
            throw AssertionError("expected a FileComparisonFailedError")
        } catch (error: FileComparisonFailedError) {
            error
        }

    companion object {
        private const val REGENERATE = "./gradlew test --tests Example -PoverwriteTestData=true"
    }
}
