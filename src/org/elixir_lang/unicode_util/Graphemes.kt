package org.elixir_lang.unicode_util

import org.elixir_lang.language_level.ElixirLanguageFeature.INDIC_CONJUNCT_GRAPHEME_CLUSTERS
import org.elixir_lang.language_level.ElixirLanguageFeature.UNICODE_14_GRAPHEME_CLUSTERS
import org.elixir_lang.language_level.ElixirLanguageFeature.UNICODE_15_GRAPHEME_CLUSTERS
import org.elixir_lang.language_level.ElixirLanguageFeature.WIDER_INDIC_CONJUNCT_GRAPHEME_CLUSTERS
import org.elixir_lang.language_level.ElixirLanguageLevel
import java.util.concurrent.ConcurrentHashMap

/**
 * Grapheme clusters as one Erlang/OTP release's `unicode_util:gc/1` splits them, which is how Elixir counts columns in
 * quoted text. It departs from UAX #29: after an emoji and a zero width joiner only another pictograph joins, a spacing
 * mark extends like any other mark, and from OTP 28 conjuncts join across every script's consonants.
 */
class Graphemes private constructor(
    private val table: Table,
    private val conjuncts: Boolean,
    private val widerConjuncts: Boolean,
) {
    /** The end of the cluster that starts at [start] in [text], looking no further than [end]. */
    fun clusterEnd(text: CharSequence, start: Int, end: Int = text.length): Int {
        val first = Character.codePointAt(text, start)
        val next = start + Character.charCount(first)
        if (next >= end) return next

        val second = Character.codePointAt(text, next)

        return when {
            first == CR -> if (second == LF) next + 1 else next
            first < LATIN_1_END && second < LATIN_1_END -> next
            else -> Cluster(text, end).after(first, next)
        }
    }

    /** Whether [codePoint] is a control, which a cluster ends after. */
    fun isControl(codePoint: Int): Boolean = table.properties(codePoint) has CONTROL

    /** Whether [codePoint] is a Prepend, which takes the cluster after it. */
    fun isPrepend(codePoint: Int): Boolean = table.properties(codePoint) has PREPEND

    /** The clusters of [text], each as its start and end. */
    fun clusters(text: CharSequence): Sequence<IntRange> {
        fun from(start: Int): IntRange? = if (start < text.length) start until clusterEnd(text, start) else null

        return generateSequence(from(0)) { from(it.last + 1) }
    }

    /** The rest of one cluster, walked as `unicode_util:gc_1` and its helpers walk it. */
    private inner class Cluster(private val text: CharSequence, private val end: Int) {
        /** The end of the cluster that starts with [codePoint], whose next code point is at [next]. */
        tailrec fun after(codePoint: Int, next: Int): Int {
            val properties = table.properties(codePoint)

            return when {
                properties has CONTROL -> next
                codePoint < LATIN_1_END && properties has PICTOGRAPHIC -> pictographic(next)
                codePoint < LATIN_1_END -> if (at(next).let { it >= 0 && it < LATIN_1_END }) next else extend(next)
                // A Prepend takes the whole cluster after it, even an ASCII character, unless that starts with a control.
                properties has PREPEND -> {
                    val prepended = at(next)

                    if (prepended < 0 || table.properties(prepended) has CONTROL) {
                        next
                    } else {
                        after(prepended, following(next))
                    }
                }
                properties has L -> hangulL(next)
                properties has V -> hangulV(next)
                properties has T -> hangulT(next)
                properties has LV -> hangulV(next)
                properties has LVT -> hangulT(next)
                properties has REGIONAL -> regional(next)
                properties has PICTOGRAPHIC -> pictographic(next)
                isConsonant(codePoint, properties) -> conjunct(next)
                else -> extend(next)
            }
        }

        /** The code point at [index], or -1 at the end. */
        private fun at(index: Int): Int = if (index < end) Character.codePointAt(text, index) else -1

        private fun following(index: Int): Int = index + Character.charCount(at(index))

        private fun properties(index: Int): Int = at(index).let { if (it < 0) 0 else table.properties(it) }

        private fun extend(start: Int): Int {
            var index = start
            while (index < end && properties(index) has EXTEND_OR_JOINER) index = following(index)
            return index
        }

        private tailrec fun hangulL(index: Int): Int {
            val properties = properties(index)

            return when {
                properties has L -> hangulL(following(index))
                properties has V || properties has LV -> hangulV(following(index))
                properties has LVT -> hangulT(following(index))
                else -> extend(index)
            }
        }

        private tailrec fun hangulV(index: Int): Int {
            val properties = properties(index)

            return when {
                properties has V -> hangulV(following(index))
                properties has T -> hangulT(following(index))
                else -> extend(index)
            }
        }

        private tailrec fun hangulT(index: Int): Int =
            if (properties(index) has T) hangulT(following(index)) else extend(index)

        private fun regional(index: Int): Int =
            if (properties(index) has REGIONAL) extend(following(index)) else extend(index)

        // After a joiner only another pictograph continues the cluster; the joiner stays, anything else ends it.
        private fun pictographic(start: Int): Int {
            var index = start

            while (index < end) {
                val properties = properties(index)

                index = when {
                    properties has JOINER -> {
                        val joined = following(index)
                        if (properties(joined) has PICTOGRAPHIC) following(joined) else return joined
                    }
                    properties has EXTEND -> following(index)
                    else -> return index
                }
            }

            return index
        }

        // A consonant, then viramas and marks, then a consonant, joins; a consonant with no virama before it ends it.
        private fun conjunct(start: Int): Int {
            var index = start
            var linked = false

            while (index < end) {
                val codePoint = at(index)
                val properties = table.properties(codePoint)

                when {
                    isLinker(properties) -> linked = true
                    properties has EXTEND_OR_JOINER -> Unit
                    linked && isConsonant(codePoint, properties) -> linked = false
                    else -> return index
                }

                index = following(index)
            }

            return index
        }
    }

    private fun isConsonant(codePoint: Int, properties: Int): Boolean =
        conjuncts &&
            (properties has CONSONANT ||
                widerConjuncts && (properties has INDEPENDENT_VOWEL || codePoint in WIDER_CONSONANTS))

    private fun isLinker(properties: Int): Boolean =
        conjuncts && (properties has VIRAMA || widerConjuncts && properties has INVISIBLE_STACKER)

    /** The properties `gc/1` tests, per code point, read from a `graphemes-<version>.tsv` resource. */
    private class Table(private val firsts: IntArray, private val lasts: IntArray, private val properties: IntArray) {
        fun properties(codePoint: Int): Int {
            var low = 0
            var high = firsts.size - 1

            while (low <= high) {
                val middle = (low + high) ushr 1

                when {
                    codePoint < firsts[middle] -> high = middle - 1
                    codePoint > lasts[middle] -> low = middle + 1
                    else -> return properties[middle]
                }
            }

            return 0
        }

        companion object {
            fun load(version: String): Table {
                val resource = "/org/elixir_lang/unicode_util/graphemes-$version.tsv"
                val rows = Table::class.java.getResourceAsStream(resource)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { reader ->
                        reader.readLines().filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split('\t') }
                    }
                    ?: error("missing resource $resource")

                return Table(
                    firsts = IntArray(rows.size) { rows[it][0].toInt(16) },
                    lasts = IntArray(rows.size) { rows[it][1].toInt(16) },
                    properties = IntArray(rows.size) { index ->
                        rows[index][2].fold(0) { flags, letter ->
                            flags or (LETTERS[letter] ?: error("unknown property $letter in $resource"))
                        }
                    },
                )
            }
        }
    }

    companion object {
        private const val CR = '\r'.code
        private const val LF = '\n'.code
        private const val LATIN_1_END = 256

        private const val CONTROL = 1 shl 0
        private const val PREPEND = 1 shl 1
        private const val L = 1 shl 2
        private const val V = 1 shl 3
        private const val T = 1 shl 4
        private const val LV = 1 shl 5
        private const val LVT = 1 shl 6
        private const val REGIONAL = 1 shl 7
        private const val PICTOGRAPHIC = 1 shl 8
        private const val EXTEND = 1 shl 9
        private const val JOINER = 1 shl 10
        private const val CONSONANT = 1 shl 11
        private const val INDEPENDENT_VOWEL = 1 shl 12
        private const val VIRAMA = 1 shl 13
        private const val INVISIBLE_STACKER = 1 shl 14
        private const val EXTEND_OR_JOINER = EXTEND or JOINER

        private val LETTERS = mapOf(
            'c' to CONTROL,
            'p' to PREPEND,
            'L' to L,
            'V' to V,
            'T' to T,
            'v' to LV,
            't' to LVT,
            'r' to REGIONAL,
            'x' to PICTOGRAPHIC,
            'e' to EXTEND,
            'z' to JOINER,
            'k' to CONSONANT,
            'i' to INDEPENDENT_VOWEL,
            'l' to VIRAMA,
            's' to INVISIBLE_STACKER,
        )

        private val WIDER_CONSONANTS = 0x1B0B..0x1B0C

        private val tables = ConcurrentHashMap<String, Table>()
        private val segmenters = ConcurrentHashMap<Triple<String, Boolean, Boolean>, Graphemes>()

        /** The segmentation of [languageLevel]'s OTP; an OTP before 24 segments as 24 does. */
        fun of(languageLevel: ElixirLanguageLevel): Graphemes {
            val widerConjuncts = WIDER_INDIC_CONJUNCT_GRAPHEME_CLUSTERS.isSufficient(languageLevel)
            val conjuncts = INDIC_CONJUNCT_GRAPHEME_CLUSTERS.isSufficient(languageLevel)
            val unicode = when {
                widerConjuncts -> "17.0"
                conjuncts -> "16.0"
                UNICODE_15_GRAPHEME_CLUSTERS.isSufficient(languageLevel) -> "15.0"
                UNICODE_14_GRAPHEME_CLUSTERS.isSufficient(languageLevel) -> "14.0"
                else -> "13.0"
            }

            return segmenters.computeIfAbsent(Triple(unicode, conjuncts, widerConjuncts)) { key ->
                Graphemes(tables.computeIfAbsent(key.first, Table::load), key.second, key.third)
            }
        }

        private infix fun Int.has(property: Int): Boolean = this and property != 0
    }
}
