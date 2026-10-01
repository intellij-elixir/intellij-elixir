package org.elixir_lang.expander

import org.elixir_lang.NameArity

/** What `elixir_dispatch:find_import_by_name_arity/4` finds for a call's name and arity. */
sealed class ImportMatch {
    data class Function(val receiver: String) : ImportMatch()

    data class Macro(val receiver: String) : ImportMatch()

    /** More than one import brings the name and arity in: the functions' receivers, then the macros'. */
    data class Ambiguous(val receivers: List<String>) : ImportMatch()

    data object None : ImportMatch()
}

/**
 * `elixir_dispatch:find_import_by_name_arity/4` for a call of [name] and [arity] in [env], with [extra] imported
 * macros ahead of [env]'s, as the dispatch site passes them. The branch that reads `imports:` from a quoted call's
 * meta is `quote`'s.
 */
fun findImportByNameArity(name: String, arity: Int, extra: List<Env.Imports>, env: Env): ImportMatch {
    val nameArity = NameArity(name, arity)
    val functions = env.functions.filter { nameArity in it.nameArities }.map { it.module }
    val macros = (extra + env.macros).filter { nameArity in it.nameArities }.map { it.module }

    return when {
        functions.isEmpty() && macros.size == 1 -> ImportMatch.Macro(macros.single())
        functions.size == 1 && macros.isEmpty() -> ImportMatch.Function(functions.single())
        functions.isEmpty() && macros.isEmpty() -> ImportMatch.None
        else -> ImportMatch.Ambiguous(functions + macros)
    }
}
