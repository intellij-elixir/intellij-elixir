package org.elixir_lang.declaration

/**
 * The arities of [arity] that an `import` [admits], or `null` when it admits every one of them or [arity] is not a
 * closed interval.
 */
fun importedArities(arity: ArityKnowledge, admits: (Int) -> Boolean): Set<Int>? {
    val arities = when (arity) {
        is ArityKnowledge.Exact, is ArityKnowledge.Range -> arity.listing().arities
        is ArityKnowledge.Open, ArityKnowledge.Unknown -> return null
    }

    return arities.filterTo(mutableSetOf(), admits).takeIf { it.size < arities.size }
}
