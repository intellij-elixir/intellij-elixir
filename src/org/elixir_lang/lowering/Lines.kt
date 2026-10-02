package org.elixir_lang.lowering

import com.intellij.openapi.util.TextRange
import org.elixir_lang.unicode_util.Graphemes

/**
 * Offsets into a file's text as [Meta.Position]s, counted as one Elixir release's tokenizer counts them.
 *
 * @param starts the offset each line starts at
 * @param quotedTexts quoted text whose columns count [graphemes]' clusters rather than code points
 * @param zeroWidthRanges ranges the tokenizer did not advance the column over
 */
internal class Lines(
    private val text: CharSequence,
    private val graphemes: Graphemes,
    private val starts: IntArray,
    quotedTexts: List<QuotedText> = emptyList(),
    zeroWidthRanges: List<TextRange> = emptyList(),
) {
    /**
     * @param uncountedNewlines offsets of newlines the tokenizer consumed without starting a line, which advance the
     *   column like any other character
     */
    constructor(
        text: CharSequence,
        graphemes: Graphemes,
        uncountedNewlines: Collection<Int> = emptyList(),
        quotedTexts: List<QuotedText> = emptyList(),
        zeroWidthRanges: List<TextRange> = emptyList(),
    ) : this(text, graphemes, starts(text, uncountedNewlines), quotedTexts, zeroWidthRanges)

    companion object {
        /** The offset each line starts at, skipping [uncountedNewlines]. */
        fun starts(text: CharSequence, uncountedNewlines: Collection<Int>): IntArray {
            val uncounted = uncountedNewlines.toSet()
            val starts = mutableListOf(0)

            for (offset in text.indices) {
                if (text[offset] == '\n' && offset !in uncounted) starts.add(offset + 1)
            }

            return starts.toIntArray()
        }
    }

    /**
     * [range] of a quoted literal's text, whose escaped [terminator] (`null` when it has none) and, if it
     * [interpolates], escaped `#{` are one column per character.
     */
    class QuotedText(val range: TextRange, val terminator: String?, val interpolates: Boolean)

    private val quotedTexts = quotedTexts.sortedBy { it.range.startOffset }
    private val zeroWidthRanges = zeroWidthRanges.sortedBy { it.startOffset }

    /** Each line's [widths], counted the first time a position on it is asked for. */
    private val lineWidths = arrayOfNulls<IntArray>(starts.size)

    fun position(offset: Int): Meta.Position {
        val line = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
        val start = starts[line]
        // An offset inside a cluster or an escape counts as the tokenizer would if the text stopped there.
        val width = widths(line)[offset - start].takeIf { it >= 0 } ?: width(start, offset)

        return Meta.Position(line + 1, width + 1)
    }

    /** [line]'s width up to each of its offsets, and -1 at an offset no column starts at. */
    private fun widths(line: Int): IntArray =
        lineWidths[line] ?: run {
            val start = starts[line]
            val end = starts.getOrElse(line + 1) { text.length }
            val widths = IntArray(end - start + 1) { -1 }
            widths[end - start] = width(start, end) { offset, width -> widths[offset - start] = width }
            lineWidths[line] = widths
            widths
        }

    /** The columns from [start] to [end], telling [counted] the width up to each offset a column starts at. */
    private fun width(start: Int, end: Int, counted: (offset: Int, width: Int) -> Unit = { _, _ -> }): Int {
        var width = 0
        var offset = start

        while (offset < end) {
            counted(offset, width)
            val zeroWidth = zeroWidthRanges.containing(offset) { it }
            val quoted = quotedTexts.containing(offset) { it.range }

            when {
                zeroWidth != null -> offset = minOf(zeroWidth.endOffset, end)
                quoted != null -> {
                    val segmentEnd = minOf(quoted.range.endOffset, end, nextZeroWidth(offset, end))
                    width += clusters(offset, segmentEnd, quoted) { at, clusters -> counted(at, width + clusters) }
                    offset = segmentEnd
                }
                else -> {
                    width++
                    offset += Character.charCount(Character.codePointAt(text, offset))
                }
            }
        }

        return width
    }

    private fun nextZeroWidth(offset: Int, end: Int): Int =
        zeroWidthRanges.firstOrNull { it.startOffset > offset }?.startOffset ?: end

    /** The element, of ones sorted by start whose ranges do not overlap, whose range holds [offset]. */
    private fun <T> List<T>.containing(offset: Int, range: (T) -> TextRange): T? =
        binarySearch { range(it).startOffset.compareTo(offset) }
            .let { if (it >= 0) it else -it - 2 }
            .let { getOrNull(it) }
            ?.takeIf { offset < range(it).endOffset }

    /**
     * The columns from [start] to [end] in [quoted] as `elixir_interpolation` counts them: a cluster each, with an
     * escape its `\` and then the one cluster that follows, whatever it holds.
     */
    private fun clusters(start: Int, end: Int, quoted: QuotedText, counted: (offset: Int, width: Int) -> Unit): Int {
        var width = 0
        var offset = start

        while (offset < end) {
            counted(offset, width)
            val escaped = offset + 1

            if (text[offset] == '\\' && escaped < end) {
                val fixed = fixedEscapeWidth(escaped, quoted)

                if (fixed != null) {
                    width += fixed
                    offset += fixed
                } else {
                    width += 2
                    offset = graphemes.clusterEnd(text, escaped, end)
                }
            } else {
                width++
                offset = graphemes.clusterEnd(text, offset, end)
            }
        }

        return width
    }

    private fun fixedEscapeWidth(escaped: Int, quoted: QuotedText): Int? {
        val terminator = quoted.terminator

        return when {
            terminator != null && text.startsWith(terminator, escaped) -> 1 + terminator.length
            quoted.interpolates && text.startsWith("#{", escaped) -> 3
            else -> null
        }
    }
}
