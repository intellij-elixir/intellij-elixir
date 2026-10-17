package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBitstr
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangExternalFun
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangPid
import com.ericsson.otp.erlang.OtpErlangPort
import com.ericsson.otp.erlang.OtpErlangRef
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.NEGATIVE_ZERO_IS_DISTINCT
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.TermKind
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
 * Erlang's term order on the OTP running Elixir at [level]: a number, then an atom, reference, fun, port, pid, tuple,
 * map, list and bitstring. Where two different references, ports, pids or closures go is the VM's own, so comparing
 * them throws [UnorderedTerms], as comparing a term the interpreter doesn't know does.
 */
internal fun termOrder(level: ElixirLanguageLevel): Comparator<OtpErlangObject> =
    if (NEGATIVE_ZERO_IS_DISTINCT.isSufficient(level)) SIGNED_TERM_ORDER else TERM_ORDER

/** [termOrder] as a map orders its keys and `=:=` compares, every integer before every float. */
internal fun mapKeyOrder(level: ElixirLanguageLevel): Comparator<OtpErlangObject> =
    if (NEGATIVE_ZERO_IS_DISTINCT.isSufficient(level)) SIGNED_MAP_KEY_ORDER else MAP_KEY_ORDER

/** [mapKeyOrder] where [exact], as `=:=` compares, else [termOrder], as `==` does. */
internal fun termOrder(level: ElixirLanguageLevel, exact: Boolean): Comparator<OtpErlangObject> =
    if (exact) mapKeyOrder(level) else termOrder(level)

/** Whether [left] and [right], exactly equal at some level, differ in a zero's sign, as `=:=` tells from OTP 27. */
internal fun differInSign(left: OtpErlangObject, right: OtpErlangObject): Boolean = SIGNED_MAP_KEY_ORDER.compare(left, right) != 0

private val TERM_ORDER = order(Numbers(exact = false, signed = false))
private val MAP_KEY_ORDER = order(Numbers(exact = true, signed = false))
private val SIGNED_TERM_ORDER = order(Numbers(exact = false, signed = true))
private val SIGNED_MAP_KEY_ORDER = order(Numbers(exact = true, signed = true))

private fun order(numbers: Numbers): Comparator<OtpErlangObject> =
    Comparator { left, right -> compareTerms(left, right, numbers) }

/**
 * How two numbers compare: [exact] puts every integer before every float, as `=:=` does, else they compare by value.
 * [signed] puts `-0.0` before `0.0` where [exact] ([NEGATIVE_ZERO_IS_DISTINCT]).
 */
private data class Numbers(val exact: Boolean, val signed: Boolean) {
    /** A map's keys compare exactly, whatever compares the map. */
    val keys: Numbers get() = if (exact) this else copy(exact = true)
}

/**
 * Erlang's `==`, or `=:=` where [exact], on the OTP running Elixir at [level]. Two different references, ports or pids
 * at one place are unequal, though only the VM orders them; any other pair without a known order throws
 * [UnorderedTerms].
 */
internal fun termsEqual(left: OtpErlangObject, right: OtpErlangObject, exact: Boolean, level: ElixirLanguageLevel): Boolean =
    !holdsDistinctIdentities(left, right) && termOrder(level, exact).compare(left, right) == 0

/**
 * Whether [left] and [right] hold, at one place outside a map, two different references, ports or pids: terms that are
 * unequal, though only the VM orders them.
 */
private fun holdsDistinctIdentities(left: OtpErlangObject, right: OtpErlangObject): Boolean {
    ProgressManager.checkCanceled()

    return when {
        left is OtpErlangTuple && right is OtpErlangTuple ->
            left.elements().zip(right.elements()).any { (a, b) -> holdsDistinctIdentities(a, b) }
        left is OtpErlangList && right is OtpErlangList ->
            left.elements().zip(right.elements()).any { (a, b) -> holdsDistinctIdentities(a, b) } ||
                (left.arity() == right.arity() && left.lastTail != null && right.lastTail != null &&
                    holdsDistinctIdentities(left.lastTail, right.lastTail))
        left is OtpErlangRef && right is OtpErlangRef ||
            left is OtpErlangPort && right is OtpErlangPort ||
            left is OtpErlangPid && right is OtpErlangPid -> left != right
        else -> false
    }
}

internal class UnorderedTerms : IllegalArgumentException("no known order between two terms")

