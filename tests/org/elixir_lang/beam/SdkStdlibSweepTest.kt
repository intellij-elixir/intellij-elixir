package org.elixir_lang.beam

import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiErrorElement
import com.intellij.util.ThrowableRunnable
import junit.framework.Test
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.junit.SharedFixture
import org.elixir_lang.junit.SharedFixtureHost
import org.junit.Assert

/**
 * Four checks over every `.beam` in the resolved Elixir and Erlang SDKs, one test per `.beam`, each decompiled once
 * in a project the tests share. A test per SDK then checks and prints the SDK's totals.
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
@Suppress("JUnitMalformedDeclaration") // Built only by `suite()`.
class SdkStdlibSweepTest private constructor(
    private val fixture: SharedFixture<SdkStdlibSweepTest>?,
    private val case: Case?,
) : PlatformTestCase(), SharedFixtureHost<SdkStdlibSweepTest> {
    private val swept = mutableMapOf<SdkBeam, SdkStdlibSweep.Result>()

    init {
        name = case?.name ?: "shared fixture"
        fixture?.add(this)
    }

    override fun setUp() {
        super.setUp()
        for (sdk in SDKS) sdk.root?.let { VfsRootAccess.allowRootAccess(testRootDisposable, it) }
    }

    override fun runShared(serve: ThrowableRunnable<Throwable>) {
        runBare(serve)
    }

    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        SharedFixture.runCase(fixture, this) { super.runBare(testRunnable) }
    }

    override fun check(case: SdkStdlibSweepTest) {
        when (val slice = case.case!!) {
            is Case.Beam -> assertSwept(slice.beam)
            is Case.Totals -> assertTotals(slice.sdk, slice.beams)
            is Case.Unswept -> throw AssertionError(slice.why)
        }
    }

    private fun sweep(beam: SdkBeam): SdkStdlibSweep.Result = swept.getOrPut(beam) { SdkStdlibSweep.sweep(project, beam) }

    private fun assertSwept(beam: SdkBeam) {
        val result = sweep(beam)
        val problems = listOf(
            "did NOT decompile to parseable Elixir" to result.parseFailures,
            "has exported definitions decompiled without a mirror, so navigating to them lands nowhere" to
                result.mirrorMisses,
            "has stubs that disagree with their decompiled mirror" to result.stubMismatches,
            "logged beam warnings" to result.decompilerWarnings,
        ).filter { (_, lines) -> lines.isNotEmpty() }

        if (problems.isNotEmpty()) {
            Assert.fail(problems.joinToString("\n") { (what, lines) -> "${beam.label} $what:\n" + firstHundred(lines) })
        }
    }

    /** Sweeps whichever of [beams] no test has, so the totals hold however the tests are filtered. */
    private fun assertTotals(sdk: SdkUnderTest, beams: List<SdkBeam>) {
        val results = beams.map(::sweep)
        val parseable = results.count { it.parseFailures.isEmpty() }
        val exported = results.sumOf { it.mirrorExported }
        val misses = results.sumOf { it.mirrorMisses.size }
        val compared = results.sumOf { it.stubCompared }
        val exportedWithParameters = results.sumOf { it.stubExportedWithParameters }
        val generated = results.sumOf { it.stubExportedGenerated }
        val real = exportedWithParameters - generated
        println("[decompile-parseable] ${sdk.label}: $parseable/${beams.size} beams decompiled to parseable Elixir")
        println("[mirror-coverage] ${sdk.label}: ${exported - misses}/$exported exported definitions got a mirror")
        println(
            "[stub-signature] ${sdk.label}: compared $compared definitions with a mirror; " +
                "$real/$exportedWithParameters exported definitions with parameters have names " +
                "not flagged as generated " +
                "(${"%.1f".format(100.0 * real / exportedWithParameters.coerceAtLeast(1))}%); " +
                "$generated generated across ${results.count { it.stubExportedGenerated > 0 }} beams"
        )
        Assert.assertTrue("${sdk.label}: no definition had a mirror to compare against", compared > 0)
    }

    private fun firstHundred(lines: List<String>): String =
        lines.take(100).joinToString("\n") + (if (lines.size > 100) "\n... and ${lines.size - 100} more" else "")

    private class SdkUnderTest(val label: String, envVar: String) {
        val root: String? = System.getenv(envVar)?.takeIf { it.isNotEmpty() }
        val tag = label.lowercase()
    }

    private sealed class Case(val name: String) {
        class Beam(val beam: SdkBeam) : Case(beam.label)

        class Totals(val sdk: SdkUnderTest, val beams: List<SdkBeam>) : Case("${sdk.tag} totals")

        /** An SDK there is nothing to sweep in, which fails with [why]. */
        class Unswept(sdk: SdkUnderTest, val why: String) : Case("${sdk.tag} swept")
    }

    companion object {
        private val SDKS = listOf(
            SdkUnderTest("Elixir", "ELIXIR_LANG_ELIXIR_PATH"),
            SdkUnderTest("Erlang", "ERLANG_SDK_HOME"),
        )

        @JvmStatic
        fun suite(): Test {
            val fixture = SharedFixture { SdkStdlibSweepTest(null, null) }
            val suite = fixture.suite(SdkStdlibSweepTest::class.java.name)

            for (sdk in SDKS) {
                val beams = SdkBeams.forSdk(sdk.root, sdk.tag)

                if (sdk.root == null) {
                    suite.addTest(SdkStdlibSweepTest(fixture, Case.Unswept(sdk, "${sdk.label} SDK env var not set")))
                } else if (beams.isEmpty()) {
                    suite.addTest(
                        SdkStdlibSweepTest(fixture, Case.Unswept(sdk, "No .beam files found under ${sdk.root}/lib"))
                    )
                } else {
                    for (beam in beams) suite.addTest(SdkStdlibSweepTest(fixture, Case.Beam(beam)))
                    suite.addTest(SdkStdlibSweepTest(fixture, Case.Totals(sdk, beams)))
                }
            }

            return suite
        }
    }
}
