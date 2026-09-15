package org.elixir_lang.annotator.unicode_security

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageFeature.*
import java.text.Normalizer
import java.util.BitSet

/**
 * What Elixir's tokenizer rejects for Unicode security reasons, as it did in the release a [ElixirLanguageLevel] stands for:
 * bidirectional formatting and line break characters in comments and quoted text, and identifiers with a restricted
 * code point or a mix of scripts.
 */
internal object UnicodeSecurityCheck {
    /** [tooltip] is HTML carrying the rest of Elixir's error, where the plugin can reproduce it. */
    class Problem(val range: TextRange, val message: String, val tooltip: String? = null)

    fun inComment(text: CharSequence, languageLevel: ElixirLanguageLevel): List<Problem> =
        characters(text) { character ->
            when {
                isBidi(character) && BIDI_CHARACTERS_REJECTED.isSufficient(languageLevel) ->
                    "invalid bidirectional formatting character in comment: ${escaped(character)}"

                isLineBreak(character) && LINE_BREAKS_REJECTED_IN_COMMENTS.isSufficient(languageLevel) ->
                    "invalid line break character in comment: ${escaped(character)}"

                else -> null
            }
        }

    fun inQuoted(text: CharSequence, languageLevel: ElixirLanguageLevel): List<Problem> =
        characters(text) { character ->
            when {
                isBidi(character) && BIDI_CHARACTERS_REJECTED.isSufficient(languageLevel) ->
                    inString("invalid bidirectional formatting character", character)
                isLineBreak(character) && LINE_BREAKS_REJECTED_IN_QUOTED_TEXT.isSufficient(languageLevel) ->
                    inString("invalid line break character", character)
                else -> null
            }
        }

    fun hasBidiOrLineBreak(text: CharSequence): Boolean = text.any { isBidi(it) || isLineBreak(it) }

    fun inIdentifier(text: CharSequence, languageLevel: ElixirLanguageLevel): Problem? {
        val table = IdentifierTable.forLanguageLevel(languageLevel) ?: return null
        val codePoints = mutableListOf<Int>()
        var scriptSet: BitSet? = null
        var offset = 0

        while (offset < text.length) {
            val original = Character.codePointAt(text, offset)
            var codePoint = original
            val first = offset == 0

            when {
                isAsciiLetter(codePoint) -> scriptSet = intersect(scriptSet, table.latin)
                codePoint == '_'.code || codePoint in '0'.code..'9'.code || codePoint == '@'.code -> Unit
                codePoint == '?'.code || codePoint == '!'.code -> {
                    codePoints.add(codePoint)
                    offset++
                    break
                }
                codePoint <= 127 -> break
                else -> {
                    val accepted = table.classesOf(codePoint) and
                        (if (first) IdentifierTable.UPPER or IdentifierTable.START else IdentifierTable.UPPER or IdentifierTable.START or IdentifierTable.CONTINUE)

                    if (accepted == 0) {
                        if (codePoint != MICRO_SIGN) {
                            // As Elixir shows it: tokenized so far, so a micro sign is already a mu.
                            val identifier = String(codePoints.toIntArray(), 0, codePoints.size) + String(Character.toChars(codePoint))
                            val range = TextRange(offset, offset + Character.charCount(codePoint))

                            return restricted(identifier, range, first, languageLevel)
                        }

                        codePoint = GREEK_SMALL_LETTER_MU
                    }

                    scriptSet = intersect(scriptSet, table.scriptSetOf(codePoint))
                }
            }

            codePoints.add(codePoint)
            offset += Character.charCount(original)
        }

        if (scriptSet == null || !scriptSet.isEmpty) return null

        val normalized = Normalizer
            .normalize(String(codePoints.toIntArray(), 0, codePoints.size), Normalizer.Form.NFC)
        val normalizedCodePoints = normalized.codePoints().toArray()
        val accepted = if (MIXED_SCRIPT_BY_UNDERSCORE_CHUNK.isSufficient(languageLevel)) {
            chunksSingle(normalizedCodePoints, table)
        } else {
            highlyRestrictive(normalizedCodePoints, table)
        }

        if (accepted) return null

        return mixedScript(normalized, normalizedCodePoints, table, languageLevel, TextRange(0, offset))
    }

