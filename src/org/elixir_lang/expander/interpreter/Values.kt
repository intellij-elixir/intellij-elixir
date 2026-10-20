package org.elixir_lang.expander.interpreter

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangBitstr
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangFun
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangPid
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.ericsson.otp.erlang.OtpOutputStream
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.expander.differInSign
import org.elixir_lang.expander.mapKeyOrder
import org.elixir_lang.expander.termOrder
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.fitsAnAtom
import java.math.BigInteger

/** The evaluator can't follow the macro body any further, for [reason]. */
internal class Declined(val reason: String) : RuntimeException(reason, null, false, false)

/** Declines for [reason], each `%s` in which names the next of [terms]. */
internal fun decline(reason: String, vararg terms: OtpErlangObject): Nothing =
    throw Declined(if (terms.isEmpty()) reason else reason.format(*terms.map { Named(it).text }.toTypedArray()))

/**
 * [term] as Erlang writes it, cut short with `...` after its first [NAMED_NODES] nodes: a term can hold one part in
 * many places, so writing it whole can take as long as walking it.
 */
private class Named(term: OtpErlangObject) {
    private val written = StringBuilder()
    private var nodes = NAMED_NODES

    init {
        write(term)
    }

    val text: String get() = written.toString()

    private fun write(term: OtpErlangObject) {
        ProgressManager.checkCanceled()

        if (nodes-- == 0) written.append("...")
        if (nodes < 0) return

        when (term) {
            is OtpErlangTuple -> sequence("{", term.elements().asList(), "}")
            is OtpErlangList -> sequence("[", term.elements().asList(), "]", term.lastTail)
            is OtpErlangMap -> {
                written.append("#{")

                for ((index, entry) in entries(term).withIndex()) {
                    if (nodes < 0) break
                    if (index > 0) written.append(",")
                    write(entry.first)
                    written.append(" => ")
                    write(entry.second)
                }

                written.append("}")
            }
            else -> written.append(term)
        }
    }

    /** [elements] and [tail] between [open] and [close], as many as are written before the nodes run out. */
    private fun sequence(open: String, elements: List<OtpErlangObject>, close: String, tail: OtpErlangObject? = null) {
        written.append(open)

        for ((index, element) in elements.withIndex()) {
            if (nodes < 0) break
            if (index > 0) written.append(",")
            write(element)
        }

        if (tail != null && nodes >= 0) {
            written.append("|")
            write(tail)
        }

        written.append(close)
    }
}

/** The nodes of a term a decline names: enough to tell the term, far fewer than one sharing its parts can hold. */
private const val NAMED_NODES = 32

/** An exception of class [kind] (`error`, `throw` or `exit`) the body raised, with its [reason] and [stacktrace]. */
internal class Thrown(val kind: String, val reason: OtpErlangObject, val stacktrace: OtpErlangObject) :
    RuntimeException(null, null, false, false)

/** A value the evaluator doesn't know, such as a `__CALLER__` field [Env][org.elixir_lang.expander.Env] doesn't hold. */
internal object Unknown : OtpErlangObject() {
    // Java serialization calls it.
    @Suppress("unused")
    private fun readResolve(): Any = Unknown

    override fun encode(buf: OtpOutputStream) = decline("an unknown value has no external form")

    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)

    override fun toString() = "#Unknown"
}

/**
 * A binary whose bytes the evaluator doesn't know, such as a message built by `inspect`: a binary segment takes it, as
 * `String.Chars` does, where anything that reads its bytes declines.
 */
internal object UnknownBinary : OtpErlangObject() {
    // Java serialization calls it.
    @Suppress("unused")
    private fun readResolve(): Any = UnknownBinary

    override fun encode(buf: OtpOutputStream) = decline("an unknown value has no external form")

    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)

    override fun toString() = "#UnknownBinary"
}

/** Whether [term] is [Unknown] or [UnknownBinary]. */
internal fun isUnknown(term: OtpErlangObject) = term === Unknown || term === UnknownBinary

/** An `fn`, or a capture of a local function, that [invoke]s its clauses with [arity] arguments. */
internal class Closure(val arity: Int, val invoke: (List<OtpErlangObject>) -> OtpErlangObject) :
    OtpErlangFun(OtpErlangPid("nonode@nohost", 0, 0, 0), "erl_eval", 0L, 0L, arrayOf()) {
    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)

    override fun toString() = "#Function<closure/$arity>"
}

internal val TRUE = OtpErlangAtom("true")
internal val FALSE = OtpErlangAtom("false")
internal val NIL = OtpErlangAtom("nil")
internal val EMPTY = OtpErlangList()

