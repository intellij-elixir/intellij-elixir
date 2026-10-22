package org.elixir_lang.expander.interpreter

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangBitstr
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangExternalFun
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
import org.elixir_lang.expander.UnorderedTerms
import org.elixir_lang.expander.termOrder
import org.elixir_lang.expander.termsEqual
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.fitsAnAtom
import org.elixir_lang.psi.Import.Term
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap

/** The evaluator can't follow the macro body any further, for [reason]. */
internal class Declined(val reason: String) : RuntimeException(reason, null, false, false) {
    /** The modules being compiled that the calls it declined in were given, any of which those calls may have written. */
    val given = Given()

    /**
     * What the value it didn't give may hold: an unknown value it declined on that came from a module being compiled
     * ([unknownOf]), the arguments a native that declined can return, and the other parts of a value it was a part of.
     */
    val holds = ArrayList<OtpErlangObject>()

    /** Whether code after it raised ([InTurn]), which ends whatever holds it, as Elixir's raise would. */
    var ended = false
        private set

    /** The raise that [ended] it, where the evaluator ran it: a `rescue` or `catch` clause can take it. */
    var endedBy: Thrown? = null
        private set

    /** It [ended], by [raise]. */
    fun end(raise: Thrown?) {
        ended = true
        endedBy = raise
    }

    /** A clause took the raise that [ended] it, so it ends nothing. */
    fun caught() {
        ended = false
        endedBy = null
    }
}

/**
 * The modules being compiled that calls were given: each whose atom an argument is or holds, or whose function an
 * external fun it holds is, and [every] one where an argument is or holds a [ModuleUnknown]. Any other unknown value,
 * which didn't come from a module, gives none.
 */
internal class Given {
    val modules = HashSet<String>()

    var every = false
        private set

    /** Every module being compiled: a value that counts as giving them ([ModuleUnknown]) couldn't be produced. */
    fun all() {
        every = true
    }

    // Each term walked, by identity, so one that several calls a decline left share is walked once.
    private val walked: MutableSet<OtpErlangObject> = Collections.newSetFromMap(IdentityHashMap())

    /** Adds what [other] was given. */
    fun add(other: Given) {
        every = every || other.every
        modules += other.modules
    }

    /** Whether [module] is one of these. */
    operator fun contains(module: String) = every || module in modules

    /** Each of [compiling], by name, that these are. */
    fun <T> of(compiling: Map<String, T>): Collection<T> = if (every) compiling.values else modules.mapNotNull(compiling::get)

    /** Adds the modules of [compiling] that [args], a call's, give it. */
    fun add(args: List<OtpErlangObject>, compiling: Set<String>) {
        if (compiling.isEmpty()) return

        anyHeld(args, walked) { term ->
            when (term) {
                ModuleUnknown -> every = true
                is OtpErlangAtom -> term.atomValue().takeIf { it in compiling }?.let(modules::add)
                is OtpErlangExternalFun -> term.module.takeIf { it in compiling }?.let(modules::add)
                else -> {}
            }

            every
        }
    }
}

/**
 * Whether [visit] gives `true` for a term [terms] hold, each walked once in [walked]: the terms themselves, a
 * [Holding]'s parts, a [Closure]'s [marks][Closure.marks], and the elements of a tuple, list or map.
 */
internal inline fun anyHeld(
    terms: List<OtpErlangObject>,
    walked: MutableSet<OtpErlangObject> = Collections.newSetFromMap(IdentityHashMap()),
    visit: (OtpErlangObject) -> Boolean,
): Boolean {
    val pending = ArrayDeque(terms)

    while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()

        val term = pending.removeLast()

        if (!walked.add(term)) continue
        if (visit(term)) return true

        when (term) {
            is Holding -> pending.addAll(term.parts)
            is Closure -> pending.addAll(term.marks())
            is OtpErlangTuple -> pending.addAll(term.elements())
            is OtpErlangList -> {
                pending.addAll(term.elements())
                term.lastTail?.let(pending::add)
            }
            is OtpErlangMap -> {
                pending.addAll(term.keys())
                pending.addAll(term.values())
            }
            else -> {}
        }
    }

    return false
}

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

/** A value the evaluator doesn't know, of any type: an [Unknown], a [Holding] or a [ModuleUnknown]. */
internal sealed class UnknownValue : OtpErlangObject() {
    override fun encode(buf: OtpOutputStream) = decline("an unknown value has no external form")

    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)

    override fun toString() = "#Unknown"
}

/** A value the evaluator doesn't know that came from no module, such as an attribute's map. */
internal object Unknown : UnknownValue() {
    // Java serialization calls it.
    @Suppress("unused")
    private fun readResolve(): Any = Unknown
}

/**
 * A value the evaluator doesn't know that came from a module being compiled, as a `__CALLER__` or `__ENV__` field
 * [Env][org.elixir_lang.expander.Env] doesn't hold does: it may be or hold any of them ([Given]).
 */
internal object ModuleUnknown : UnknownValue() {
    // Java serialization calls it.
    @Suppress("unused")
    private fun readResolve(): Any = ModuleUnknown
}

/** A value the evaluator doesn't know, built from [parts] it does, any of which may be or hold a module being compiled. */
internal class Holding(val parts: List<OtpErlangObject>) : UnknownValue()