    /** [identifier] ends with the code point Elixir rejects. */
    private fun restricted(identifier: String, range: TextRange, first: Boolean, languageLevel: ElixirLanguageLevel): Problem {
        val codePoint = identifier.codePointBefore(identifier.length)
        val message = "unexpected token: \"${String(Character.toChars(codePoint))}\" (code point U+%04X)".format(codePoint)
        // Elixir hints only after an identifier's first code point, trying the compatibility form of the identifier up
        // to that code point first; its later tries need confusable data the plugin does not ship.
        val compatible = Normalizer.normalize(identifier, Normalizer.Form.NFKC)
            .takeIf { !first && it != identifier && inIdentifier(it, languageLevel) == null }

        val tooltip = if (compatible != null) {
            tooltip(
                message,
                HtmlChunk.p().addText("Elixir expects unquoted Unicode atoms, variables, and calls to use allowed codepoints and to be in NFC form."),
                HtmlChunk.p().addText("Got: \"$identifier\" (code points${hexadecimal(identifier)})"),
                HtmlChunk.p().addText(
                    "Hint: You could write the above in a compatible format that is accepted by Elixir: " +
                        "\"$compatible\" (code points${hexadecimal(compatible)})"
                ),
                links("r4-equivalent-normalized-identifiers" to "R4. Equivalent Normalized Identifiers"),
            )
        } else {
            tooltip(
                message,
                HtmlChunk.p().addText(
                    "Elixir does not allow this code point in unquoted atoms, variables, and calls: Unicode's identifier " +
                        "rules exclude it, or its security profile restricts it."
                ),
                links(
                    "r1-default-identifiers" to "R1. Default Identifiers",
                    "c1-general-security-profile-for-identifiers" to "C1. General Security Profile for Identifiers",
                ),
            )
        }

        return Problem(range, message, tooltip)
    }

    private fun mixedScript(
        identifier: String,
        codePoints: IntArray,
        table: IdentifierTable,
        languageLevel: ElixirLanguageLevel,
        range: TextRange,
    ): Problem {
        val scripts = codePoints.map { codePoint -> codePoint to chunkScriptSetOf(codePoint, table)?.let(table::scriptNames) }
        val message = "invalid mixed-script identifier found: $identifier" +
            (culprits(codePoints, table, languageLevel)?.let { " ($it)" } ?: "")
        val guidance = if (MIXED_SCRIPT_GUIDANCE_REQUIRES_UNDERSCORES.isSufficient(languageLevel)) {
            "Characters in identifiers from different scripts must be separated by underscore (_)."
        } else {
            "All characters in the identifier should resolve to a single script, or use a highly restrictive set of scripts."
        }
        val rows = scripts.flatMapIndexed { index, (codePoint, names) ->
            val row = HtmlChunk.text(character(codePoint) + (names?.joinToString(", ", prefix = " ") ?: ""))
            if (index == 0) listOf(row) else listOf(HtmlChunk.br(), row)
        }

        return Problem(
            range,
            message,
            tooltip(
                message,
                HtmlChunk.p().addText(
                    "Mixed-script identifiers are not supported for security reasons. '$identifier' is made of the following scripts:"
                ),
                HtmlChunk.p().children(rows),
                HtmlChunk.p().addText(guidance),
                links("c3-mixed-script-detection" to "C3. Mixed Script Detection"),
            )
        )
    }

