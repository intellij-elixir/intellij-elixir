package org.elixir_lang.inspection

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.RejectedCall
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.unicode_util.Graphemes

/** The functions the compiler lists after "is undefined or private. Did you mean:", chosen as `did_you_mean/2` does. */
internal object DidYouMean {
    private const val MAX_SUGGESTIONS = 5
    private const val THRESHOLD = 0.77

    /**
     * A function the compiler lists as `name/arity`, [distance] from the name called: 1.0 for the arities of the name
     * itself. [open] says it accepts every arity from [arity] up; [macro] that it is listed before the functions.
     */
    data class Entry(
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
        exported(
            named.flatMap { (found, arities, from) ->
                entries(found.candidate.declaration, ArityKnowledge.Listing(arities, from))
            }
        )

    /**
     * The functions and macros [use]'s module exports, whose names are near the name [use] calls: what the compiler
     * lists for a name its module exports at no arity. The module's exports are what completion offers after
     * [use]'s qualifier.
     */
    @RequiresReadLock
    fun nearNames(use: Use.Named): List<Entry> {
        val level = ElixirLanguageLevelResolver.languageLevelFor(use.entrance)
        val exports = sourceFor(Feature.REFERENCES_INSPECTION).visible(use.entrance)
            .mapNotNull { it.declaration }
            .flatMap(::entries)

        return suggestions(
            exported(exports).mapNotNull { entry ->
                distance(use.name, entry.name, level).takeIf { it >= THRESHOLD }?.let { entry.copy(distance = it) }
            }
        )
    }

    /** `String.jaro_distance/2`, which is `:string.jaro_similarity/2` over grapheme clusters. */
    fun distance(a: String, b: String, level: ElixirLanguageLevel): Double =
        when {
            a == b -> 1.0
            a.isEmpty() || b.isEmpty() -> 0.0
            else -> {
                val graphemes = Graphemes.of(level)

                similarity(clusters(graphemes, a), clusters(graphemes, b))
            }
        }

    private fun clusters(graphemes: Graphemes, text: String): List<String> =
        graphemes.clusters(text).map { text.substring(it.first, it.last + 1) }.toList()

    /** The matches within the window, their transpositions counted as OTP's `jaro_match/6` and `jaro_calc_mt/4` do. */
    private fun similarity(a: List<String>, b: List<String>): Double {
        val window = maxOf(1, maxOf(a.size, b.size) / 2)
        val indexes = HashMap<String, MutableList<Int>>()

        b.forEachIndexed { index, grapheme -> indexes.getOrPut(grapheme) { mutableListOf() }.add(index) }

        val matchedA = mutableListOf<String>()
        val matchedB = mutableListOf<Pair<Int, String>>()

        a.forEachIndexed { position, grapheme ->
            val candidates = indexes[grapheme] ?: return@forEachIndexed
            val min = position - window
            val max = position + window
            val skipped = candidates.takeWhile { it <= min }.size
            val index = candidates.getOrNull(skipped)?.takeIf { it < max }

            if (index != null) {
                indexes[grapheme] = candidates.subList(skipped + 1, candidates.size)
                matchedA.add(grapheme)
                matchedB.add(index to grapheme)
            }
        }

        if (matchedA.isEmpty()) return 0.0

        val matches = matchedA.size
        val transposed = matchedA.zip(matchedB.sortedBy { it.first }).count { (grapheme, other) -> grapheme != other.second }

        return (matches.toDouble() / a.size + matches.toDouble() / b.size + (matches - transposed / 2.0) / matches) / 3
    }

    /** One entry for each arity [declaration] accepts. */
    private fun entries(declaration: Declaration): List<Entry> = entries(declaration, declaration.arity.listing())

    /** One entry for each arity of [listing], and one open entry where it opens. */
    private fun entries(declaration: Declaration, listing: ArityKnowledge.Listing): List<Entry> =
        listing.arities.map { entry(declaration, it) } +
            listOfNotNull(listing.from?.let { entry(declaration, it, open = true) })

    private fun entry(declaration: Declaration, arity: Int, open: Boolean = false): Entry =
        Entry(declaration.name, arity, open, declaration.capabilities.compileTime)

    /** One entry for each distinct `name/arity`, in the order of `exports_for/1`: macros, then functions, by name. */
    private fun exported(entries: List<Entry>): List<Entry> =
        entries
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
