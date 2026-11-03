package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import org.elixir_lang.NameArity
import org.elixir_lang.coverage.ExpanderProgress

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

/** How [result] ended: `compiled`, or a stop by its kind and the dispatch it stopped at. */
internal fun ending(result: ExpansionResult): String =
    when (val ended = result.ended) {
        ExpansionResult.Ended.Compiled -> "compiled"
        is ExpansionResult.Ended.Stopped -> "stopped ${ExpanderProgress.shape(ended.at)}"
        else -> "$ended"
    }

/** The functions and macros [result] stored, as [exported] gives them. */
internal fun stored(result: ExpansionResult): String {
    val named = { kind: DefinitionTable.Kind -> result.table.entries.filterValues { it.kind == kind }.keys }

    return exported(named(DefinitionTable.Kind.DEF), named(DefinitionTable.Kind.DEFMACRO))
}

/** The `functions` and `macros` of Elixir's map [exports], each a list of `{name, arity}`, as [exported] gives them. */
internal fun exported(exports: OtpErlangMap): String =
    exported(nameArities(exports.get(OtpErlangAtom("functions"))), nameArities(exports.get(OtpErlangAtom("macros"))))

/** [exports]' functions and macros, as [exported] gives them. */
internal fun exported(exports: ModuleExports.Present): String = exported(exports.functions, exports.macros)

/** [functions] and [macros], each list as [names] gives it. */
internal fun exported(functions: Collection<NameArity>, macros: Collection<NameArity>): String =
    "functions: ${names(functions)}; macros: ${names(macros)}"

private fun nameArities(term: OtpErlangObject): List<NameArity> = (term as OtpErlangList).elements().map { NameArity.from(it)!! }

private fun names(nameArities: Collection<NameArity>): String =
    nameArities.sortedWith(compareBy({ it.name }, { it.arity })).joinToString(", ") { "${it.name}/${it.arity}" }
