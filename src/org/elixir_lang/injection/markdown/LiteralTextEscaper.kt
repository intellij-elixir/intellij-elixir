package org.elixir_lang.injection.markdown

import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.text.CharSequenceSubSequence
import org.elixir_lang.injection.PsiLanguageInjectionHost.isDocumentation
import org.elixir_lang.injection.sigilIsTemplate
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.lowering.Piece
import org.elixir_lang.lowering.sigilEscape
import org.elixir_lang.psi.Body
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.HeredocLiteral
import org.elixir_lang.psi.Parent
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.SigilHeredocLiteral
import org.elixir_lang.psi.SigilLine
import org.elixir_lang.psi.decodedTextStart
import java.util.concurrent.ConcurrentHashMap

/**
 * @param decodesTemplates whether a template sigil's text is decoded, its escapes and its heredoc indentation. The
 * platform's fragment of a template is not (see [sigilIsTemplate]); a caller that reads the decoded text itself is.
 */
class LiteralTextEscaper(parent: Parent, private val decodesTemplates: Boolean = false) :
    com.intellij.psi.LiteralTextEscaper<Parent>(parent) {
    /**
     * The range the paired `offsets` (or its absence, when `null`) was decoded for.
     *
     * Read locks are shared, not exclusive, so two threads can call [decode]/[getOffsetInHost] on the
     * same escaper instance concurrently for two different ranges. Bundling the range and its offsets
     * into one immutable pair behind a single `@Volatile` reference means a reader always sees a range
     * and its own matching offsets together, never one thread's range paired with another's table.
     */
    private class Decoded(val range: TextRange, val offsets: IntArray?)

    @Volatile
    private var decoded: Decoded? = null

    override fun decode(rangeInsideHost: TextRange, outChars: StringBuilder): Boolean {
        val contentRanges = markdownContentRanges(rangeInsideHost)

        if (contentRanges.isEmpty()) {
            val host = myHost

            if (host is Sigil) {
                decoded = Decoded(rangeInsideHost, decodeSigil(host, rangeInsideHost, outChars))
            } else {
                decoded = Decoded(rangeInsideHost, null)
                outChars.append(rangeInsideHost.substring(myHost.text))
            }

            return true
        }

        val hostText = myHost.text
        val offsets = IntArray(rangeInsideHost.length + 1)
        offsets.fill(rangeInsideHost.endOffset)
        var offsetInDecoded = 0

        fun copy(range: TextRange) {
            for (offsetInRange in 0 until range.length) {
                offsets[offsetInDecoded++] = range.startOffset + offsetInRange
            }

            outChars.append(hostText, range.startOffset, range.endOffset)
        }

        // A character typed at the range's start widens it, so the range then starts before its first content. It is
        // kept as typed: the injected document holds the host's text there, and the parsed text has to agree
        copy(TextRange(rangeInsideHost.startOffset, contentRanges.first().startOffset))
        contentRanges.forEach(::copy)

        decoded = Decoded(rangeInsideHost, offsets)

        return true
    }

    override fun getOffsetInHost(offsetInDecoded: Int, rangeInsideHost: TextRange): Int {
        // The contract does not guarantee decode() was last called for this same range - answering from
        // a stale offsets table built for a different range would silently mismatch, so recompute rather
        // than assume.
        var current = decoded

        if (current == null || current.range != rangeInsideHost) {
            decode(rangeInsideHost, StringBuilder())
            current = decoded
        }

        val offsets = current?.offsets ?: return rangeInsideHost.startOffset + offsetInDecoded

        return if (offsetInDecoded in offsets.indices) offsets[offsetInDecoded] else -1
    }

    override fun getRelevantTextRange(): TextRange =
        when (val host = myHost) {
            is HeredocLiteral -> markdownRangeInHost(host) ?: TextRange.from(1, 0)
            is ElixirLine -> host.lineBody?.textRangeInParent ?: TextRange.from(1, 0)
            else -> super.getRelevantTextRange()
        }

    override fun isOneLine(): Boolean = false

    /**
     * [rangeInsideHost] of [sigil] as Elixir's tokenizer keeps it, appended to [outChars], and each decoded offset's
     * offset in the host, or `null` where no part of the range decodes to other text.
     */
    private fun decodeSigil(sigil: Sigil, rangeInsideHost: TextRange, outChars: StringBuilder): IntArray? {
        val hostStart = sigil.textRange.startOffset
        // The file's text is cached, where the sigil's own would be built afresh for each of its lines.
        val hostText = CharSequenceSubSequence(sigil.containingFile.node.chars, hostStart, hostStart + sigil.textLength)
        val decodesNothing = !decodesTemplates && sigilIsTemplate(sigil.sigilName())
        val parts = if (decodesNothing) emptyList() else partsIn(decodedParts(sigil), rangeInsideHost)

        if (parts.isEmpty()) {
            outChars.append(hostText, rangeInsideHost.startOffset, rangeInsideHost.endOffset)

            return null
        }

        val offsets = IntArray(rangeInsideHost.length + 1 - parts.sumOf { (range, text) -> range.length - text.length })
        var offsetInDecoded = 0
        var offset = rangeInsideHost.startOffset

        fun copyTo(end: Int) {
            outChars.append(hostText, offset, end)
            while (offset < end) offsets[offsetInDecoded++] = offset++
        }

        for ((range, text) in parts) {
            copyTo(range.startOffset)
            repeat(text.length) { offsets[offsetInDecoded++] = range.startOffset }
            outChars.append(text)
            offset = range.endOffset
        }

        copyTo(rangeInsideHost.endOffset)
        offsets[offsetInDecoded] = rangeInsideHost.endOffset

        return offsets
    }

    /**
     * Each part of [sigil]'s text that decodes to other text, by its range in the host, in order: an escape in its body,
     * and the indentation of a heredoc line, which decodes to none. A heredoc is decoded a line at a time, so they are
     * collected once per language level, until the file changes.
     */
    private fun decodedParts(sigil: Sigil): List<Pair<TextRange, String>> =
        CachedValuesManager.getCachedValue(sigil) {
            CachedValueProvider.Result.create(ConcurrentHashMap<ElixirLanguageLevel, List<Pair<TextRange, String>>>(), sigil)
        }.computeIfAbsent(ElixirLanguageLevelResolver.languageLevelFor(sigil)) { languageLevel ->
            val hostStart = sigil.textRange.startOffset

            buildList {
                fun addEscapes(body: Body) {
                    body.node.getChildren(null).mapNotNullTo(this) { node ->
                        sigilEscape(sigil, Piece(node.elementType, node.text, node), languageLevel)
                            ?.takeIf { it != node.text }
                            ?.let { node.textRange.shiftLeft(hostStart) to it }
                    }
                }

                when (sigil) {
                    is SigilLine -> addEscapes(sigil.body)
                    is SigilHeredocLiteral -> {
                        val indentation = sigil.heredocPrefix.textLength

                        // A line's indentation comes before its body's escapes, so the parts stay in host order
                        for (line in sigil.heredocLineList) {
                            val textStart = line.decodedTextStart(indentation)

                            if (textStart > 0) add(TextRange.from(line.textRangeInParent.startOffset, textStart) to "")
                            addEscapes(line.body)
                        }
                    }
                }
            }
        }

    /**
     * The [parts] [rangeInsideHost] covers: an escape wholly inside it, and an indentation, which decodes to nothing, as
     * far as the range covers it.
     */
    private fun partsIn(parts: List<Pair<TextRange, String>>, rangeInsideHost: TextRange): List<Pair<TextRange, String>> {
        // The comparator never answers 0, so the search always gives the insertion point: the first part ending past the start
        val first = -parts.binarySearch { (range, _) -> if (range.endOffset <= rangeInsideHost.startOffset) -1 else 1 } - 1
        val covered = ArrayList<Pair<TextRange, String>>()

        for (part in parts.subList(first, parts.size)) {
            val (range, text) = part

            if (range.startOffset >= rangeInsideHost.endOffset) break

            when {
                text.isEmpty() -> range.intersection(rangeInsideHost)?.let { covered.add(it to text) }
                rangeInsideHost.contains(range) -> covered.add(part)
            }
        }

        return covered
    }

    /**
     * The Markdown [rangeInsideHost] covers, clipped to it; empty when it covers none, which is how the
     * Elixir injected into a documentation code block is recognised.
     *
     * Clipping rather than matching the range against the registered places matters because
     * `InjectionRegistrarImpl.reparse` hands back a range grown from the shred's own marker, which is
     * greedy at both ends, and against a host in the uncommitted tree. A grown Markdown range still covers
     * its own content, and a code block's indent and prompt are not host ranges at all, so a grown Elixir
     * range still covers none.
     */
    private fun markdownContentRanges(rangeInsideHost: TextRange): List<TextRange> {
        val host = myHost as? HeredocLiteral ?: return emptyList()

        if (!isDocumentation(host)) return emptyList()

        return markdownInjection(host)
            .contentRanges
            .mapNotNull { contentRange -> contentRange.intersection(rangeInsideHost) }
            .filterNot { it.isEmpty }
    }
}
