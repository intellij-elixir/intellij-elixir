package org.elixir_lang.injection.markdown

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.text.CharSequenceSubSequence
import org.elixir_lang.injection.PsiLanguageInjectionHost.isDocumentation
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.lowering.Piece
import org.elixir_lang.lowering.sigilEscape
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.HeredocLiteral
import org.elixir_lang.psi.Parent
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.SigilHeredocLiteral
import org.elixir_lang.psi.SigilLine
import java.util.concurrent.ConcurrentHashMap

class LiteralTextEscaper(parent: Parent) : com.intellij.psi.LiteralTextEscaper<Parent>(parent) {
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

        for (contentRange in contentRanges) {
            for (offsetInContent in 0 until contentRange.length) {
                offsets[offsetInDecoded++] = contentRange.startOffset + offsetInContent
            }

            outChars.append(contentRange.substring(hostText))
        }

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
     * offset in the host, or `null` where no escape in the range changes.
     */
    private fun decodeSigil(sigil: Sigil, rangeInsideHost: TextRange, outChars: StringBuilder): IntArray? {
        val hostStart = sigil.textRange.startOffset
        // The file's text is cached, where the sigil's own would be built afresh for each of its lines.
        val hostText = CharSequenceSubSequence(sigil.containingFile.node.chars, hostStart, hostStart + sigil.textLength)
        val escapes = escapesIn(escapes(sigil), rangeInsideHost)

        if (escapes.isEmpty()) {
            outChars.append(hostText, rangeInsideHost.startOffset, rangeInsideHost.endOffset)

            return null
        }

        val offsets = ArrayList<Int>(rangeInsideHost.length + 1)
        var offset = rangeInsideHost.startOffset

        for ((range, text) in escapes) {
            while (offset < range.startOffset) {
                offsets.add(offset)
                outChars.append(hostText[offset++])
            }

            repeat(text.length) { offsets.add(range.startOffset) }
            outChars.append(text)
            offset = range.endOffset
        }

        while (offset < rangeInsideHost.endOffset) {
            offsets.add(offset)
            outChars.append(hostText[offset++])
        }

        offsets.add(rangeInsideHost.endOffset)

        return offsets.toIntArray()
    }

    /**
     * Each escape in [sigil]'s body that decodes to other text, by its range in the host, in order. A heredoc is decoded
     * a line at a time, so they are collected once per language level, until the file changes.
     */
    private fun escapes(sigil: Sigil): List<Pair<TextRange, String>> =
        CachedValuesManager.getCachedValue(sigil) {
            CachedValueProvider.Result.create(ConcurrentHashMap<ElixirLanguageLevel, List<Pair<TextRange, String>>>(), sigil)
        }.computeIfAbsent(ElixirLanguageLevelResolver.languageLevelFor(sigil)) { languageLevel ->
            val hostStart = sigil.textRange.startOffset

            bodyNodes(sigil)
                .mapNotNull { node ->
                    sigilEscape(sigil, Piece(node.elementType, node.text, node), languageLevel)
                        ?.takeIf { it != node.text }
                        ?.let { node.textRange.shiftLeft(hostStart) to it }
                }
                .toList()
        }

    /** The [escapes] wholly inside [rangeInsideHost]. */
    private fun escapesIn(escapes: List<Pair<TextRange, String>>, rangeInsideHost: TextRange): List<Pair<TextRange, String>> {
        val first = escapes.binarySearch { (range, _) -> range.startOffset.compareTo(rangeInsideHost.startOffset) }
            .let { if (it < 0) -it - 1 else it }

        return escapes.subList(first, escapes.size).takeWhile { (range, _) -> range.endOffset <= rangeInsideHost.endOffset }
    }

    private fun bodyNodes(sigil: Sigil): Sequence<ASTNode> =
        when (sigil) {
            is SigilLine -> sigil.body.node.getChildren(null).asSequence()
            is SigilHeredocLiteral -> sigil.heredocLineList.asSequence().flatMap { it.body.node.getChildren(null).asSequence() }
            else -> emptySequence()
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
