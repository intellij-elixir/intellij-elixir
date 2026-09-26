package org.elixir_lang.beam

import com.intellij.psi.PsiErrorElement
import org.elixir_lang.PlatformTestCase
import org.junit.Assert

/**
 * Four checks over every `.beam` in the resolved Elixir and Erlang SDKs, all read from one [SdkStdlibSweep] per SDK.
 * They are one class because that sweep is cached per JVM and the test task spreads classes over several JVMs: as
 * separate classes each landed on its own fork and paid the whole decompile again.
 *
 * - **Parseable:** the decompiled source parses as valid Elixir (no [PsiErrorElement]), the check
 *   [DecompilerTest.assertParseable] applies to its golden fixtures, scaled to the whole stdlib so decompiler gaps
 *   surface on a version bump. [SdkBeamParseTest] covers only the platform-free chunk parser.
 * - **Mirror coverage:** every exported definition comes out of `ModuleImpl.setMirror` with a mirror. Mirror mapping
 *   runs after the parse, matching each exported stub against the decompiled source by name and arity, so a
 *   definition the source never produced a matching clause for parses fine and surfaces only at runtime, as a "No
 *   decompiled source function with name" warning and a navigation target that goes nowhere. A mirror-less
 *   *unexported* definition is ordinary and is not asserted.
 * - **Stub signatures:** the stub's parameters are the ones the decompiled mirror shows, and the mirror is the first
 *   matching clause, for every compared definition - `ModuleImpl.setMirror` and the source path's parameter hints
 *   agree on the first clause of each name and arity. A module whose own clause map failed to build is skipped
 *   entirely, so `stubCompared` counts comparisons actually made. The counts of exported definitions with parameters
 *   whose names are not flagged as generated are printed to this class's `system-out` in the JUnit XML.
 * - **No beam warnings:** a term the beam code cannot render is logged and replaced by a placeholder that parses, so
 *   none of the checks above sees it.
 */
class SdkStdlibSweepTest : PlatformTestCase() {
    fun testElixirSdkDecompilesToParseableElixir() = assertParseable(ELIXIR)

    fun testErlangSdkDecompilesToParseableElixir() = assertParseable(ERLANG)

    fun testElixirSdkExportedDefinitionsAllGetMirrors() = assertMirrorCoverage(ELIXIR)

    fun testErlangSdkExportedDefinitionsAllGetMirrors() = assertMirrorCoverage(ERLANG)

    fun testElixirSdkStubParametersMatchMirror() = assertStubSignatures(ELIXIR)

    fun testErlangSdkStubParametersMatchMirror() = assertStubSignatures(ERLANG)

    fun testElixirSdkDecompilesWithoutWarnings() = assertNoDecompilerWarnings(ELIXIR)

    fun testErlangSdkDecompilesWithoutWarnings() = assertNoDecompilerWarnings(ERLANG)

    private fun assertParseable(sdk: SdkUnderTest) {
        val result = sweep(sdk)
        val failures = result.parseFailures
        println(
            "[decompile-parseable] ${sdk.label}: ${result.beamCount - failures.size}/${result.beamCount} beams " +
                "decompiled to parseable Elixir"
        )
        Assert.assertTrue(
            "${failures.size}/${result.beamCount} ${sdk.label} beams did NOT decompile to parseable Elixir:\n" +
                firstHundred(failures),
            failures.isEmpty()
        )
    }

    private fun assertMirrorCoverage(sdk: SdkUnderTest) {
        val result = sweep(sdk)
        val misses = result.mirrorMisses
        val exported = result.mirrorExported
        println("[mirror-coverage] ${sdk.label}: ${exported - misses.size}/$exported exported definitions got a mirror")
        Assert.assertTrue(
            "${misses.size} exported ${sdk.label} definitions decompiled without a mirror, so navigating to them " +
                "lands nowhere:\n" + firstHundred(misses),
            misses.isEmpty()
        )
    }

    private fun assertStubSignatures(sdk: SdkUnderTest) {
        val result = sweep(sdk)
        val mismatches = result.stubMismatches
        val exportedWithParameters = result.stubExportedWithParameters
        val real = exportedWithParameters - result.stubExportedGenerated
        println(
            "[stub-signature] ${sdk.label}: compared ${result.stubCompared} definitions with a mirror; " +
                "$real/$exportedWithParameters exported definitions with parameters have names " +
                "not flagged as generated " +
                "(${"%.1f".format(100.0 * real / exportedWithParameters.coerceAtLeast(1))}%); " +
                "${result.stubExportedGenerated} generated across ${result.stubBeamsWithExportedGenerated} beams"
        )
        Assert.assertTrue("${sdk.label}: no definition had a mirror to compare against", result.stubCompared > 0)
        Assert.assertTrue(
            "${mismatches.size} ${sdk.label} stubs disagree with their decompiled mirror:\n" + firstHundred(mismatches),
            mismatches.isEmpty()
        )
    }

    private fun assertNoDecompilerWarnings(sdk: SdkUnderTest) {
        val warnings = sweep(sdk).decompilerWarnings
        Assert.assertTrue(
            "${warnings.size} beam warnings for ${sdk.label} beams:\n" + firstHundred(warnings),
            warnings.isEmpty()
        )
    }

    private fun sweep(sdk: SdkUnderTest): SdkStdlibSweep.Result {
        val root = System.getenv(sdk.envVar)
        Assert.assertNotNull("${sdk.label} SDK env var not set", root)
        val result = SdkStdlibSweep.forSdk(project, testRootDisposable, root!!, sdk.label.lowercase())
        Assert.assertTrue("No .beam files found under $root/lib", result.beamCount > 0)
        return result
    }

    private fun firstHundred(lines: List<String>): String =
        lines.take(100).joinToString("\n") + (if (lines.size > 100) "\n... and ${lines.size - 100} more" else "")

    private class SdkUnderTest(val label: String, val envVar: String)

    private companion object {
        val ELIXIR = SdkUnderTest("Elixir", "ELIXIR_LANG_ELIXIR_PATH")
        val ERLANG = SdkUnderTest("Erlang", "ERLANG_SDK_HOME")
    }
}
