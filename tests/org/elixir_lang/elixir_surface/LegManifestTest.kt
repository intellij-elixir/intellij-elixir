package org.elixir_lang.elixir_surface

import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.testFramework.core.FileComparisonFailedError
import com.intellij.testFramework.UsefulTestCase
import com.intellij.util.SystemProperties
import org.elixir_lang.golden.CommittedGolden
import org.elixir_lang.junit.UnitTestCase
import java.io.File

class LegManifestTest : UnitTestCase() {
    private lateinit var root: File
    private lateinit var summary: File
    private var savedSummary: String? = null

    override fun setUp() {
        super.setUp()
        root = FileUtil.createTempDirectory("manifest", null)
        summary = File(root, "summary.md")
        savedSummary = System.getProperty(CommittedGolden.STEP_SUMMARY_PROPERTY)
        System.setProperty(CommittedGolden.STEP_SUMMARY_PROPERTY, summary.path)
    }

    override fun tearDown() {
        try {
            SystemProperties.setProperty(CommittedGolden.STEP_SUMMARY_PROPERTY, savedSummary)
            root.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testRegenerateCommandNamesTheTestAndTheLeg() {
        val command = LegManifest.regenerateCommand(LegManifestTest::class, elixirVersion = "1.21.0", otpVersion = "29.1.1")

        assertTrue(command, command.startsWith("./gradlew test --tests org.elixir_lang.elixir_surface.LegManifestTest "))
        assertTrue(command, command.contains(" \"-PelixirVersion=1.21.0\" "))
        assertTrue(command, command.endsWith(" \"-PotpVersion=29.1.1\""))
        assertTrue(command, command.contains("-PoverwriteTestData=true"))
    }

    fun testMismatchIsReportedToTheStepSummary() {
        // Under -PoverwriteTestData=true the platform rewrites the manifest before comparing, so nothing can mismatch.
        if (UsefulTestCase.OVERWRITE_TESTDATA) return

        commit("1.20.4", "a 1\nb 1\n")

        val error = try {
            LegManifest.assertMatchesFile(root.path, "1.20.4", NAME, "a 1\nc 1\n", regenerate = "regenerate-command")
            null
        } catch (e: FileComparisonFailedError) {
            e
        }

        assertNotNull("a differing manifest must fail", error)
        assertTrue(error!!.message, error.message!!.contains("regenerate-command"))
        assertTrue(summary.readText(), summary.readLines().containsAll(listOf("-b 1", "+c 1")))
    }

    fun testMissingManifestNamesTheLinesAndTheRegeneration() {
        val error = missing("1.20.4", "a 1\n")

        assertTrue(error.message, error.message!!.lines().contains("a 1"))
        assertTrue(error.message, error.message!!.contains("regenerate-command"))
        assertFalse("only regeneration writes a manifest", File(root, "1.20.4/$NAME").exists())
    }

    fun testMissingManifestIsDiffedAgainstTheNearestEarlierVersion() {
        commit("1.2.0", "a 1\n")
        commit("1.9.0", "a 1\nb 1\n")
        commit("1.11.0", "a 1\nb 1\nc 1\nd 1\n")
        File(root, "1.9.5").mkdirs()

        missing("1.10.0", "a 1\nc 1\n")

        val lines = summary.readLines()
        assertTrue(summary.readText(), lines.contains("--- a/${root.path}/1.9.0/$NAME"))
        assertTrue(summary.readText(), lines.contains("+++ b/${root.path}/1.10.0/$NAME"))
        assertTrue(summary.readText(), lines.containsAll(listOf("-b 1", "+c 1")))
        assertFalse(summary.readText(), lines.contains("-a 1"))
    }

    private fun commit(version: String, text: String) {
        File(root, "$version/$NAME").apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun missing(version: String, actual: String): AssertionError =
        try {
            LegManifest.assertMatchesFile(root.path, version, NAME, actual, regenerate = "regenerate-command", writeMissing = false)
            throw IllegalStateException("a missing manifest must fail")
        } catch (e: AssertionError) {
            e
        }

    companion object {
        private const val NAME = "manifest.txt"
    }
}