private fun compareTerms(left: OtpErlangObject, right: OtpErlangObject, numbers: Numbers): Int {
    ProgressManager.checkCanceled()

    val leftRank = rank(left) ?: throw UnorderedTerms()
    val ranks = leftRank.compareTo(rank(right) ?: throw UnorderedTerms())
    if (ranks != 0) return ranks

    return when (left) {
        is OtpErlangLong, is OtpErlangDouble -> compareNumbers(left, right, numbers)
        is OtpErlangAtom -> ATOM_ORDER.compare(left.atomValue(), (right as OtpErlangAtom).atomValue())
        is OtpErlangExternalFun if right is OtpErlangExternalFun ->
            compareValuesBy(left, right, ATOM_ORDER) { it.module }.takeIf { it != 0 }
                ?: compareValuesBy(left, right, ATOM_ORDER) { it.function }.takeIf { it != 0 }
                ?: left.arity.compareTo(right.arity)
        is OtpErlangTuple -> {
            val other = right as OtpErlangTuple

            left.arity().compareTo(other.arity()).takeIf { it != 0 }
                ?: compareElements(left.elements().asList(), other.elements().asList(), numbers)
        }
        is OtpErlangMap -> compareMaps(left, right as OtpErlangMap, numbers)
        is OtpErlangList, is OtpErlangString -> compareLists(left, right, numbers)
        is OtpErlangBitstr -> compareBits(left, right as OtpErlangBitstr)
        else -> if (left == right) 0 else throw UnorderedTerms()
    }
}

/** `null` for a term the interpreter holds without knowing it, which has no known place. */
private fun rank(term: OtpErlangObject): Int? = TermKind.of(term)?.ordinal

private fun compareNumbers(left: OtpErlangObject, right: OtpErlangObject, numbers: Numbers): Int {
    if (numbers.exact) {
        (left is OtpErlangDouble).compareTo(right is OtpErlangDouble).takeIf { it != 0 }?.let { return it }
    }

    val compared = number(left).compareTo(number(right))

    // Two floats of one value differ only as `-0.0` and `0.0`.
    return if (compared == 0 && numbers.exact && numbers.signed && left is OtpErlangDouble) {
        compareValues(left.doubleValue(), (right as OtpErlangDouble).doubleValue())
    } else {
        compared
    }
}

/** A float past a double's range, which no Elixir term is, has no place. */
private fun number(term: OtpErlangObject): BigDecimal =
    when (term) {
        is OtpErlangLong -> BigDecimal(term.bigIntegerValue())
        else ->
            BigDecimal((term as OtpErlangDouble).doubleValue().takeIf(Double::isFinite) ?: throw UnorderedTerms())
    }

/** By size, then the keys in their order, then the values in the keys' order. */
private fun compareMaps(left: OtpErlangMap, right: OtpErlangMap, numbers: Numbers): Int {
    left.arity().compareTo(right.arity()).takeIf { it != 0 }?.let { return it }

    val keys = numbers.keys
    val keyOrder = Comparator<OtpErlangObject> { a, b -> compareTerms(a, b, keys) }
    // By entry, as a key's lookup compares it again.
    val leftEntries = left.entrySet().sortedWith(compareBy(keyOrder) { it.key })
    val rightEntries = right.entrySet().sortedWith(compareBy(keyOrder) { it.key })

    return compareElements(leftEntries.map { it.key }, rightEntries.map { it.key }, keys).takeIf { it != 0 }
        ?: compareElements(leftEntries.map { it.value }, rightEntries.map { it.value }, numbers)
}

/** Cell by cell: the heads, then the tails, so an improper tail compares as the term it is. */
private fun compareLists(left: OtpErlangObject, right: OtpErlangObject, numbers: Numbers): Int {
    val (leftElements, leftTail) = cells(left)
    val (rightElements, rightTail) = cells(right)
    val shared = minOf(leftElements.size, rightElements.size)

    compareElements(leftElements.subList(0, shared), rightElements.subList(0, shared), numbers).takeIf { it != 0 }
        ?.let { return it }

    return compareRests(rest(leftElements, leftTail, shared), rest(rightElements, rightTail, shared), numbers)
}

/** Where one list ends in `[]`, which sorts before what follows the other's cells, unless that is a lesser term. */
private fun compareRests(left: OtpErlangObject?, right: OtpErlangObject?, numbers: Numbers): Int =
    when {
        left == null && right == null -> 0
        left == null -> if (isList(right!!)) -1 else compareTerms(EMPTY_LIST, right, numbers)
        right == null -> -compareRests(null, left, numbers)
        else -> compareTerms(left, right, numbers)
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

private fun compareElements(left: List<OtpErlangObject>, right: List<OtpErlangObject>, numbers: Numbers): Int =
    left.zip(right).firstNotNullOfOrNull { (a, b) -> compareTerms(a, b, numbers).takeIf { it != 0 } }
        ?: left.size.compareTo(right.size)

/** Bit by bit, a prefix first. */
private fun compareBits(left: OtpErlangBitstr, right: OtpErlangBitstr): Int {
    val leftBits = bits(left)
    val rightBits = bits(right)

    for (i in 0 until minOf(leftBits, rightBits)) {
        ProgressManager.checkCanceled()

        val compared = bit(left, i).compareTo(bit(right, i))

        if (compared != 0) return compared
    }

    return leftBits.compareTo(rightBits)
}

private fun bits(bitstring: OtpErlangBitstr): Int = bitstring.binaryValue().size * 8 - bitstring.pad_bits()

private fun bit(bitstring: OtpErlangBitstr, index: Int): Int = (bitstring.binaryValue()[index / 8].toInt() shr (7 - index % 8)) and 1
