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
        /** Where its expansion stopped, at an error or at what isn't ported: that node's start, or `null`. */
        var stop: Int? = null

        /** Where the run left the module this `defmodule` defines uncompiled: its body's start, or `null`. */
        var uncompiled: Int? = null

        /** Where the expansion stopped reaching this node's descendants, or `null`. */
        val reachedUntil: Int? get() = listOfNotNull(stop, uncompiled).minOrNull()
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
        val at = when (expansion) {
            is Expansion.Error -> expansion.at
            is Expansion.Unported -> expansion.at
            else -> return
        }
        val origin = node.meta.origin
        byRange[key(origin.startOffset, origin.endOffset)]?.stop = at.meta.origin.startOffset
    }

    /** [defmodule]'s module was never compiled, so nothing in its [body] was reached. */
    fun uncompiled(defmodule: ElixirAst, body: ElixirAst) {
        val origin = defmodule.meta.origin
        byRange[key(origin.startOffset, origin.endOffset)]?.uncompiled = body.meta.origin.startOffset
    }

    /**
     * The env of the innermost node entered whose origin contains [range]. Where none was, as for a `defmodule` Elixir
     * compiles directly, the [top] env. A node after a stop in that node takes the env the stopped node was entered in,
     * which the nodes before it left.
     */
    fun at(range: TextRange): Env {
        byRange[key(range.startOffset, range.endOffset)]?.let { return it.env }
        val enclosing = enclosing(range) ?: return top

        return enclosing.before?.takeIf { it.stop != null }?.env ?: enclosing.entry.env
    }

    /**
     * [at]'s env, except `null` where the expansion did not reach [range]: [range] was never entered, and no node
     * containing it was, or one that was stopped, or whose module was left uncompiled, before [range] ends.
     */
    fun atReached(range: TextRange): Env? {
        byRange[key(range.startOffset, range.endOffset)]?.let { return it.env }
        val enclosing = enclosing(range) ?: return null

        var index = enclosing.index
        while (index >= 0) {
            if ((enclosing.sorted.entries[index].reachedUntil ?: Int.MAX_VALUE) < range.endOffset) return null
            index = enclosing.sorted.enclosing[index]
        }

        return enclosing.entry.env
    }

    /** The innermost entry containing a range, at [index] in [sorted], and the last entry before the range in it. */
    private class Enclosing(val sorted: Sorted, val index: Int, val before: Entry?) {
        val entry: Entry get() = sorted.entries[index]
    }

    private fun enclosing(range: TextRange): Enclosing? {
        val sorted = sorted ?: sort().also { sorted = it }
        var index = upperBound(sorted.entries, range.startOffset) - 1
        var examined = 0
        var before: Entry? = null
        while (index >= 0) {
            examined++
            if (sorted.entries[index].end >= range.endOffset) break
            before = sorted.entries[index]
            // The entries between this one and the one enclosing it end before this one does, so they miss too.
            index = sorted.enclosing[index]
        }
        ExpanderSource.Probe.count(ExpanderSource.Probe.Counter.EXAMINED, examined)

        return if (index >= 0) Enclosing(sorted, index, before) else null
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
