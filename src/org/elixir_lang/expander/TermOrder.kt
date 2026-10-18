package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBitstr
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangExternalFun
import com.ericsson.otp.erlang.OtpErlangFun
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangPid
import com.ericsson.otp.erlang.OtpErlangPort
import com.ericsson.otp.erlang.OtpErlangRef
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.NameArity
import java.math.BigDecimal

/** Erlang's term order over atom text: code point by code point, where `String` compares UTF-16 units. */
internal val ATOM_ORDER: Comparator<String> = Comparator { left, right ->
    val leftPoints = left.codePoints().iterator()
    val rightPoints = right.codePoints().iterator()

    while (leftPoints.hasNext() && rightPoints.hasNext()) {
        val compared = leftPoints.nextInt().compareTo(rightPoints.nextInt())

        if (compared != 0) return@Comparator compared
    }

    leftPoints.hasNext().compareTo(rightPoints.hasNext())
}

/**
 * Erlang's term order over a variable's context: an atom, a `{Module, n}` counter, or outside a module an integer
 * counter. A number sorts before an atom, which sorts before a tuple.
 */
private val CONTEXT_ORDER: Comparator<Variable.Context> =
    compareBy<Variable.Context> {
        when (it) {
            is Variable.Context.Atom -> 1
            is Variable.Context.Counter -> if (it.counter is Env.Counter.Unique) 0 else 2
        }
    }
        .thenBy(nullsFirst(ATOM_ORDER)) {
            when (it) {
                is Variable.Context.Atom -> it.text
                is Variable.Context.Counter -> (it.counter as? Env.Counter.InModule)?.module
            }
        }
        .thenBy {
            when (it) {
                is Variable.Context.Atom -> null
                is Variable.Context.Counter -> when (val counter = it.counter) {
                    is Env.Counter.InModule -> counter.n
                    is Env.Counter.Unique -> counter.n
                }
            }
        }

/** Erlang's term order over `{name, context}`, which a small map folds in. */
internal val VARIABLE_ORDER: Comparator<Variable> =
    compareBy(ATOM_ORDER, Variable::name).thenComparing(Variable::context, CONTEXT_ORDER)

/** Erlang's term order over `{name, arity}`. */
internal val NAME_ARITY_ORDER: Comparator<NameArity> =
    compareBy(ATOM_ORDER, NameArity::name).thenBy(NameArity::arity)

/**
 * Erlang's term order: a number, then an atom, reference, fun, port, pid, tuple, map, list and bitstring. Where two
 * different references, ports, pids or closures go is the VM's own, so comparing them throws [UnorderedTerms].
 */
internal val TERM_ORDER: Comparator<OtpErlangObject> = Comparator { left, right -> compareTerms(left, right, exact = false) }

/** [TERM_ORDER] as a map orders its keys, every integer before every float. */
internal val MAP_KEY_ORDER: Comparator<OtpErlangObject> = Comparator { left, right -> compareTerms(left, right, exact = true) }

internal class UnorderedTerms(left: OtpErlangObject, right: OtpErlangObject) :
    IllegalArgumentException("no known order between $left and $right")

/**
 * [exact] compares as a map's keys do, every integer before every float; otherwise an integer and a float compare by
 * value.
 */
private fun compareTerms(left: OtpErlangObject, right: OtpErlangObject, exact: Boolean): Int {
    val ranks = rank(left).compareTo(rank(right))
    if (ranks != 0) return ranks

    return when (left) {
        is OtpErlangLong, is OtpErlangDouble -> compareNumbers(left, right, exact)
        is OtpErlangAtom -> ATOM_ORDER.compare(left.atomValue(), (right as OtpErlangAtom).atomValue())
        is OtpErlangExternalFun if right is OtpErlangExternalFun ->
            compareValuesBy(left, right, ATOM_ORDER) { it.module }.takeIf { it != 0 }
                ?: compareValuesBy(left, right, ATOM_ORDER) { it.function }.takeIf { it != 0 }
                ?: left.arity.compareTo(right.arity)
        is OtpErlangTuple -> {
            val other = right as OtpErlangTuple

            left.arity().compareTo(other.arity()).takeIf { it != 0 }
                ?: compareElements(left.elements().asList(), other.elements().asList(), exact)
        }
        is OtpErlangMap -> compareMaps(left, right as OtpErlangMap, exact)
        is OtpErlangList, is OtpErlangString -> compareLists(left, right, exact)
        is OtpErlangBitstr -> compareBits(left, right as OtpErlangBitstr)
        else -> if (left == right) 0 else throw UnorderedTerms(left, right)
    }
}

