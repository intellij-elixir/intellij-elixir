package org.elixir_lang.inspection

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.QualifiedExports
import org.elixir_lang.declaration.RejectedCall
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.expander.ATOM_ORDER
import org.elixir_lang.expander.ModuleExports
import org.elixir_lang.language_level.ElixirLanguageFeature
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.unicode_util.Graphemes

/** The functions the compiler lists after "is undefined or private. Did you mean:", chosen as `did_you_mean/2` does. */
internal object DidYouMean {
    private const val MAX_SUGGESTIONS = 5
    private const val THRESHOLD = 0.77
    private val BY_NAME: Comparator<Entry> = compareBy(ATOM_ORDER, Entry::name)
    private val BY_NAME_THEN_ARITY: Comparator<Entry> = BY_NAME.thenBy(Entry::arity)
    private val MACROS_FIRST: Comparator<Entry> = compareBy<Entry> { !it.macro }.then(BY_NAME_THEN_ARITY)

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
        entries.sortedByDescending(Entry::distance).take(MAX_SUGGESTIONS).sortedWith(BY_NAME)

    /**
     * [named]'s arities, one entry for each distinct `name/arity`: for a [remote] call, the nearest five in the order
     * [level]'s hint lists them; for a local one, all of them in the order an Elixir module lists its exports.
     */
    fun arities(named: List<RejectedCall.Named<Found>>, remote: Boolean, level: ElixirLanguageLevel): List<Entry> {
        val entries = named.flatMap { (found, arities, from) ->
            entries(found.candidate.declaration, ArityKnowledge.Listing(arities, from))
        }

        return if (remote) suggestions(ordered(entries, level)) else distinct(entries).sortedWith(MACROS_FIRST)
    }

    /**
     * The functions and macros [use]'s module exports, whose names are near the name [use] calls: what [level]'s
     * compiler lists for a name its module exports at no arity, which leaves out the deprecated ones. Where the source
     * can't say what the module exports, they are what completion offers after [use]'s qualifier.
     */
    @RequiresReadLock
    fun nearNames(use: Use.Named, level: ElixirLanguageLevel): List<Entry> {
        val source = sourceFor(Feature.REFERENCES_INSPECTION)
        val exports = source.qualifiedExports(use.entrance)?.let { entries(it, level) }
            ?: source.visible(use.entrance).mapNotNull { it.declaration }.flatMap(::entries)

        return suggestions(
            ordered(exports, level).mapNotNull { entry ->
                distance(use.name, entry.name, level).takeIf { it >= THRESHOLD }?.let { entry.copy(distance = it) }
            }
        )
    }

    /**
     * A remote module's [entries] in the order [level]'s hint lists them, one for each distinct `name/arity`: before
     * the hint reads the module's exports, by name then arity, macros among the functions, as the checker's cached
     * exports are sorted; from it, macros first, then by name and arity.
     */
    fun ordered(entries: List<Entry>, level: ElixirLanguageLevel): List<Entry> =
        distinct(entries).sortedWith(
            if (ElixirLanguageFeature.UNDEFINED_FUNCTION_HINT_READS_MODULE_EXPORTS.isSufficient(level)) MACROS_FIRST else BY_NAME_THEN_ARITY
        )

    /**
     * [exports] but the deprecated, with `__info__/1` and a behaviour's `behaviour_info/1` before the hint reads the
     * module's exports, as the checker's cached exports add them.
     */
    private fun entries(exports: QualifiedExports, level: ElixirLanguageLevel): List<Entry> {
        val listed = exports.exports
            .filterNot(QualifiedExports.Export::deprecated)
            .map { Entry(it.nameArity.name, it.nameArity.arity, macro = it.macro) }

        return if (ElixirLanguageFeature.UNDEFINED_FUNCTION_HINT_READS_MODULE_EXPORTS.isSufficient(level)) {
            listed
        } else {
            listed + listOfNotNull(ModuleExports.INFO.takeIf { exports.hasInfo }, ModuleExports.BEHAVIOUR_INFO.takeIf { exports.behaviourInfo })
                .map { Entry(it.name, it.arity) }
        }
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

    private fun distinct(entries: List<Entry>): List<Entry> = entries.distinctBy { Triple(it.name, it.arity, it.open) }
}
