package org.elixir_lang.annotator.unicode_security

import org.elixir_lang.language_level.ElixirLanguageLevel
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

/**
 * The code points above 127 that Elixir's tokenizer accepts in an identifier, for one Unicode version, read from the
 * `identifiers-<version>.tsv` resource that `generate.exs` builds from that release's own Unicode files.
 */
internal class IdentifierTable private constructor(
    private val firsts: IntArray,
    private val lasts: IntArray,
    private val classes: ByteArray,
    /** `null` is the set of every script, which intersects every other. */
    private val scriptSets: Array<BitSet?>,
    private val scripts: List<String>,
) {
    val latin: BitSet = scriptSet(scripts, "Latin")
    val highlyRestrictive: List<BitSet> = listOf("Japanese", "Han with Bopomofo", "Korean").map { script ->
        (latin.clone() as BitSet).apply { or(scriptSet(scripts, script)) }
    }
    val singleScripts: List<BitSet> = scripts.indices.map { index -> BitSet().apply { set(index) } }

    fun classesOf(codePoint: Int): Int = index(codePoint).let { if (it < 0) 0 else classes[it].toInt() }

    /** `null`, every script, for a code point not in the table too. */
    fun scriptSetOf(codePoint: Int): BitSet? = index(codePoint).let { if (it < 0) null else scriptSets[it] }

    /** In Elixir's order: Latin, then the others alphabetically. */
    fun scriptNames(scriptSet: BitSet): List<String> = scriptSet.stream().mapToObj { scripts[it] }.toList()

    private fun index(codePoint: Int): Int {
        var low = 0
        var high = firsts.size - 1

        while (low <= high) {
            val middle = (low + high) ushr 1

            when {
                codePoint < firsts[middle] -> high = middle - 1
                codePoint > lasts[middle] -> low = middle + 1
                else -> return middle
            }
        }

        return -1
    }

    companion object {
        const val UPPER = 1
        const val START = 2
        const val CONTINUE = 4

        private val tables = ConcurrentHashMap<String, IdentifierTable>()

        private fun forUnicode(version: String): IdentifierTable = tables.computeIfAbsent(version, ::load)

        /**
         * The table of the newest Elixir minor at or before [languageLevel]'s, or `null` before the first minor that
         * checks identifiers. A minor newer than [IDENTIFIER_TABLE_UNICODE_VERSIONS] checks with the newest table.
         */
        fun forLanguageLevel(languageLevel: ElixirLanguageLevel): IdentifierTable? {
            val release = languageLevel.elixir.major to languageLevel.elixir.minor

            return IDENTIFIER_TABLE_UNICODE_VERSIONS
                .lastOrNull { (minor, _) -> compareValuesBy(minor, release, { it.first }, { it.second }) <= 0 }
                ?.let { (_, unicode) -> forUnicode(unicode) }
        }

        private fun scriptSet(scripts: List<String>, script: String): BitSet =
            BitSet().apply { set(scripts.indexOf(script).also { check(it >= 0) { "no $script script" } }) }

        private fun load(version: String): IdentifierTable {
            val resource = "/org/elixir_lang/annotator/unicode_security/identifiers-$version.tsv"
            val lines = IdentifierTable::class.java.getResourceAsStream(resource)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { reader -> reader.readLines().filter { it.isNotEmpty() && !it.startsWith("#") } }
                ?: error("missing resource $resource")
            val scripts = lines.first().split('\t').also { check(it.first() == "scripts") }.drop(1)
            val rows = lines.drop(1).map { it.split('\t') }

            return IdentifierTable(
                firsts = IntArray(rows.size) { rows[it][0].toInt(16) },
                lasts = IntArray(rows.size) { rows[it][1].toInt(16) },
                classes = ByteArray(rows.size) { index ->
                    rows[index][2].fold(0) { flags, letter ->
                        flags or when (letter) {
                            'u' -> UPPER
                            's' -> START
                            'c' -> CONTINUE
                            else -> error("unknown class $letter in $resource")
                        }
                    }.toByte()
                },
                scriptSets = Array(rows.size) { index ->
                    rows[index][3].takeUnless { it == "*" }?.let { indexes ->
                        BitSet().apply { indexes.split(',').forEach { set(it.toInt()) } }
                    }
                },
                scripts = scripts,
            )
        }
    }
}