/**
 * The atom [name]. Past an atom's 255 characters it declines: Elixir refuses such an alias, or `__MODULE__`, when it
 * compiles the macro's own module (`elixir_aliases:concat/1`), and a builder that raises `system_limit` says so first.
 */
internal fun atom(name: String): OtpErlangAtom =
    if (fitsAnAtom(name)) OtpErlangAtom(name) else decline("an atom of over 255 characters")

internal fun bool(value: Boolean) = if (value) TRUE else FALSE

internal fun integer(value: Long) = OtpErlangLong(value)

internal fun integer(value: BigInteger) = OtpErlangLong(value)

internal fun binary(text: String) = OtpErlangBinary(text.toByteArray(Charsets.UTF_8))

internal fun tuple(vararg elements: OtpErlangObject) = OtpErlangTuple(arrayOf(*elements))

internal fun list(elements: List<OtpErlangObject>) = OtpErlangList(elements.toTypedArray())

/** `[heads | tail]`, [Unknown] where [tail] is: how many cells follow, and whether the list is proper, aren't known. */
internal fun cons(heads: List<OtpErlangObject>, tail: OtpErlangObject): OtpErlangObject =
    when {
        heads.isEmpty() || tail === Unknown -> tail
        tail is OtpErlangList && tail.lastTail == null -> list(heads + tail.elements())
        tail is OtpErlangList -> OtpErlangList((heads + tail.elements()).toTypedArray(), tail.lastTail)
        else -> OtpErlangList(heads.toTypedArray(), tail)
    }

/** A bitstring of [bytes] with [padBits] unused bits in its last byte, a binary where there are none. */
internal fun bitstring(bytes: ByteArray, padBits: Int): OtpErlangBitstr =
    if (padBits == 0) OtpErlangBinary(bytes) else OtpErlangBitstr(bytes, padBits)

/**
 * A map of [entries] in their order, a later value replacing an earlier one at a key exactly equal to it at [level].
 * Declines where the two keys differ in a zero's sign.
 */
internal fun mapOf(entries: List<Pair<OtpErlangObject, OtpErlangObject>>, level: ElixirLanguageLevel): OtpErlangMap {
    val keys = mutableListOf<OtpErlangObject>()
    val values = mutableListOf<OtpErlangObject>()
    val byHash = mutableMapOf<Int, MutableList<Int>>()

    for ((key, value) in entries) {
        known(key)
        // Two captures of one function are one key in Elixir, but a closure is equal only to itself.
        if (holdsClosure(key)) decline("a function in a map key")

        val at = indexIn(keys, key, level, byHash[key.hashCode()].orEmpty())

        if (at == null) {
            byHash.getOrPut(key.hashCode(), ::mutableListOf) += keys.size
            keys += key
            values += value
        } else {
            // Which of the two the map keeps depends on how the VM holds it and on what the compiler folded.
            if (keys[at] !== key && holdsFloat(key) && differInSign(keys[at], key)) {
                decline("which of %s and %s a map keeps", keys[at], key)
            }
            values[at] = value
        }
    }

    return OtpErlangMap(keys.toTypedArray(), values.toTypedArray())
}

/** [map]'s value at the key exactly equal to [key] at [level], or `null` where it has none. */
internal fun valueAt(map: OtpErlangMap, key: OtpErlangObject, level: ElixirLanguageLevel): OtpErlangObject? {
    val keys = map.keys().asList()

    return indexIn(keys, known(key), level, keys.indices)?.let { map.values()[it] }
}

/**
 * The index of the one of [keys] exactly equal to [key] at [level]. A key holding a float is compared with each key
 * holding one, as `hashCode` parts `0.0` from `-0.0` on every OTP; any other only with the [sameHash] keys.
 */
private fun indexIn(
    keys: List<OtpErlangObject>,
    key: OtpErlangObject,
    level: ElixirLanguageLevel,
    sameHash: Iterable<Int>,
): Int? =
    if (holdsFloat(key)) keys.indices.firstOrNull { holdsFloat(keys[it]) && equal(keys[it], key, true, level) }
    else sameHash.firstOrNull { keys[it].hashCode() == key.hashCode() && sameTerm(keys[it], key) }

/**
 * Whether [left] and [right] are the one term `equals` says they are, checking for cancellation at each node: `equals`
 * walks both whole, and a term can hold one part in many places.
 */
private fun sameTerm(left: OtpErlangObject?, right: OtpErlangObject?): Boolean {
    ProgressManager.checkCanceled()

    return when {
        left === right -> true
        left == null || right == null -> false
        left is OtpErlangTuple ->
            right is OtpErlangTuple && left.arity() == right.arity() &&
                (0 until left.arity()).all { sameTerm(left.elementAt(it), right.elementAt(it)) }
        left is OtpErlangList ->
            right is OtpErlangList && left.arity() == right.arity() &&
                (0 until left.arity()).all { sameTerm(left.elementAt(it), right.elementAt(it)) } &&
                sameTerm(left.lastTail, right.lastTail)
        left is OtpErlangMap -> right is OtpErlangMap && left.arity() == right.arity() && sameEntries(left, right)
        else -> left == right
    }
}

