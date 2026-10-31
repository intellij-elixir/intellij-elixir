package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import org.elixir_lang.expander.interpreter.COPY_NODES
import org.elixir_lang.lowering.Aligned
import org.elixir_lang.lowering.Aligner
import org.elixir_lang.lowering.ElixirAst
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Each macro argument's term and node, by which a term rebuilt from a value finds the source node it was escaped from.
 * Only nodes of the source are recorded: a node a macro built has the origin of a call that [expanded].
 */
internal class Origins : Aligner {
    private data class Key(val head: OtpErlangObject, val meta: OtpErlangObject, val arity: Int)

    /** Each recorded term, by identity, to its node: alignment walks source terms and reads their nodes here. */
    private val nodeOf = IdentityHashMap<OtpErlangObject, ElixirAst>()

    /** The origins of the calls run as macros: what each made has that origin, wherever it is then passed. */
    private val expanded = HashSet<TextRange>()

    private val byTerm = HashMap<OtpErlangObject, MutableList<OtpErlangTuple>>()

    private val byKey = HashMap<Key, MutableList<OtpErlangTuple>>()

    fun record(node: ElixirAst, term: OtpErlangObject) {
        if (node.meta.built || node.meta.origin in expanded) return

        if (nodeOf.put(term, node) == null && term is OtpErlangTuple && term.arity() == 3) {
            byTerm.getOrPut(term) { ArrayList(1) } += term
            keyOf(term)?.let { key -> byKey.getOrPut(key) { ArrayList(1) } += term }
        }
    }

    fun expanded(call: ElixirAst) {
        expanded += call.meta.origin
    }

    private fun keyOf(term: OtpErlangTuple): Key? =
        (term.elementAt(2) as? OtpErlangList)?.takeIf { it.lastTail == null }?.let { Key(term.elementAt(0), term.elementAt(1), it.arity()) }

    /** The first of [terms], where they all stand for one place in the source. */
    private fun single(terms: List<OtpErlangTuple>): OtpErlangTuple? {
        val first = nodeOf.getValue(terms[0]).meta.origin

        return terms[0].takeIf { terms.all { nodeOf.getValue(it).meta.origin == first } }
    }

    private fun equal(term: OtpErlangObject): Aligned.Source? =
        byTerm[term]?.let(::single)?.let { source(nodeOf.getValue(it)) }

    private fun source(node: ElixirAst) = Aligned.Source(node, nodes(node))

    private fun nodes(node: ElixirAst): Int {
        ProgressManager.checkCanceled()

        return 1 + children(node).sumOf(::nodes)
    }

    override fun look(term: OtpErlangObject, aligned: OtpErlangObject?): Aligned? {
        if (aligned != null) {
            if (isFragment(aligned)) return Aligned.Stays

            nodeOf[aligned]?.let { node ->
                if (term == aligned) return source(node)
                if (sameShape(term, aligned)) return Aligned.Spine(node.meta, aligned)
            }
        }

        // A literal, list or pair is the same term wherever it is written: it has no origin of its own to find.
        if (term !is OtpErlangTuple || term.arity() != 3) return null
        if (byTerm.containsKey(term)) return equal(term)

        return keyOf(term)
            ?.let { byKey[it] }
            ?.filter { fits(term, it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let(::single)
            ?.let { Aligned.Spine(nodeOf.getValue(it).meta, it) }
    }

    /** An element from a list's first `unquote_splicing` on may hold a later sibling, so its position says nothing. */
    override fun alignedElements(source: OtpErlangList): Int =
        source.elements().indexOfFirst { isCall(it, UNQUOTE_SPLICING) }.takeIf { it >= 0 } ?: source.arity()

    override val equalOnly: Aligner = object : Aligner {
        override fun look(term: OtpErlangObject, aligned: OtpErlangObject?): Aligned? = equal(term)

        override fun alignedElements(source: OtpErlangList): Int = 0

        override val equalOnly: Aligner get() = this
    }

    /** Whether [source] is an `unquote` or `unquote_splicing` call, at any depth: it reads no quote level. */
    private fun isFragment(source: OtpErlangObject): Boolean = isCall(source, UNQUOTE) || isCall(source, UNQUOTE_SPLICING)

    private fun isCall(source: OtpErlangObject, head: OtpErlangAtom): Boolean =
        source is OtpErlangTuple && source.arity() == 3 && source.elementAt(0) == head &&
            (source.elementAt(2) as? OtpErlangList)?.arity() == 1

    /** Whether [term] and [source] have the same head, meta and arity, or are lists of one length, or both pairs. */
    private fun sameShape(term: OtpErlangObject, source: OtpErlangObject): Boolean =
        when (term) {
            is OtpErlangList -> source is OtpErlangList && term.lastTail == null && source.lastTail == null && term.arity() == source.arity()
            is OtpErlangTuple -> source is OtpErlangTuple && term.arity() == source.arity() &&
                when (term.arity()) {
                    2 -> true
                    3 -> sameThree(term, source)
                    else -> false
                }
            else -> false
        }

    private fun sameThree(term: OtpErlangTuple, source: OtpErlangTuple): Boolean {
        val last = term.elementAt(2)
        val sourceLast = source.elementAt(2)

        return term.elementAt(1) == source.elementAt(1) &&
            (term.elementAt(0) == source.elementAt(0) || sameShape(term.elementAt(0), source.elementAt(0))) &&
            last is OtpErlangList && sourceLast is OtpErlangList && last.arity() == sourceLast.arity()
    }

    /**
     * Whether [term] is [source] but for what [source]'s fragments hold. A pair [read] before fitted, as one that didn't
     * ended the walk, so shared parts are each read once.
     */
    private fun fits(
        term: OtpErlangObject,
        source: OtpErlangObject,
        read: IdentityHashMap<OtpErlangObject, MutableSet<OtpErlangObject>> = IdentityHashMap(),
    ): Boolean {
        ProgressManager.checkCanceled()

        return when {
            isFragment(source) -> true
            !sameShape(term, source) -> term == source
            !read.getOrPut(term) { Collections.newSetFromMap(IdentityHashMap()) }.add(source) -> true
            term is OtpErlangList -> term.elements().indices.all { fits(term.elementAt(it), (source as OtpErlangList).elementAt(it), read) }
            term is OtpErlangTuple && term.arity() == 2 -> (0..1).all { fits(term.elementAt(it), (source as OtpErlangTuple).elementAt(it), read) }
            term is OtpErlangTuple -> {
                source as OtpErlangTuple
                val args = term.elementAt(2) as OtpErlangList
                fits(term.elementAt(0), source.elementAt(0), read) &&
                    args.elements().indices.all { fits(args.elementAt(it), (source.elementAt(2) as OtpErlangList).elementAt(it), read) }
            }
            else -> false
        }
    }

    private companion object {
        val UNQUOTE = OtpErlangAtom("unquote")
        val UNQUOTE_SPLICING = OtpErlangAtom("unquote_splicing")
    }
}

/**
 * A value or a macro's result as code: [term] as [ElixirAst.fromOtp] reads it, each node it finds
 * in [run]'s record its source node or that node's origin. Everything else has [origin], and is marked built where
 * [built]. `null` where it isn't code or would hold more than [COPY_NODES] nodes.
 */
internal fun rebuilt(
    term: OtpErlangObject,
    origin: TextRange,
    run: Run,
    built: Boolean,
    known: (OtpErlangObject) -> ElixirAst? = { null },
    onNode: ((OtpErlangObject, ElixirAst) -> Unit)? = null,
): ElixirAst? = ElixirAst.fromOtp(term, origin, values = true, known, COPY_NODES, run.origins, built, onNode)
