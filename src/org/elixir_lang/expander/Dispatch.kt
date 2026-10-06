package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_IMPORTS_EVERY_ARITY
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_IMPORTS_NON_LIST_FALLS_THROUGH
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.Meta

/**
 * A call as `elixir_dispatch` resolves it: [receiver] and [name] are after `elixir_rewrite:inline/3`, and every name is
 * atom text.
 */
data class Dispatch(val kind: Kind, val receiver: String, val name: String, val arity: Int) {
    /** The kind of the trace event Elixir emits for the call. */
    enum class Kind { IMPORTED_FUNCTION, IMPORTED_MACRO, REMOTE_FUNCTION, REMOTE_MACRO, LOCAL_FUNCTION, LOCAL_MACRO }
}

/** The kind of the trace event `quote` emits for an import it quotes. */
enum class QuotedImportKind { IMPORTED_FUNCTION, IMPORTED_MACRO, IMPORTED_QUOTED }

/** What `elixir_dispatch:find_import_by_name_arity/4` finds for a call's name and arity. */
sealed class ImportMatch {
    data class Function(val receiver: String) : ImportMatch()

    data class Macro(val receiver: String) : ImportMatch()

    /** More than one import brings the name and arity in: the functions' receivers, then the macros'. */
    data class Ambiguous(val receivers: List<String>) : ImportMatch()

    data object None : ImportMatch()

    /** The import `quote` recorded in the call's meta: a required call of [receiver], whatever [Env] imports. */
    data class Quoted(val receiver: String) : ImportMatch()

    /** Recorded import meta Elixir crashes on, or hands on as a receiver that isn't a module. */
    data object Unreadable : ImportMatch()
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
 * `elixir_dispatch:find_import_by_name_arity/4`, `find_dispatch/4` before 1.14, for a call of [name] and [arity] whose
 * meta is [meta] in [env], with [extra] imported macros ahead of [env]'s, as the dispatch site passes them.
 */
fun findImportByNameArity(
    meta: Meta,
    name: String,
    arity: Int,
    extra: List<Env.Imports>,
    env: Env,
    level: ElixirLanguageLevel,
): ImportMatch {
    isImport(meta, arity, level)?.let { return it }

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

/**
 * `is_import/1`, `is_import/2` from 1.14: the import `quote` recorded, read only beside `context:`. Before 1.14 it is
 * `import: Module`, for any arity; from 1.14 the entry for [arity] in `imports: [{Arity, Module}]`.
 */
private fun isImport(meta: Meta, arity: Int, level: ElixirLanguageLevel): ImportMatch? {
    if (!QUOTE_IMPORTS_EVERY_ARITY.isSufficient(level)) {
        val import = metaValue(meta, "import") ?: return null

        metaValue(meta, "context") ?: return null

        return quoted(import)
    }

    val imports = metaValue(meta, "imports") ?: return null

    if (imports !is Meta.Value.List && QUOTE_IMPORTS_NON_LIST_FALLS_THROUGH.isSufficient(level)) return null

    metaValue(meta, "context") ?: return null

    val entries = (imports as? Meta.Value.List)?.elements ?: return ImportMatch.Unreadable
    val entry = entries.filterIsInstance<Meta.Value.Tuple>()
        .firstOrNull { (it.elements.firstOrNull() as? Meta.Value.Integer)?.value == arity.toLong() }
        ?: return null
    // Elixir's match on `{Arity, Receiver}` crashes on a tuple of any other size.
    val receiver = entry.elements.takeIf { it.size == 2 }?.get(1) ?: return ImportMatch.Unreadable

    return quoted(receiver)
}

private fun quoted(receiver: Meta.Value): ImportMatch =
    (receiver as? Meta.Value.Atom)?.let { ImportMatch.Quoted(it.name) } ?: ImportMatch.Unreadable
