package org.elixir_lang.coverage

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightProjectDescriptor
import org.elixir_lang.coverage.FactsReport.ROOT
import org.elixir_lang.declaration.CompiledOrigin
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.SourceOrigin
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.snapshot.CompilerCorpusJoinTestCase
import org.elixir_lang.snapshot.CompilerFacts
import org.elixir_lang.snapshot.CompilerFacts.INPUTS
import org.elixir_lang.snapshot.CompilerJoin
import org.elixir_lang.snapshot.CompilerJoin.Joined
import org.elixir_lang.snapshot.CompilerJoin.Outcome
import org.elixir_lang.snapshot.LegElixirProjectDescriptor
import org.elixir_lang.snapshot.SnapshotTestCase
import java.io.File

/**
 * Pins what the facts report counts, by kind, call kind or `def` keyword, outcome and the form or backing of the
 * declarations matched, over the compiler's facts for the fixture inputs and over small sources.
 */
class FactsReportTest : SnapshotTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = LegElixirProjectDescriptor

    fun testCallableDeclaration() = assertReportMatchesGolden("callable_declaration")

    fun testColumns() = assertReportMatchesGolden("columns")

    fun testInputs() = assertReportMatchesGolden("inputs")

    fun testALocalCallMatchesAClause() =
        assertRows(
            listOf("ref local_function matched clause 1"),
            "ref a.ex:3:14 local_function A f 1",
            "a.ex" to """
                defmodule A do
                  def f(x), do: x
                  def g, do: f(1)
                end
            """
        )

    fun testARemoteCallMatchesADelegation() =
        assertRows(
            listOf("ref remote_function matched delegation 1"),
            "ref a.ex:3:16 remote_function A f 1",
            "a.ex" to """
                defmodule A do
                  defdelegate f(x), to: B
                  def g, do: A.f(1)
                end
            """,
            B,
        )

    fun testAnUnmatchedFactReadsNone() =
        assertRows(
            listOf("ref remote_function nothing none 1"),
            "ref a.ex:2:19 remote_function Nope f 1",
            "a.ex" to """
                defmodule A do
                  def g, do: Nope.f(1)
                end
            """
        )

    fun testAnUndefinedLocalCallReadsNone() =
        assertRows(
            listOf("ref local_function matched none 1"),
            "diag a.ex:3 undefined_function f 2\nref a.ex:3:14 local_function A f 2",
            "a.ex" to """
                defmodule A do
                  def f(x), do: x
                  def g, do: f(1, 2)
                end
            """
        )

    fun testADefMatchesEachClauseOnce() =
        assertRows(
            listOf("def def matched clause 1", "declaration def matched clause 2"),
            "def A def f 1 2",
            "a.ex" to """
                defmodule A do
                  def f(1), do: 1
                  def f(_), do: 2
                end
            """,
            kinds = setOf("def", "declaration"),
        )

    fun testADefMatchesEachClauseInSourceOrder() {
        val file = myFixture.addFileToProject(
            "a.ex",
            """
                defmodule A do
                  def f(1), do: 1
                  def f(_), do: 2
                end
            """.trimIndent()
        )
        val joined = join(file.virtualFile.parent, environment("ELIXIR_VERSION"), listOf("def\tA\tdef\tf\t1\t2"))
        val matched = joined.single { it.kind == "def" }.matched
        val sources = matched.map { it as Declared.Source }

        assertEquals(listOf(Form.CLAUSE, Form.CLAUSE), sources.map { it.form })
        assertEquals(2, sources.map { it.origin.range }.distinct().size)
        assertEquals(sources.map { it.origin.range.startOffset }.sorted(), sources.map { it.origin.range.startOffset })
        assertEquals(
            matched,
            joined.filter { it.kind == "declaration" && it.outcome == Outcome.MATCHED }.flatMap { it.matched }
        )
    }

    fun testADeclarationReadsItsOwnForm() =
        assertRows(
            listOf(
                "def def arity none 1",
                "def def definer none 1",
                "declaration def nothing clause 1",
                "declaration defp nothing clause 1",
            ),
            "def A def h 0 2\ndef A def k 3 3",
            "a.ex" to """
                defmodule A do
                  defp h, do: 1
                  def k, do: 1
                end
            """,
            kinds = setOf("def", "declaration"),
        )

    fun testAnAliasReadsNone() =
        assertRows(
            listOf("alias - matched none 1"),
            "alias a.ex:2:14 A.B",
            "a.ex" to """
                defmodule C do
                  def g, do: A.B
                end
            """,
            "b.ex" to """
                defmodule A.B do
                end
            """,
            kinds = setOf("alias"),
        )

    fun testLabelsAreDistinctSortedAndJoined() {
        val file = myFixture.addFileToProject("a.ex", "defmodule A do\nend").virtualFile
        val compiled = Declared.Compiled(CompiledOrigin(file, "A", "f", 1))
        val clause = Declared.Source(Form.CLAUSE, SourceOrigin(file, 0, TextRange.EMPTY_RANGE))
        val line = "ref\ta.ex:1:1\tremote_function\tA\tf\t1"
        val matched = Joined("ref", Outcome.MATCHED, line, "A\tf\t1", "a.ex:1:1", listOf(compiled, clause, clause))
        val unmatched = Joined("ref", Outcome.NOTHING, line, "A\tf\t1", "a.ex:1:1")

        assertEquals(
            listOf("ref remote_function matched clause+compiled 1", "ref remote_function nothing none 1"),
            FactsReport.rows(listOf(matched, unmatched)).map { it.line().replace('\t', ' ') }
        )
        assertEquals("clause+compiled", FactsReport.label(matched.matched))
        assertEquals("none", FactsReport.label(emptyList()))
    }

    fun testASumAddsEachKeysCounts() {
        val first = listOf(
            "# Elixir 1.20.4\tshard 0",
            FactsReport.COLUMNS,
            "ref\tlocal_function\tmatched\tclause\t2",
            "def\tdef\tmatched\tclause\t1",
        ).joinToString("\n", postfix = "\n")
        val second = listOf(
            "# Elixir 1.20.4\tshard 1",
            FactsReport.COLUMNS,
            "alias\t-\tmatched\tnone\t4",
            "ref\tlocal_function\tmatched\tclause\t3",
            "ref\tlocal_function\tnothing\tnone\t1",
        ).joinToString("\n", postfix = "\n")

        assertEquals(
            listOf(
                "# Elixir 1.20.4",
                FactsReport.COLUMNS,
                "ref\tlocal_function\tmatched\tclause\t5",
                "ref\tlocal_function\tnothing\tnone\t1",
                "alias\t-\tmatched\tnone\t4",
                "def\tdef\tmatched\tclause\t1",
            ).joinToString("\n", postfix = "\n"),
            FactsReport.sum("1.20.4", listOf(first, second))
        )
    }

    fun testCorpusReportIsTheSumOfItsShards() {
        val elixirVersion = environment("ELIXIR_VERSION")
        val shards = (0 until CompilerCorpusJoinTestCase.SHARDS).map { shard ->
            val report = File("$ROOT/$elixirVersion/corpus-$shard.report")

            assertTrue("No committed report for Elixir $elixirVersion, corpus shard $shard: $report", report.isFile)
            report.readText()
        }

        LegManifest.assertMatchesFile(
            ROOT,
            elixirVersion,
            "corpus.report",
            FactsReport.sum(elixirVersion, shards),
            regenerate(elixirVersion)
        )
    }

    private fun assertReportMatchesGolden(input: String) {
        val elixirVersion = environment("ELIXIR_VERSION")
        val (root, _) = copyInputs(INPUTS.getValue(input).removePrefix("$testDataPath/"))
        val facts = File("${CompilerFacts.ROOT}/$elixirVersion/$input.facts").readLines()

        LegManifest.assertMatchesFile(
            ROOT,
            elixirVersion,
            "$input.report",
            FactsReport.report(elixirVersion, join(root, elixirVersion, facts)),
            regenerate(elixirVersion)
        )
    }

    private fun regenerate(elixirVersion: String): String =
        LegManifest.regenerateCommand(FactsReportTest::class, elixirVersion, environment("ERLANG_VERSION"))

    /**
     * Fails unless the report over [facts] and [sources] has [expected] for the rows of [kinds], each a kind, a
     * sub-kind, an outcome, a label and a count. Facts and rows are written with spaces for tabs, so no name in them
     * may hold a space.
     */
    private fun assertRows(
        expected: List<String>,
        facts: String,
        vararg sources: Pair<String, String>,
        kinds: Set<String> = setOf("ref"),
    ) {
        val files = sources.map { (path, text) -> myFixture.addFileToProject(path, text.trimIndent()) }
        val root = files.first().virtualFile.parent
        val tabbed = facts.lines().map { it.replace(' ', '\t') }
        val rows = FactsReport.rows(join(root, environment("ELIXIR_VERSION"), tabbed))

        assertEquals(expected, rows.filter { it.kind in kinds }.map { it.line().replace('\t', ' ') })
    }

    private fun join(root: VirtualFile, elixirVersion: String, facts: List<String>): List<Joined> =
        ReadAction.computeBlocking<List<Joined>, Throwable> {
            CompilerJoin(project, root, elixirVersion).join(facts)
        }

    companion object {
        private val B = "b.ex" to """
            defmodule B do
              def f(x), do: x
            end
        """
    }
}
