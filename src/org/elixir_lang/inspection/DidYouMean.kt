package org.elixir_lang.inspection

import org.elixir_lang.code.InspectAtom
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.RejectedCall

/** The functions the compiler lists after "is undefined or private. Did you mean:", chosen as `did_you_mean/2` does. */
internal object DidYouMean {
    private const val MAX_SUGGESTIONS = 5

    /**
     * A function the compiler lists as `name/arity`, [distance] from the name called: 1.0 for the arities of the name
     * itself. [open] says it accepts every arity from [arity] up; [macro] that it is listed before the functions.
     */
    class Entry(
        val name: String,
        val arity: Int,
        val open: Boolean = false,
        val macro: Boolean = false,
        val distance: Double = 1.0
    ) {
        override fun toString(): String = "${InspectAtom.remoteCall(name)}/$arity${if (open) "+" else ""}"
    }

    /**
     * The entries to name: the nearest five in the order given, which stays for equal distances, then by name, which
     * keeps the arities of one name in the order given.
     */
    fun suggestions(entries: List<Entry>): List<Entry> =
        entries.sortedByDescending(Entry::distance).take(MAX_SUGGESTIONS).sortedWith { a, b -> compareNames(a.name, b.name) }

    /** [named]'s arities, one entry for each distinct `name/arity`, in the order an Elixir module lists its exports. */
    fun arities(named: List<RejectedCall.Named<Found>>): List<Entry> =
        named
            .flatMap { (found, arities, from) ->
                val declaration = found.candidate.declaration
                val macro = declaration.capabilities.compileTime

                arities.map { Entry(declaration.name, it, macro = macro) } +
                    listOfNotNull(from?.let { Entry(declaration.name, it, open = true, macro = macro) })
            }
            .distinctBy { Triple(it.name, it.arity, it.open) }
            .sortedWith(compareBy<Entry> { !it.macro }.thenComparator { a, b -> compareNames(a.name, b.name) }.thenBy { it.arity })

    /** The order of atoms, which is by code point. */
    private fun compareNames(a: String, b: String): Int {
        var i = 0
        var j = 0

        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)

            if (x != y) return x.compareTo(y)

            i += Character.charCount(x)
            j += Character.charCount(y)
        }

        return (a.length - i).compareTo(b.length - j)
    }
}
