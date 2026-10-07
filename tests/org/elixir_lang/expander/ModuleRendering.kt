package org.elixir_lang.expander

/** A compiled module as one line: how it ended, then each definition in its table. */
internal object ModuleRendering {
    /** `module <name> <ended>`, then `<kind> <name>/<arity> line <n> clauses <n>` for each entry, `|`-separated. */
    fun render(code: String, result: ExpansionResult): String {
        val ended = when (val ended = result.ended) {
            ExpansionResult.Ended.Compiled -> "compiled"
            ExpansionResult.Ended.Tainted -> "tainted"
            is ExpansionResult.Ended.Raised -> "raised ${ended.error.kind} `${ended.error.at.meta.origin.substring(code)}`"
            is ExpansionResult.Ended.Crashed -> "crashed ${ended.error.kind}"
            is ExpansionResult.Ended.Stopped -> "stopped `${ended.at.meta.origin.substring(code)}`"
        }
        val table = result.table.entries.map { (nameArity, entry) ->
            "${entry.kind.name.lowercase()} ${nameArity.name}/${nameArity.arity} line ${entry.line} " +
                "clauses ${entry.clauses}"
        }

        return (listOf("module ${result.module} $ended") + table).joinToString(" | ")
    }
}
