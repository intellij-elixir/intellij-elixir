package org.elixir_lang.expander

import com.intellij.openapi.util.TextRange
import org.elixir_lang.declaration.Uncertain
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

        /** The first stop left here. A macro's output shares this origin, and a unit it queued can stop after the output. */
        var stopped: Expansion.Stop? = null

        /** [stopped]'s env when this node was left; a stamp further out is not this level's. */
        var stopEnv: Env? = null

        /** Where the run left the module this `defmodule` defines uncompiled: its body's start, or `null`. */
        var uncompiled: Int? = null

        /** Where an [Expansion.Opaque] ended this node: the opaque call's start, or `null`. */
        var opaque: Int? = null

        /** Where the expansion stopped reaching this node's descendants, or `null`. */
        val reachedUntil: Int? get() = listOfNotNull(stop, uncompiled).minOrNull()

        /** Whether the expansion stopped reaching this node's descendants before [range] ends. */
        fun stopsBefore(range: TextRange): Boolean = (reachedUntil ?: Int.MAX_VALUE) < range.endOffset
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
        if (expansion is Expansion.Opaque) {
            val origin = node.meta.origin
            byRange[key(origin.startOffset, origin.endOffset)]?.let { it.opaque = it.opaque ?: expansion.at.meta.origin.startOffset }
            return
        }
        if (expansion !is Expansion.Stop) return
        val at = when (expansion) {
            is Expansion.Error -> expansion.at
            is Expansion.Unported -> expansion.at
        }
        val origin = node.meta.origin
        val entry = byRange[key(origin.startOffset, origin.endOffset)] ?: return
        entry.stop = at.meta.origin.startOffset
        if (entry.stopped == null || entry.stopped === expansion) {
            entry.stopped = expansion
            entry.stopEnv = expansion.envAtStop
        }
    }

    /** [defmodule]'s module was never compiled, so nothing in its [body] was reached. */
    fun uncompiled(defmodule: ElixirAst, body: ElixirAst) {
        val origin = defmodule.meta.origin
        byRange[key(origin.startOffset, origin.endOffset)]?.uncompiled = body.meta.origin.startOffset
    }

    /** [range]'s env as [placed] reads it. */
    fun at(range: TextRange): Env = placed(range).env

    /** An env [placed] reads for a range, and why it may not be the env Elixir expands the range in, `null` where it is. */
    class Placed(val env: Env, val uncertain: Uncertain?)

    /**
     * The env of the innermost node entered whose origin contains [range]. Where none was, as for a `defmodule` Elixir
     * compiles directly, the [top] env, [Uncertain.UNREACHED]. A node after a stop in that node takes the env as it stood
     * at the stop, or, where the stop held none when the stopped node was left, the env that node was entered in, and is
     * [Uncertain.STOP]; past a stop or an opaque call in a node containing it, it is [Uncertain.STOP] or
     * [Uncertain.OPAQUE].
     */
    fun placed(range: TextRange): Placed {
        byRange[key(range.startOffset, range.endOffset)]?.let { return Placed(it.env, null) }
        val enclosing = enclosing(range) ?: return Placed(top, Uncertain.UNREACHED)
        enclosing.stoppedBefore()?.let { return Placed(it.stopEnv ?: it.env, Uncertain.STOP) }
        val uncertain = enclosing.chain().firstNotNullOfOrNull { entry ->
            when {
                entry.stopsBefore(range) -> Uncertain.STOP
                (entry.opaque ?: Int.MAX_VALUE) < range.endOffset -> Uncertain.OPAQUE
                else -> null
            }
        }

        return Placed(enclosing.entry.env, uncertain)
    }

    /**
     * [at]'s env, except `null` where the expansion did not reach [range]: [range] was never entered, and no node
     * containing it was, or one that was stopped, or whose module was left uncompiled, before [range] ends.
     */
    fun atReached(range: TextRange): Env? {
        byRange[key(range.startOffset, range.endOffset)]?.let { return it.env }
        val enclosing = enclosing(range) ?: return null

        if (enclosing.chain().any { it.stopsBefore(range) }) return null

        return enclosing.entry.env
    }

    /** The innermost entry containing a range, at [index] in [sorted], and the last entry before the range in it. */
    private class Enclosing(val sorted: Sorted, val index: Int, val before: Entry?) {
        val entry: Entry get() = sorted.entries[index]

        /** The node before the range in [entry] where that node stopped, or `null`. */
        fun stoppedBefore(): Entry? = before?.takeIf { it.stop != null }

        /** [entry], then each entry containing it, outward. */
        fun chain(): Sequence<Entry> =
            generateSequence(index) { sorted.enclosing[it].takeIf { enclosing -> enclosing >= 0 } }.map { sorted.entries[it] }
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
