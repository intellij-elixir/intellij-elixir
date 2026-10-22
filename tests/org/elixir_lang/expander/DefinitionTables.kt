package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * The expander's definition tables against what a hook read of them on the leg's Elixir: names, arities and kinds on
 * every leg, and from 1.12, when `Module.get_definition/2` gives them, each definition's line and clause count, and from
 * 1.20 whether it was stored for a default arity.
 */
internal object DefinitionTables {
    /**
     * The rows of both sides, [elixir] the hook's and [expander] the expander's, each starting with its module relative
     * to the case module, which is empty for the case module itself.
     */
    class Rows(val elixir: List<String>, val expander: List<String>)

    /**
     * The rows of the case module and of every module under it that the hook reported or the expander reached, at any
     * depth; a module on one side only is a row on the other, `no hook row` or `not expanded`. `null` if the hook didn't
     * run in the case module, whose rows and nested modules' then have nothing to compare. Whether the hook should have
     * run in a module whose compile failed is the caller's to decide.
     *
     * @param hooks what the hook saw, by module
     * @param result the expander's result for the case module
     * @param bodyLine the line the case body starts on, which the rows count their lines from
     */
    fun compare(
        caseModule: String,
        hooks: Map<String, List<ProbeHarness.HookEntry>>,
        result: ExpansionResult,
        bodyLine: Int,
    ): Rows? {
        if (caseModule !in hooks) return null

        val expanded = modules(result).associateBy { it.module }
        val hooked = hooks.filterKeys { it == caseModule || it.startsWith("$caseModule.") }
        val elixir = mutableListOf<String>()
        val expander = mutableListOf<String>()

        for (module in (hooked.keys + expanded.keys).sorted()) {
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
        row(entry.name, entry.arity, entry.kind, entry.line?.let { it - bodyLine + 1 }, entry.clauses, entry.default)

    private fun tableRow(name: String, arity: Int, entry: DefinitionTable.Entry, bodyLine: Int): String {
        val level = legLevel()
        val detailed = level.elixir >= GET_DEFINITION.elixir

        return row(
            name,
            arity,
            entry.kind.name.lowercase(),
            (entry.line - bodyLine + 1).takeIf { detailed },
            entry.clauses.takeIf { detailed },
            // The hook reads whether the meta holds `:context`, which a definition a macro quoted has on every leg.
            (entry.default && level.elixir >= DEFAULT_CLAUSES_TAKE_CONTEXT.elixir ||
                metaValue(entry.at.meta, "context") != null).takeIf { detailed },
        )
    }

    private fun row(name: String, arity: Int, kind: String, line: Int?, clauses: Int?, default: Boolean?) =
        "$name/$arity $kind" + (line?.let { " line $it" } ?: "") + (clauses?.let { " clauses $it" } ?: "") +
            (default?.let { " default $it" } ?: "")

    /** `Module.get_definition/2`, which gives a definition's meta and clauses. */
    private val GET_DEFINITION: ElixirLanguageLevel = ElixirLanguageLevel.of("1.12.0-rc.0")

    /** `elixir-lang/elixir@f1bbb2cd3` puts `:context` in a default arity's meta. */
    private val DEFAULT_CLAUSES_TAKE_CONTEXT: ElixirLanguageLevel = ElixirLanguageLevel.of("1.20.0-rc.0")
}
