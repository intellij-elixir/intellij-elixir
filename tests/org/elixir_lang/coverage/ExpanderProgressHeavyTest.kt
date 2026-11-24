package org.elixir_lang.coverage

import org.elixir_lang.coverage.MacroCensus.Side
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.expander.LegSdk
import org.elixir_lang.expander.PhoenixCorpus
import org.elixir_lang.junit.HeavyTestCase
import java.io.File

/**
 * Pins the expander progress report over a small Mix project and one dependency. `Progress.Macros.wrap/1` declines at
 * the `send` before its `receive`, which takes that message in Elixir and gives what its `after` would.
 * `Progress.Macros.send_self/2` declines at the same `send`, given a reference that differs each run.
 */
class ExpanderProgressHeavyTest : HeavyTestCase() {
    private lateinit var corpus: PhoenixCorpus
    private lateinit var progress: ExpanderProgress

    override fun setUp() {
        super.setUp()
        corpus = ExpanderProgress.setUp(project, module, testRootDisposable, File(FIXTURE).absoluteFile)

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
        assertEquals(listOf("3 units, 3 expanded, compiled"), describe("Progress.Macros"))
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

    fun testAStopNamesTheDecliningCall() {
        assertEquals(listOf(SEND_DECLINED), rows("Progress.Opaque").map { it.stop?.declined })
    }

    fun testTheMarkNamesTheStatementThatSetIt() {
        assertEquals(listOf(SEND_SELF_MARK), marked("Progress.Marked"))
    }

    fun testANestedMarkNamesItsModule() {
        assertEquals(listOf("Progress.Macros.wrap/1 line 4 in Progress.Outer.Inner: $SEND_DECLINED"), marked("Progress.Outer"))
        assertEquals(listOf("Progress.Macros.wrap/1 line 4: $SEND_DECLINED"), marked("Progress.Outer.Inner"))
    }

    /** `Progress.Marked`'s reference is what differs between two runs. */
    fun testTwoRunsReportAlike() {
        assertEquals(progress.report(), ExpanderProgress.run(project, module, corpus).report())
    }

    fun testTotals() {
        assertEquals(
            listOf(
                "app progress: 10 files, 10 modules, 15 units, 11 expanded, compiled 4, tainted 0, raised 1, crashed 0, stopped 5",
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
            # module: side, library, file, module, units, units expanded, ended, first stop, line, declined, marked
            # file: side, library, file, top-level outcome, line, declined
            # total: side, library, files, modules, units, units expanded, compiled, tainted, raised, crashed, stopped
            # stop: side, first stop, modules it ends
            module	app	progress	lib/compiled.ex	Progress.Compiled	2	2	compiled	-	-	-	-
            module	app	progress	lib/macros.ex	Progress.Macros	3	3	compiled	-	-	-	-
            module	app	progress	lib/marked.ex	Progress.Marked	2	2	stopped	other defmodule/2	1	-	$SEND_SELF_MARK
            module	app	progress	lib/opaque.ex	Progress.Opaque	2	1	stopped	opaque Progress.Macros.wrap/1	4	$SEND_DECLINED	Progress.Macros.wrap/1 line 4: $SEND_DECLINED
            module	app	progress	lib/outer.ex	Progress.Outer	1	1	stopped	nested	4	-	Progress.Macros.wrap/1 line 4 in Progress.Outer.Inner: $SEND_DECLINED
            module	app	progress	lib/outer.ex	Progress.Outer.Inner	1	0	stopped	opaque Progress.Macros.wrap/1	4	$SEND_DECLINED	Progress.Macros.wrap/1 line 4: $SEND_DECLINED
            module	app	progress	lib/raised.ex	Progress.Raised	1	0	raised	raised unloaded_module	2	-	-
            module	app	progress	lib/twice_a.ex	Progress.Twice	1	1	compiled	-	-	-	-
            module	app	progress	lib/twice_b.ex	Progress.Twice	1	1	compiled	-	-	-	-
            module	app	progress	lib/unreadable.ex	Progress.Unreadable	1	0	stopped	unported require/1	2	-	-
            module	dep	dep-progress_dep	deps/progress_dep/lib/progress_dep.ex	ProgressDep	2	2	compiled	-	-	-	-
            file	app	progress	lib/raised.ex	error unloaded_module	2	-
            file	app	progress	test/top_test.exs	opaque Progress.Macros.wrap/1	2	$SEND_DECLINED
            total	app	progress	10	10	15	11	4	0	1	0	5
            total	dep	dep-progress_dep	1	1	2	2	1	0	0	0	0
            stop	app	opaque Progress.Macros.wrap/1	2
            stop	app	nested	1
            stop	app	other defmodule/2	1
            stop	app	raised unloaded_module	1
            stop	app	unported require/1	1

            """.trimIndent(),
            progress.report()
        )
    }

    private fun rows(module: String): List<ExpanderProgress.ModuleRow> = progress.modules.filter { it.module == module }

    private fun describe(module: String): List<String> = rows(module).map(::describe)

    private fun describe(row: ExpanderProgress.ModuleRow): String =
        "${row.units} units, ${row.expanded} expanded, ${row.ended.name.lowercase()}" +
            (row.stop?.let { ", ${it.cause} at line ${it.line}" } ?: "")

    private fun marked(module: String): List<String?> = rows(module).map { it.marked }

    private companion object {
        const val FIXTURE = "testData/org/elixir_lang/coverage/progress"

        const val SEND_DECLINED = "erlang:send/2 isn't a native in erlang:send(#Pid,decline)"

        const val SEND_SELF_MARK = "Progress.Macros.send_self/2 line 2: " +
            "erlang:send/2 isn't a native in erlang:send(#Pid,{'Elixir.Progress.Marked',#Ref})"
    }
}
