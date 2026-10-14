package org.elixir_lang.expander

import com.intellij.openapi.util.TextRange
import org.elixir_lang.lowering.ElixirAst

/**
 * The env each node of a file's expansion was entered in, by the node's origin. A node entered more than once keeps the
 * env of its first entry.
 *
 * @param top the env the file's expansion started from
 */
internal class EnvIndex(private val top: Env) {
    private class Entry(val start: Int, val end: Int, val env: Env) {
        /** Whether its expansion stopped, at an error or at what isn't ported. */
        var stopped = false
    }

    private val byRange = LinkedHashMap<Long, Entry>()

    /** The entries by start, outermost first, each with the index of the nearest entry before it that contains it. */
    private class Sorted(val entries: List<Entry>, val enclosing: IntArray)

    @Volatile
    private var sorted: Sorted? = null

    fun entering(node: ElixirAst, env: Env) {
        val origin = node.meta.origin
        byRange.putIfAbsent(key(origin.startOffset, origin.endOffset), Entry(origin.startOffset, origin.endOffset, env))
        sorted = null
    }

    fun left(node: ElixirAst, expansion: Expansion) {
        if (expansion is Expansion.Error || expansion is Expansion.Unported) {
            val origin = node.meta.origin
            byRange[key(origin.startOffset, origin.endOffset)]?.stopped = true
        }
    }

    /**
     * The env of the innermost node entered whose origin contains [range]. Where none was, as for a `defmodule` Elixir
     * compiles directly, the [top] env. A node after a stop in that node takes the env the stopped node was entered in,
     * which the nodes before it left.
     */
    fun at(range: TextRange): Env {
        byRange[key(range.startOffset, range.endOffset)]?.let { return it.env }

        val sorted = sorted ?: sort().also { sorted = it }
        var index = upperBound(sorted.entries, range.startOffset) - 1
        var examined = 0
        var env = top
        // The last entry before [range] in the one that contains it.
        var before: Entry? = null
        while (index >= 0) {
            val entry = sorted.entries[index]
            examined++
            if (entry.end >= range.endOffset) {
                env = before?.takeIf { it.stopped }?.env ?: entry.env
                break
            }
            before = entry
            // The entries between this one and the one enclosing it end before this one does, so they miss too.
            index = sorted.enclosing[index]
        }
        ExpanderSource.Probe.count(ExpanderSource.Probe.Counter.EXAMINED, examined)

        return env
    }

    private fun sort(): Sorted {
        val entries = byRange.values.sortedWith(compareBy<Entry> { it.start }.thenByDescending { it.end })
        val enclosing = IntArray(entries.size)
        val open = ArrayDeque<Int>()
        entries.forEachIndexed { index, entry ->
            while (open.isNotEmpty() && entries[open.last()].end < entry.end) open.removeLast()
            enclosing[index] = open.lastOrNull() ?: -1
            open.addLast(index)
        }

        return Sorted(entries, enclosing)
    }

    /** The index of the first entry starting after [offset]. */
    private fun upperBound(entries: List<Entry>, offset: Int): Int {
        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].start <= offset) low = mid + 1 else high = mid
        }
        return low
    }

    private fun key(start: Int, end: Int): Long = (start.toLong() shl 32) or end.toLong()
}