private fun sameEntries(left: OtpErlangMap, right: OtpErlangMap): Boolean {
    val keys = right.keys().asList()
    val values = right.values()
    val byHash = keys.indices.groupBy { keys[it].hashCode() }

    return entries(left).all { (key, value) ->
        byHash[key.hashCode()].orEmpty().firstOrNull { sameTerm(keys[it], key) }?.let { sameTerm(values[it], value) } == true
    }
}

private fun holdsFloat(term: OtpErlangObject): Boolean {
    ProgressManager.checkCanceled()

    return when (term) {
        is OtpErlangDouble -> true
        is OtpErlangTuple -> term.elements().any(::holdsFloat)
        is OtpErlangList -> term.elements().any(::holdsFloat) || term.lastTail?.let(::holdsFloat) == true
        is OtpErlangMap -> term.keys().any(::holdsFloat) || term.values().any(::holdsFloat)
        else -> false
    }
}

internal fun entries(map: OtpErlangMap): List<Pair<OtpErlangObject, OtpErlangObject>> = map.keys().zip(map.values())

/** [term], which must hold nothing unknown ([isUnknown]), as a map key or a compared term must. */
internal fun known(term: OtpErlangObject): OtpErlangObject {
    ProgressManager.checkCanceled()

    when (term) {
        Unknown, UnknownBinary -> decline("an unknown value")
        is OtpErlangTuple -> term.elements().forEach(::known)
        is OtpErlangList -> {
            term.elements().forEach(::known)
            term.lastTail?.let(::known)
        }
        is OtpErlangMap -> {
            term.keys().forEach(::known)
            term.values().forEach(::known)
        }
    }

    return term
}

private fun holdsClosure(term: OtpErlangObject): Boolean {
    ProgressManager.checkCanceled()

    return when (term) {
        is Closure -> true
        is OtpErlangTuple -> term.elements().any(::holdsClosure)
        is OtpErlangList -> term.elements().any(::holdsClosure) || term.lastTail?.let(::holdsClosure) == true
        is OtpErlangMap -> term.keys().any(::holdsClosure) || term.values().any(::holdsClosure)
        else -> false
    }
}

/** A list's elements and its tail, `null` for `[]`; `null` itself for a term that isn't a list. */
internal fun cells(term: OtpErlangObject): Pair<List<OtpErlangObject>, OtpErlangObject?>? =
    when (term) {
        is OtpErlangList -> term.elements().asList() to term.lastTail
        is OtpErlangString -> term.stringValue().codePoints().toArray().map { integer(it.toLong()) } to null
        Unknown -> decline("an unknown value")
        else -> null
    }

/** The elements of the proper list [term], or `null` for any other term. */
internal fun properElements(term: OtpErlangObject): List<OtpErlangObject>? =
    cells(term)?.takeIf { it.second == null }?.first

internal fun isNumber(term: OtpErlangObject) = term is OtpErlangLong || term is OtpErlangDouble

/** Erlang's `==` where [exact] is false, else `=:=`; declines on terms whose order only the VM knows. */
internal fun equal(left: OtpErlangObject, right: OtpErlangObject, exact: Boolean, level: ElixirLanguageLevel): Boolean =
    compare(left, right, level, exact) == 0

internal fun compare(
    left: OtpErlangObject,
    right: OtpErlangObject,
    level: ElixirLanguageLevel,
    exact: Boolean = false,
): Int {
    if (left === right && !isUnknown(left)) return 0

    known(left)
    known(right)

    return try {
        (if (exact) mapKeyOrder(level) else termOrder(level)).compare(left, right)
    } catch (_: IllegalArgumentException) {
        decline("compares %s and %s", left, right)
    }
}

/** [term], declining where it is unknown ([isUnknown]), before its type or value is tested. */
internal fun seen(term: OtpErlangObject): OtpErlangObject = if (isUnknown(term)) decline("an unknown value") else term

internal fun truthy(term: OtpErlangObject): Boolean {
    if (term === Unknown) decline("an unknown value")

    return term != FALSE && term != NIL
}

/** An `{M, F, ArgsOrArity, []}` stacktrace entry. */
internal fun frame(module: String, function: String, argsOrArity: OtpErlangObject): OtpErlangTuple =
    tuple(atom(module), atom(function), argsOrArity, EMPTY)
