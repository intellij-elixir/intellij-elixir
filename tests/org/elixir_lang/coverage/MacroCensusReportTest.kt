package org.elixir_lang.coverage

import org.elixir_lang.junit.UnitTestCase
import java.io.File

/**
 * Writes the macro census of the Mix project `ELIXIR_LANG_CENSUS_PROJECT` names, whose dependencies must be fetched, to
 * `build/macro-census/<project directory name>-<Elixir version>.tsv`. Does nothing while the variable is unset.
 *
 * ```
 * ELIXIR_LANG_CENSUS_PROJECT=<path> mise exec -- ./gradlew test --tests org.elixir_lang.coverage.MacroCensusReportTest --rerun
 * ```
 */
class MacroCensusReportTest : UnitTestCase() {
    fun testNamedProject() {
        val project = System.getenv("ELIXIR_LANG_CENSUS_PROJECT")?.takeIf { it.isNotEmpty() }?.let(::File) ?: return
        val census = MacroCensus.run(project, isolateMix = false, timeoutMinutes = 60)
        val report = File("build/macro-census/${project.name}-${census.elixirVersion}.tsv")

        report.parentFile.mkdirs()
        report.writeText(census.report())
        println("Macro census written to ${report.absolutePath}")
    }
}
