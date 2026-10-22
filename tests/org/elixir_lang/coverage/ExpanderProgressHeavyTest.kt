package org.elixir_lang.coverage

import org.elixir_lang.coverage.MacroCensus.Side
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.expander.LegSdk
import org.elixir_lang.junit.HeavyTestCase
import java.io.File

/**
 * Pins the expander progress report over a small Mix project and one dependency. `Progress.Macros.wrap/1` declines by
 * its `receive`, which gives Elixir its `after` value at once.
 */
class ExpanderProgressHeavyTest : HeavyTestCase() {
    private lateinit var progress: ExpanderProgress

    override fun setUp() {
        super.setUp()
        val corpus = ExpanderProgress.setUp(project, module, testRootDisposable, File(FIXTURE).absoluteFile)

        progress = ExpanderProgress.run(project, module, corpus)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testACompiledModuleExpandsEveryUnit() {
        assertEquals(listOf("2 units, 2 expanded, compiled"), describe("Progress.Compiled"))
        assertEquals(listOf("2 units, 2 expanded, compiled"), describe("Progress.Macros"))
        assertEquals(
            listOf("1 units, 1 expanded, compiled", "1 units, 1 expanded, compiled"),
            describe("Progress.Twice")
        )
    }

    fun testUnitsQueuedBeforeAStopStillExpand() {
        assertEquals(
            listOf("2 units, 1 expanded, stopped, opaque Progress.Macros.wrap/1 at line 4"),
            describe("Progress.Opaque")
        )
    }

    fun testSeveralCopiesStopAtTheirRequire() {
        assertEquals(listOf("1 units, 0 expanded, stopped, unported require/1 at line 2"), describe("Progress.Unreadable"))
    }

    fun testARaisedModuleNamesItsError() {
        assertEquals(listOf("1 units, 0 expanded, raised, raised unloaded_module at line 2"), describe("Progress.Raised"))
    }

    fun testANestedStopEndsItsParent() {
        assertEquals(
            listOf("1 units, 0 expanded, stopped, opaque Progress.Macros.wrap/1 at line 4"),
            describe("Progress.Outer.Inner")
        )
        assertEquals(listOf("1 units, 1 expanded, stopped, nested at line 4"), describe("Progress.Outer"))
    }

    fun testATopLevelStopHidesLaterModules() {
        assertEquals(
            listOf("opaque Progress.Macros.wrap/1"),
            progress.files.filter { it.file == "test/top_test.exs" }.map { it.top }
        )
        assertEquals(emptyList<ExpanderProgress.ModuleRow>(), progress.modules.filter { it.file == "test/top_test.exs" })
        assertFalse(progress.report().contains("Progress.Unreached"))
    }

    fun testDependencyFilesAreCountedUnderTheirLibrary() {
        val (dep, app) = progress.modules.partition { it.module == "ProgressDep" }

        assertEquals(listOf(Side.DEP to "dep-progress_dep"), dep.map { it.side to it.library })
        assertEquals(listOf("2 units, 2 expanded, compiled"), dep.map(::describe))
        assertEquals(setOf(Side.APP to "progress"), app.map { it.side to it.library }.toSet())
    }

    fun testTotals() {
        assertEquals(
            listOf(
                "app progress: 9 files, 9 modules, 12 units, 8 expanded, compiled 4, tainted 0, raised 1, crashed 0, stopped 4",
                "dep dep-progress_dep: 1 files, 1 modules, 2 units, 2 expanded, compiled 1, tainted 0, raised 0, crashed 0, stopped 0",
            ),
            progress.totals().map {
                "${it.side.name.lowercase()} ${it.library}: ${it.files} files, ${it.modules} modules, ${it.units} units, " +
                    "${it.expanded} expanded, " +
                    ExpanderProgress.Kind.entries.joinToString(", ") { kind -> "${kind.name.lowercase()} ${it.ended.getValue(kind)}" }
            }
        )
    }

    fun testReportFormat() {
        val version = environment("ELIXIR_VERSION")

        assertEquals(
            """
            # Elixir $version	progress	dependencies without CLASSES: 1
            # units counts the units the expander reached, not the units in the source: definitions queued before a body's stop still expand and count, and those after it are never queued
            # module: side, library, file, module, units, units expanded, ended, first stop, line
            # file: side, library, file, top-level outcome, line
            # total: side, library, files, modules, units, units expanded, compiled, tainted, raised, crashed, stopped
            # stop: side, first stop, modules it ends
            module	app	progress	lib/compiled.ex	Progress.Compiled	2	2	compiled	-	-
            module	app	progress	lib/macros.ex	Progress.Macros	2	2	compiled	-	-
            module	app	progress	lib/opaque.ex	Progress.Opaque	2	1	stopped	opaque Progress.Macros.wrap/1	4
            module	app	progress	lib/outer.ex	Progress.Outer	1	1	stopped	nested	4
            module	app	progress	lib/outer.ex	Progress.Outer.Inner	1	0	stopped	opaque Progress.Macros.wrap/1	4
            module	app	progress	lib/raised.ex	Progress.Raised	1	0	raised	raised unloaded_module	2
            module	app	progress	lib/twice_a.ex	Progress.Twice	1	1	compiled	-	-
            module	app	progress	lib/twice_b.ex	Progress.Twice	1	1	compiled	-	-
            module	app	progress	lib/unreadable.ex	Progress.Unreadable	1	0	stopped	unported require/1	2
            module	dep	dep-progress_dep	deps/progress_dep/lib/progress_dep.ex	ProgressDep	2	2	compiled	-	-
            file	app	progress	test/top_test.exs	opaque Progress.Macros.wrap/1	2
            total	app	progress	9	9	12	8	4	0	1	0	4
            total	dep	dep-progress_dep	1	1	2	2	1	0	0	0	0
            stop	app	opaque Progress.Macros.wrap/1	2
            stop	app	nested	1
            stop	app	raised unloaded_module	1
            stop	app	unported require/1	1

            """.trimIndent(),
            progress.report()
        )
    }

    private fun describe(module: String): List<String> = progress.modules.filter { it.module == module }.map(::describe)

    private fun describe(row: ExpanderProgress.ModuleRow): String =
        "${row.units} units, ${row.expanded} expanded, ${row.ended.name.lowercase()}" +
            (row.stop?.let { ", ${it.cause} at line ${it.line}" } ?: "")

    private companion object {
        const val FIXTURE = "testData/org/elixir_lang/coverage/progress"
    }
}