/** A value the evaluator doesn't know, built from [parts]: it came from a module being compiled where one of them did. */
internal fun unknownOf(parts: List<OtpErlangObject>): UnknownValue =
    when {
        parts.any { it === ModuleUnknown } -> ModuleUnknown
        parts.all { it === Unknown || it === UnknownBinary } -> Unknown
        else -> Holding(parts)
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

/** Whether [term] is an [UnknownValue] or [UnknownBinary]. */
internal fun isUnknown(term: OtpErlangObject) = term is UnknownValue || term === UnknownBinary

/**
 * An `fn`, or a capture, that [invoke]s its clauses with [arity] arguments, and the values it holds ([marks]), so a call
 * given it is given them ([Given]).
 */
internal class Closure(
    val arity: Int,
    val marks: () -> List<OtpErlangObject> = { emptyList() },
    val invoke: (List<OtpErlangObject>) -> OtpErlangObject,
) :
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

/**
 * `[heads | tail]`, unknown where [tail] is: how many cells follow, and whether the list is proper, aren't known, but it
 * holds [heads].
 */
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
        is UnknownValue, UnknownBinary -> decline("an unknown value")
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
        is UnknownValue -> unknown(term)
        else -> null
    }

/** The elements of the proper list [term], or `null` for any other term. */
internal fun properElements(term: OtpErlangObject): List<OtpErlangObject>? =
    cells(term)?.takeIf { it.second == null }?.first

internal fun isNumber(term: OtpErlangObject) = term is OtpErlangLong || term is OtpErlangDouble

/** Erlang's `==` where [exact] is false, else `=:=` ([termsEqual]); declines on terms whose order only the VM knows. */
internal fun equal(left: OtpErlangObject, right: OtpErlangObject, exact: Boolean, level: ElixirLanguageLevel): Boolean =
    ordered(left, right, true) { termsEqual(left, right, exact, level) }

internal fun compare(
    left: OtpErlangObject,
    right: OtpErlangObject,
    level: ElixirLanguageLevel,
    exact: Boolean = false,
): Int = ordered(left, right, 0) { termOrder(level, exact).compare(left, right) }

/** [answer] for [left] and [right], [same] where they are one known term; declines where only the VM orders them. */
private inline fun <T> ordered(left: OtpErlangObject, right: OtpErlangObject, same: T, answer: () -> T): T {
    if (left === right && !isUnknown(left)) return same

    known(left)
    known(right)

    return try {
        answer()
    } catch (_: UnorderedTerms) {
        decline("compares %s and %s", left, right)
    }
}

/** [term], declining where it is unknown ([isUnknown]), before its type or value is tested. */
internal fun seen(term: OtpErlangObject): OtpErlangObject = if (isUnknown(term)) unknown(term) else term

/** Declines on [term], an unknown value, keeping it where it came from a module being compiled ([Declined.holds]). */
private fun unknown(term: OtpErlangObject): Nothing =
    throw Declined("an unknown value").apply { if (term is Holding || term === ModuleUnknown) holds += term }

internal fun truthy(term: OtpErlangObject): Boolean {
    if (term is UnknownValue) unknown(term)

    return term != FALSE && term != NIL
}

/**
 * [value] as the expander's [Term]: a float is [Term.NonTuple], and what a [Term] can't hold, an unknown value
 * included, is [Term.Unexpanded], as is a value too large to copy ([copyable]).
 */
internal fun termOf(value: OtpErlangObject): Term = if (copyable(value)) copiedTerm(value) else Term.Unexpanded

private fun copiedTerm(value: OtpErlangObject): Term {
    ProgressManager.checkCanceled()

    return when (value) {
        is OtpErlangAtom -> Term.Atom(value.atomValue())
        is OtpErlangLong -> Term.Integer(value.bigIntegerValue())
        is OtpErlangDouble -> Term.NonTuple
        is OtpErlangBinary -> Term.Binary(value.binaryValue())
        UnknownBinary -> Term.Binary(null)
        is OtpErlangString ->
            Term.List(value.stringValue().codePoints().toArray().map { Term.Integer(BigInteger.valueOf(it.toLong())) })
        is OtpErlangList -> Term.List(value.elements().map(::copiedTerm), value.lastTail?.let(::copiedTerm))
        is OtpErlangTuple ->
            value.elements().map(::copiedTerm).let { if (it.size == 2) Term.Pair(it[0], it[1]) else Term.Tuple(it) }
        else -> Term.Unexpanded
    }
}

/**
 * Whether a copy of [value], part by part, holds at most [COPY_NODES] nodes, each of its shared parts counted each time
 * it appears and a string's code points one each. The evaluator shares a value's parts, as the VM does, so a value it
 * holds in a few nodes can take more than the IDE's heap once every part is copied.
 */
internal fun copyable(value: OtpErlangObject): Boolean {
    var nodes = 0

    fun fits(value: OtpErlangObject): Boolean {
        ProgressManager.checkCanceled()

        nodes += if (value is OtpErlangString) 1 + value.stringValue().let { it.codePointCount(0, it.length) } else 1

        return nodes <= COPY_NODES &&
            when (value) {
                is OtpErlangList -> value.elements().all { fits(it) } && value.lastTail?.let { fits(it) } != false
                is OtpErlangTuple -> value.elements().all { fits(it) }
                else -> true
            }
    }

    return fits(value)
}

/** The most nodes a value [copyable] allows. */
private const val COPY_NODES = 1 shl 20

/** An `{M, F, ArgsOrArity, []}` stacktrace entry. */
internal fun frame(module: String, function: String, argsOrArity: OtpErlangObject): OtpErlangTuple =
    tuple(atom(module), atom(function), argsOrArity, EMPTY)
