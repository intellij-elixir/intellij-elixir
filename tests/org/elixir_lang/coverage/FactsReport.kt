package org.elixir_lang.coverage

import org.elixir_lang.declaration.Declared
import org.elixir_lang.snapshot.CompilerFacts
import org.elixir_lang.snapshot.CompilerFacts.Fact
import org.elixir_lang.snapshot.CompilerJoin
import org.elixir_lang.snapshot.CompilerJoin.Joined
import org.elixir_lang.snapshot.CompilerJoin.Outcome
import org.elixir_lang.snapshot.label

/**
 * How many compiler facts the plugin answers, by kind, call kind or `def` keyword, outcome and the form or backing of
 * the declarations it matched, as the tab-separated report committed under [ROOT] for each Elixir version.
 */
object FactsReport {
    const val ROOT = "testData/org/elixir_lang/coverage/facts"

    const val COLUMNS = "kind\tsub-kind\toutcome\tlabel\tcount"

    /** The sub-kind of an `alias`, which has none. */
    private const val NO_SUB_KIND = "-"

    /** The label of a fact that matched no declaration. */
    private const val NO_LABEL = "none"

    data class Row(
        val kind: String,
        val subKind: String,
        val outcome: Outcome,
        val label: String,
        val count: Int,
    ) {
        /** What names the row in a report: every column but its count. */
        fun key(): String = listOf(kind, subKind, outcome.text, label).joinToString("\t")

        fun line(): String = "${key()}\t$count"
    }

    private val ORDER = compareBy<Row>(
        { CompilerJoin.KINDS.indexOf(it.kind) },
        { it.subKind },
        { it.outcome.ordinal },
        { it.label },
    )

    fun rows(joined: List<Joined>): List<Row> =
        added(joined.map { Row(it.kind, subKind(it), it.outcome, label(it.matched), 1) })

    /** The distinct forms and backings of [matched], sorted and joined with `+`, or `none`. */
    fun label(matched: List<Declared>): String =
        matched.map { it.label() }.distinct().sorted().joinToString("+").ifEmpty { NO_LABEL }

    /** The report of [joined] for [elixirVersion], and for corpus [shard] when it is one. */
    fun report(elixirVersion: String, joined: List<Joined>, shard: Int? = null): String =
        render(elixirVersion, shard, rows(joined))

    /** The report of the counts in [reports], each as [report] writes it, added per kind, sub-kind, outcome and label. */
    fun sum(elixirVersion: String, reports: List<String>): String {
        val rows = reports.flatMap { report -> report.lines().mapNotNull(::row) }

        return render(elixirVersion, null, added(rows))
    }

    /** The key of [line] of a report, as [Row.key] gives it, or null for a header line. */
    fun key(line: String): String? = row(line)?.key()

    /** The row [line] of a report is, or null for a header line. */
    private fun row(line: String): Row? {
        if (line.isEmpty() || line.startsWith("#") || line == COLUMNS) return null

        val (kind, subKind, outcome, label, count) = line.split('\t')

        return Row(kind, subKind, Outcome.entries.single { it.text == outcome }, label, count.toInt())
    }

    private fun added(rows: List<Row>): List<Row> =
        rows.groupBy { it.copy(count = 0) }
            .map { (key, group) -> key.copy(count = group.sumOf { it.count }) }
            .sortedWith(ORDER)

    private fun render(elixirVersion: String, shard: Int?, rows: List<Row>): String =
        (listOf("# Elixir $elixirVersion" + (shard?.let { "\tshard $it" } ?: ""), COLUMNS) + rows.map { it.line() })
            .joinToString("\n", postfix = "\n")

    private fun subKind(joined: Joined): String =
        when (joined.kind) {
            "ref" -> (CompilerFacts.parse(joined.line) as Fact.Ref).kind
            "def" -> (CompilerFacts.parse(joined.line) as Fact.Def).kind
            "declaration" -> joined.line.split('\t')[2]
            "alias" -> NO_SUB_KIND
            else -> error("no sub-kind for a ${joined.kind} fact: ${joined.line}")
        }
}
