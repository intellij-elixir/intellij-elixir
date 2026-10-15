package org.elixir_lang.coverage

import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.expander.LegSdk
import org.elixir_lang.junit.HeavyTestCase
import java.io.File

/**
 * Writes the expander progress report of the Mix project `ELIXIR_LANG_PROGRESS_PROJECT` names to
 * `build/expander-progress/<project directory name>-<Elixir version>.tsv`. The project must have `lib`, `test`,
 * `deps` and `_build/dev/lib` (`mix compile`), and must not be an umbrella. Does nothing while the variable is unset.
 *
 * ```
 * ELIXIR_LANG_PROGRESS_PROJECT=<path> mise exec -- ./gradlew test --tests org.elixir_lang.coverage.ExpanderProgressReportHeavyTest --rerun
 * ```
 */
class ExpanderProgressReportHeavyTest : HeavyTestCase() {
    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testNamedProject() {
        val dir = System.getenv("ELIXIR_LANG_PROGRESS_PROJECT")?.takeIf { it.isNotEmpty() }?.let(::File)?.absoluteFile ?: return
        val missing = REQUIRED.filter { !File(dir, it).isDirectory }

        if (missing.isNotEmpty()) {
            fail("${dir.path} has no ${missing.joinToString(", ")}; the project needs all of ${REQUIRED.joinToString(", ")}")
        }

        val corpus = ExpanderProgress.setUp(project, module, testRootDisposable, dir)
        val progress = ExpanderProgress.run(project, module, corpus)
        val report = File("build/expander-progress/${dir.name}-${environment("ELIXIR_VERSION")}.tsv")

        report.parentFile.mkdirs()
        report.writeText(progress.report())
        println("Expander progress written to ${report.absolutePath}")
    }

    private companion object {
        /** `lib` is absent in an umbrella, and `_build/dev/lib` gives each dependency its compiled classes. */
        val REQUIRED = listOf("lib", "test", "deps", "_build/dev/lib")
    }
}