private fun rank(term: OtpErlangObject): Int =
    when (term) {
        is OtpErlangLong, is OtpErlangDouble -> 0
        is OtpErlangAtom -> 1
        is OtpErlangRef -> 2
        is OtpErlangFun, is OtpErlangExternalFun -> 3
        is OtpErlangPort -> 4
        is OtpErlangPid -> 5
        is OtpErlangTuple -> 6
        is OtpErlangMap -> 7
        is OtpErlangList, is OtpErlangString -> 8
        is OtpErlangBitstr -> 9
        else -> throw IllegalArgumentException("not an Erlang term: $term")
    }

private fun compareNumbers(left: OtpErlangObject, right: OtpErlangObject, exact: Boolean): Int {
    if (exact) (left is OtpErlangDouble).compareTo(right is OtpErlangDouble).takeIf { it != 0 }?.let { return it }

    return number(left).compareTo(number(right))
}

private fun number(term: OtpErlangObject): BigDecimal =
    when (term) {
        is OtpErlangLong -> BigDecimal(term.bigIntegerValue())
        else -> BigDecimal((term as OtpErlangDouble).doubleValue())
    }

/** By size, then the keys in their order, then the values in the keys' order. */
private fun compareMaps(left: OtpErlangMap, right: OtpErlangMap, exact: Boolean): Int {
    left.arity().compareTo(right.arity()).takeIf { it != 0 }?.let { return it }

    val keyOrder = Comparator<OtpErlangObject> { a, b -> compareTerms(a, b, exact = true) }
    val leftKeys = left.keys().sortedWith(keyOrder)
    val rightKeys = right.keys().sortedWith(keyOrder)

    return compareElements(leftKeys, rightKeys, exact = true).takeIf { it != 0 }
        ?: compareElements(leftKeys.map { left.get(it) }, rightKeys.map { right.get(it) }, exact)
}

/** Cell by cell: the heads, then the tails, so an improper tail compares as the term it is. */
private fun compareLists(left: OtpErlangObject, right: OtpErlangObject, exact: Boolean): Int {
    val (leftElements, leftTail) = cells(left)
    val (rightElements, rightTail) = cells(right)
    val shared = minOf(leftElements.size, rightElements.size)

    compareElements(leftElements.subList(0, shared), rightElements.subList(0, shared), exact).takeIf { it != 0 }
        ?.let { return it }

    return compareRests(rest(leftElements, leftTail, shared), rest(rightElements, rightTail, shared), exact)
}

/** Where one list ends in `[]`, which sorts before what follows the other's cells, unless that is a lesser term. */
private fun compareRests(left: OtpErlangObject?, right: OtpErlangObject?, exact: Boolean): Int =
    when {
        left == null && right == null -> 0
        left == null -> if (isList(right!!)) -1 else compareTerms(EMPTY_LIST, right, exact)
        right == null -> -compareRests(null, left, exact)
        else -> compareTerms(left, right, exact)
    }

private fun isList(term: OtpErlangObject): Boolean = term is OtpErlangList || term is OtpErlangString

/** A list's elements and its improper tail, `null` where it ends in `[]`. */
private fun cells(list: OtpErlangObject): Pair<List<OtpErlangObject>, OtpErlangObject?> =
    when (list) {
        is OtpErlangString -> list.stringValue().codePoints().toArray().map { OtpErlangLong(it.toLong()) } to null
        else -> (list as OtpErlangList).let { it.elements().asList() to it.lastTail }
    }

/** What follows the first [from] cells: the rest of the list, or its tail, `null` for `[]`. */
private fun rest(elements: List<OtpErlangObject>, tail: OtpErlangObject?, from: Int): OtpErlangObject? =
    if (from < elements.size) OtpErlangList(elements.subList(from, elements.size).toTypedArray(), tail) else tail

private val EMPTY_LIST = OtpErlangList()

private fun compareElements(left: List<OtpErlangObject>, right: List<OtpErlangObject>, exact: Boolean): Int =
    left.zip(right).firstNotNullOfOrNull { (a, b) -> compareTerms(a, b, exact).takeIf { it != 0 } }
        ?: left.size.compareTo(right.size)

/** Bit by bit, a prefix first. */
private fun compareBits(left: OtpErlangBitstr, right: OtpErlangBitstr): Int {
    val leftBits = bits(left)
    val rightBits = bits(right)

    for (i in 0 until minOf(leftBits, rightBits)) {
        val compared = bit(left, i).compareTo(bit(right, i))

        if (compared != 0) return compared
    }

    return leftBits.compareTo(rightBits)
}

private fun bits(bitstring: OtpErlangBitstr): Int = bitstring.binaryValue().size * 8 - bitstring.pad_bits()

private fun bit(bitstring: OtpErlangBitstr, index: Int): Int = (bitstring.binaryValue()[index / 8].toInt() shr (7 - index % 8)) and 1
