package org.elixir_lang.expander.differential

import com.intellij.util.diff.Diff
import junit.framework.TestCase
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.expander.CASE_MODULE_PATTERN
import org.elixir_lang.expander.Expansion
import org.elixir_lang.expander.ExpansionProbes
import org.elixir_lang.expander.ExpansionProbes.CaseExpansion
import org.elixir_lang.expander.ExpansionResult.Ended
import org.elixir_lang.expander.TOKEN_PATTERN
import org.elixir_lang.lowering.ElixirAst
import java.util.TreeMap
import kotlin.reflect.KClass

/**
 * Every case's outcome against Elixir, one row each: `match`; a line diff of the expander's text against Elixir's;
 * `unported <node>`; `stopped <node>`, for a module whose expansion stops at a node the comparison can't follow; or
 * `stops at <macro>` followed by `match` or the diff up to that macro. This is the one routine that every context of
 * the differential records its cases through.
 */
internal class ProbeOutcomes(private val probes: ExpansionProbes) {
    /**
     * @property status what the row says of the case
     * @property diff the expander's lines (`-`) and Elixir's (`+`) that differ, in hunks, if the case diverges
     */
    class Row(val status: String, val diff: List<String> = emptyList()) {
        /** Whether the expander and Elixir agree on the whole case. */
        val matches: Boolean get() = status == MATCH
    }

    /** The row of each of [expansions], by case name, in name order. */
    fun of(expansions: Map<String, CaseExpansion>): Map<String, Row> {
        val unported = expansions.filterValues { it.outcome is Expansion.Unported }
        val opaque = expansions.filterValues { it.outcome is Expansion.Opaque }
        val rest = expansions.filterKeys { it !in unported && it !in opaque }
        val rows = TreeMap<String, Row>()

        unported.forEach { (name, expansion) ->
            rows[name] = Row("$UNPORTED ${describe((expansion.outcome as Expansion.Unported).at)}")
        }
        probes.compareUpToMacro(opaque).forEach { (name, comparison) ->
            rows[name] = row(comparison, "$STOPS_AT ${normalised(opaque.getValue(name).macro.orEmpty())} ")
        }
        probes.compare(rest).first.forEach { (name, comparison) -> rows[name] = row(comparison, "") }
        (rest.keys - rows.keys).forEach { name ->
            (rest.getValue(name).ended as? Ended.Stopped)?.let { rows[name] = Row("$STOPPED ${describe(it.at)}") }
        }

        check(rows.keys == expansions.keys) { "no row for ${expansions.keys - rows.keys}" }

        return rows
    }

    /** How many of [rows] are of each kind. */
    fun summary(rows: Map<String, Row>): String =
        "${rows.size} cases, ${rows.values.count { it.matches }} match, " +
            "${rows.values.count { it.status.startsWith(UNPORTED) }} unported, " +
            "${rows.values.count { it.status.startsWith(STOPPED) }} stopped, " +
            "${rows.values.count { it.status.startsWith(STOPS_AT) }} stop at a macro, " +
            "${rows.values.count { it.status == DIFF }} diverge"

    /**
     * The rows of [expansions] against the leg's golden `<name>.txt` of every case's outcome, with at least [floor] of
     * them `match`, which may only grow. [test] is the class that regenerates the golden.
     */
    fun assertGolden(name: String, expansions: Map<String, CaseExpansion>, floor: Int, test: KClass<*>) {
        val rows = of(expansions)
        val matched = rows.values.count { it.matches }
        val elixirVersion = LegManifest.environment("ELIXIR_VERSION")

        println("$name: ${summary(rows)}")

        LegManifest.assertMatchesFile(
            GOLDENS,
            elixirVersion,
            "$name.txt",
            golden(rows),
            LegManifest.regenerateCommand(test, elixirVersion, LegManifest.environment("ERLANG_VERSION")),
        )
        TestCase.assertTrue("$name: $matched cases match, below $floor", matched >= floor)
    }

    /** [rows] as the text of a golden: LF-only lines, each starting with its case's name. */
    fun golden(rows: Map<String, Row>): String =
        rows.entries.joinToString("") { (name, row) ->
            (listOf(row.status) + row.diff).joinToString("") { "$name\t$it\n" }
        }

    internal fun row(comparison: ExpansionProbes.Comparison, prefix: String): Row =
        if (comparison.matches) Row(prefix + MATCH) else Row(prefix + DIFF, diff(comparison))

    internal fun diff(comparison: ExpansionProbes.Comparison): List<String> {
        val before = Diff.splitLines(normalised(comparison.expected))
        val after = Diff.splitLines(normalised(comparison.actual))

        return Diff.buildChanges(before, after)?.toList().orEmpty().flatMap { change ->
            listOf("@@ -${range(change.line0, change.deleted)} +${range(change.line1, change.inserted)} @@") +
                before.slice(change.line0 until change.line0 + change.deleted).map { "-$it" } +
                after.slice(change.line1 until change.line1 + change.inserted).map { "+$it" }
        }
    }

    /** A unified diff's `start,count`, where an empty range starts at the line before it. */
    private fun range(start: Int, count: Int): String = "${if (count == 0) start else start + 1},$count"

    /** [text] without the compile's token, or the index of the case module in its batch. */
    private fun normalised(text: String): String =
        text.replace(CASE_MODULE_PATTERN, "{token}.Case").replace(TOKEN_PATTERN, "{token}")

    /** A call as its name and arity, anything else as its kind. */
    private fun describe(node: ElixirAst): String =
        when {
            node is ElixirAst.Call && node.callee is ElixirAst.Literal.Atom ->
                node.callee.name + (node.arguments?.let { "/${it.size}" } ?: " (variable)")
            node is ElixirAst.Call -> "remote or anonymous call"
            else -> node.javaClass.simpleName
        }

    private companion object {
        const val MATCH = "match"
        const val DIFF = "diff"
        const val UNPORTED = "unported"
        const val STOPPED = "stopped"
        const val STOPS_AT = "stops at"

        const val GOLDENS = "testData/org/elixir_lang/expander/differential"
    }
}
