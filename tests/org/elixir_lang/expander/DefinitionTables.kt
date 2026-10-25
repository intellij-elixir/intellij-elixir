package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertTrue

/**
 * The expander's definition tables against what a hook read of them on the leg's Elixir: names, arities and kinds on
 * every leg, and from 1.12, when `Module.get_definition/2` gives them, each definition's line, clause count and whether
 * its meta holds `:context`.
 */
internal object DefinitionTables {
    /**
     * The rows of both sides, [elixir] the hook's and [expander] the expander's, each starting with its module relative
     * to the case module, which is empty for the case module itself.
     */
    class Rows(val elixir: List<String>, val expander: List<String>)

    /**
     * The rows of the case module and of every module under it that the hook reported or the expander reached, at any
     * depth; a module on one side only is a row on the other, `no hook row` or `not expanded`. Where the hook didn't run
     * in the case module, because its compile failed first, only the modules the hook reported are rows, as what the
     * expander reached after the failure has nothing to compare; `null` if there is none. Whether the hook should have
     * run in a module whose compile failed is the caller's to decide.
     *
     * A compile that succeeded must have hooked every module it defined under the case module, or the comparison fails
     * naming the modules it missed: a harness bug, since a module the hook missed is on neither side.
     *
     * @param hooks what the hook saw, by module
     * @param result the expander's result for the case module
     * @param bodyLine the line the case body starts on, which the rows count their lines from
     * @param compiled the compile [hooks] came from, whose traced environments name each module it defined
     */
    fun compare(
        caseModule: String,
        hooks: Map<String, List<ProbeHarness.HookEntry>>,
        result: ExpansionResult,
        bodyLine: Int,
        compiled: Quoter.Compiled,
    ): Rows? {
        assertEveryModuleHooked(caseModule, hooks, compiled)

        val hooked = hooks.filterKeys { DispatchEvents.atOrUnder(it, caseModule) }

        if (hooked.isEmpty()) return null

        val expanded = modules(result).associateBy { it.module }
        val elixir = mutableListOf<String>()
        val expander = mutableListOf<String>()
        val names = if (caseModule in hooked) hooked.keys + expanded.keys else hooked.keys

        for (module in names.sorted()) {
            val suffix = module.removePrefix(caseModule)
            val theirs = hooked[module]?.map { hookRow(it, bodyLine) }
            val ours = expanded[module]?.let { compared(hooked[module].orEmpty(), it.table, bodyLine) }

            elixir += (theirs ?: listOf("no hook row")).map { under(suffix, it) }
            expander += (ours ?: listOf("not expanded")).map { under(suffix, it) }
        }

        return Rows(elixir, expander)
    }

    /** [rows] under [heading], sorted. */
    fun render(heading: String, rows: List<String>) =
        "== ${heading.replace("\n", "; ")}\n" + rows.sorted().joinToString("") { "  $it\n" }

    /**
     * Every module a compile defines is the module of a traced environment, but for one defined while a function the
     * compile calls runs, which this misses.
     */
    private fun assertEveryModuleHooked(
        caseModule: String,
        hooks: Map<String, List<ProbeHarness.HookEntry>>,
        compiled: Quoter.Compiled,
    ) {
        if (compiled.status != OtpErlangAtom("ok")) return

        val missed = DispatchEvents.modules(compiled.events)
            .filter { DispatchEvents.atOrUnder(it, caseModule) && it !in hooks }

        assertTrue("the harness left the hook out of ${missed.joinToString()}, which the compile defined", missed.isEmpty())
    }

    private fun under(suffix: String, row: String) = if (suffix.isEmpty()) row else "$suffix $row"

    private fun modules(result: ExpansionResult): List<ExpansionResult> = listOf(result) + result.nested.flatMap(::modules)

    /**
     * The expander's [table] read against the hook's rows by the may-be-present rule: Elixir's table holds
     * every ordered entry, an unordered entry may be absent and compares its kind only, and every Elixir entry is one
     * of the expander's. An unnamed entry pairs with an Elixir entry of its kind that no named entry claims; one that
     * is a statement, so unordered only for its name, must find one.
     */
    private fun compared(
        hooked: List<ProbeHarness.HookEntry>,
        table: DefinitionTable,
        bodyLine: Int,
    ): List<String> {
        val elixir = hooked.associateBy { it.name to it.arity }
        val actual = mutableListOf<String>()
        val claimed = mutableSetOf<Pair<String, Int>>()

        for ((nameArity, entry) in table.entries) {
            val key = nameArity.name to nameArity.arity
            val theirs = elixir[key]

            when {
                entry.ordered || theirs != null && theirs.kind != entry.kind.name.lowercase() ->
                    actual += tableRow(nameArity.name, nameArity.arity, entry, bodyLine)
                theirs != null -> actual += hookRow(theirs, bodyLine)
            }

            if (theirs != null) claimed += key
        }

        val unclaimed = hooked.filterTo(mutableListOf()) { (it.name to it.arity) !in claimed }

        for ((unnamedKind, _, statement) in table.unnamed) {
            val kind = unnamedKind.name.lowercase()
            val pair = unclaimed.firstOrNull { it.kind == kind }

            when {
                pair != null -> {
                    unclaimed -= pair
                    actual += hookRow(pair, bodyLine)
                }
                statement -> actual += "<unnamed> $kind"
            }
        }

        return actual
    }

    private fun hookRow(entry: ProbeHarness.HookEntry, bodyLine: Int): String =
        row(entry.name, entry.arity, entry.kind, entry.line?.let { it - bodyLine + 1 }, entry.clauses, entry.context)

    private fun tableRow(name: String, arity: Int, entry: DefinitionTable.Entry, bodyLine: Int): String {
        val level = legLevel()
        val detailed = level.elixir >= GET_DEFINITION.elixir

        return row(
            name,
            arity,
            entry.kind.name.lowercase(),
            (entry.line - bodyLine + 1).takeIf { detailed },
            entry.clauses.takeIf { detailed },
            (metaValue(entry.heads.first().stored.meta, "context") != null).takeIf { detailed },
        )
    }

    private fun row(name: String, arity: Int, kind: String, line: Int?, clauses: Int?, context: Boolean?) =
        "$name/$arity $kind" + (line?.let { " line $it" } ?: "") + (clauses?.let { " clauses $it" } ?: "") +
            (context?.let { " context $it" } ?: "")

    /** `Module.get_definition/2`, which gives a definition's meta and clauses. */
    private val GET_DEFINITION: ElixirLanguageLevel = ElixirLanguageLevel.of("1.12.0-rc.0")
}
