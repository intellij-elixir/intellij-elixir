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

/** What `elixir_dispatch:find_imports/3` finds for a name. */
sealed class NameImports {
    /** `[{arity, module}]`, by arity. */
    data class Found(val imports: List<Pair<Int, String>>) : NameImports()

    /** Two modules import the name at [arity]: the one `E` holds later, then the one it holds first. */
    data class Ambiguous(val arity: Int, val modules: List<String>) : NameImports()
}

/** `elixir_dispatch:find_imports/3`: every arity [env] imports [name] at, from its functions and then its macros. */
fun findImports(name: String, env: Env): NameImports {
    val found = sortedMapOf<Int, String>()

    for ((module, nameArities) in env.functions + env.macros) {
        for ((importedName, arity) in nameArities) {
            if (importedName != name) continue

            found[arity]?.let { return NameImports.Ambiguous(arity, listOf(module, it)) }
            found[arity] = module
        }
    }

    return NameImports.Found(found.map { (arity, module) -> arity to module })
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
