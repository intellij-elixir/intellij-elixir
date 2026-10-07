package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.DefinitionTable.Kind

/**
 * `elixir_overridable`'s record of a definition made overridable.
 *
 * @property count how many times it was made overridable, which names the hidden definition
 * @property entry the definition taken out of the table, the latest one
 * @property stored whether `super` or the end of the body stored it
 */
internal class Overridable(val count: Int, val entry: DefinitionTable.Entry) {
    var stored = false
}

internal enum class Overriding { RECORDED, NOT_DEFINED, BAD_KIND }

/** `Module.make_overridable/2` of [nameArity]: its entry taken out of the table and recorded, if it is there. */
internal fun makeOverridable(compiling: Compiling, nameArity: NameArity): Overriding {
    val entry = compiling.table.remove(nameArity) ?: return Overriding.NOT_DEFINED
    val previous = compiling.overridable[nameArity]

    if (previous != null && previous.entry.kind.macro != entry.kind.macro) return Overriding.BAD_KIND

    compiling.overridable[nameArity] = Overridable((previous?.count ?: 0) + 1, entry)

    return Overriding.RECORDED
}

/** `elixir_overridable:store_not_overridden/1`: each overridable definition with no new one is stored back. */
internal fun storeNotOverridden(compiling: Compiling): Expansion.Error? {
    for ((nameArity, overridable) in compiling.overridable) {
        val defined = compiling.table[nameArity]

        when {
            defined == null -> storeOverridable(compiling, nameArity, overridable, hidden = false)
            defined.kind.macro != overridable.entry.kind.macro -> return Expansion.Error("bad_kind", defined.at)
        }
    }

    return null
}

/**
 * `elixir_overridable:store/5`: [overridable]'s definition stored once, as it was, or for `super` as the private
 * `"<name> (overridable <count>)"`. Its default arities were never taken, so they aren't stored again.
 */
internal fun storeOverridable(compiling: Compiling, nameArity: NameArity, overridable: Overridable, hidden: Boolean) {
    if (overridable.stored) return

    overridable.stored = true

    val entry = overridable.entry
    val kind = when {
        !hidden -> entry.kind
        entry.kind.macro -> Kind.DEFMACROP
        else -> Kind.DEFP
    }
    val name = if (hidden) "${nameArity.name} (overridable ${overridable.count})" else nameArity.name

    compiling.table.restore(name, nameArity.arity, kind, entry.at, entry.clauses, entry.defaults, entry.ordered)
}