    /**
     * The characters that keep [codePoints] from resolving, judged as Elixir's check judges them: per underscore-separated
     * chunk from 1.18, and before that across the whole identifier, where Latin may also pair with a highly restrictive
     * script.
     */
    private fun culprits(codePoints: IntArray, table: IdentifierTable, languageLevel: ElixirLanguageLevel): String? {
        val byChunk = MIXED_SCRIPT_BY_UNDERSCORE_CHUNK.isSufficient(languageLevel)
        val chunks = if (byChunk) chunks(codePoints) else listOf(codePoints)
        val candidates = if (byChunk) table.singleScripts else table.singleScripts + table.highlyRestrictive

        return chunks
            .mapNotNull { chunk ->
                chunkCulprits(chunk, candidates, table)?.let { if (chunks.size > 1) "in ${String(chunk, 0, chunk.size)}, $it" else it }
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("; ")
    }

    /** The scripts most characters share are taken as meant, the first character's breaking a tie. */
    private fun chunkCulprits(chunk: IntArray, candidates: List<BitSet>, table: IdentifierTable): String? {
        val scripted = chunk.asList().mapNotNull { codePoint -> chunkScriptSetOf(codePoint, table)?.let { codePoint to it } }
        val meant = candidates.maxWithOrNull(
            compareBy(
                { candidate -> scripted.count { (_, scriptSet) -> scriptSet.intersects(candidate) } },
                { candidate -> scripted.firstOrNull()?.second?.intersects(candidate) == true },
            )
        ) ?: return null
        val others = scripted
            .filterNot { (_, scriptSet) -> scriptSet.intersects(meant) }
            .groupBy({ (_, scriptSet) -> table.scriptNames(scriptSet) }, { (codePoint, _) -> codePoint })
            .map { (names, members) ->
                val distinct = members.distinct()
                "${distinct.joinToString(", ", transform = ::character)} ${if (distinct.size == 1) "is" else "are"} ${either(names)}"
            }

        return if (others.isEmpty()) null else (others + "the rest is ${table.scriptNames(meant).joinToString(" and ")}").joinToString("; ")
    }

    private fun chunks(codePoints: IntArray): List<IntArray> {
        val chunks = mutableListOf<IntArray>()
        var start = 0

        for (index in codePoints.indices) {
            if (codePoints[index] == '_'.code) {
                if (index > start) chunks.add(codePoints.copyOfRange(start, index))
                start = index + 1
            }
        }
        if (start < codePoints.size) chunks.add(codePoints.copyOfRange(start, codePoints.size))

        return chunks
    }

    private fun tooltip(message: String, vararg paragraphs: HtmlChunk): String =
        HtmlBuilder()
            .append(HtmlChunk.p().addText(message))
            .apply { paragraphs.forEach { append(it) } }
            .wrapWithHtmlBody()
            .toString()

    private fun links(vararg sections: Pair<String, String>): HtmlChunk =
        HtmlChunk.p().children(
            sections.flatMapIndexed { index, (anchor, title) ->
                val link = HtmlChunk.link("$UNICODE_SYNTAX#$anchor", "Unicode syntax: $title")
                if (index == 0) listOf(link) else listOf(HtmlChunk.br(), link)
            }
        )

    private fun character(codePoint: Int) = "U+%04X %s".format(codePoint, String(Character.toChars(codePoint)))

    private fun either(names: List<String>) =
        if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " or " + names.last()

    private fun hexadecimal(text: String) = text.codePoints().toArray().joinToString("") { " 0x%05X".format(it) }

    private const val UNICODE_SYNTAX = "https://hexdocs.pm/elixir/unicode-syntax.html"
    private const val MICRO_SIGN = 0x00B5
    private const val GREEK_SMALL_LETTER_MU = 0x03BC

    private fun isAsciiLetter(codePoint: Int) = codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code

    private fun isBidi(character: Char) = character.code in 0x202A..0x202E || character.code in 0x2066..0x2069

    private fun isLineBreak(character: Char) =
        when (character.code) {
            0x000B, 0x000C, 0x0085, 0x2028, 0x2029 -> true
            else -> false
        }

    private fun escaped(character: Char) = "\\u%04X".format(character.code)

    private fun inString(prefix: String, character: Char) =
        "$prefix in string: ${escaped(character)}. If you want to use such character, use it in its escaped ${escaped(character)} form instead"

    private inline fun characters(text: CharSequence, message: (Char) -> String?): List<Problem> =
        text.indices.mapNotNull { index -> message(text[index])?.let { Problem(TextRange(index, index + 1), it) } }

    /** `null` is the set of every script. */
    private fun intersect(left: BitSet?, right: BitSet?): BitSet? =
        when {
            left == null -> right
            right == null -> left
            else -> (left.clone() as BitSet).apply { and(right) }
        }

    /** Like the tokenizer's `codepoint_to_scriptset`: a code point it does not tokenize belongs to every script. */
    private fun chunkScriptSetOf(codePoint: Int, table: IdentifierTable): BitSet? =
        if (isAsciiLetter(codePoint)) table.latin else table.scriptSetOf(codePoint)

    private fun chunksSingle(codePoints: IntArray, table: IdentifierTable): Boolean {
        var chunk: BitSet? = null

        for (codePoint in codePoints) {
            if (codePoint == '_'.code) {
                if (chunk != null && chunk.isEmpty) return false
                chunk = null
            } else {
                chunk = intersect(chunk, chunkScriptSetOf(codePoint, table))
            }
        }

        return chunk == null || !chunk.isEmpty
    }

    private fun highlyRestrictive(codePoints: IntArray, table: IdentifierTable): Boolean =
        table.highlyRestrictive.any { restrictive ->
            codePoints.all { codePoint -> chunkScriptSetOf(codePoint, table)?.intersects(restrictive) ?: true }
        }
}
