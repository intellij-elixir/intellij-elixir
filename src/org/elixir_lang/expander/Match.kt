package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.expander.ExState.Prematch.Dependency
import org.elixir_lang.expander.ExState.Prematch.InMatch
import org.elixir_lang.expander.ExState.Write
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import java.math.BigDecimal

/**
 * `elixir_clauses:match/6`: [pattern] expanded as a pattern, after the right side took [before] to [after].
 *
 * @param at the match, which a recursive pattern reports
 */
internal fun match(
    pattern: ElixirAst,
    after: ExState,
    before: ExState,
    env: Env,
    run: Run,
    at: ElixirAst,
): Expansion = match(after, before, env, at) { state, matchEnv -> Expander.expand(pattern, state, matchEnv, run) }

/** `elixir_clauses:match/6` with [expand] as its `Fun`, given the state and env the pattern starts from. */
internal fun match(
    after: ExState,
    before: ExState,
    env: Env,
    at: ElixirAst,
    expand: (ExState, Env) -> Expansion,
): Expansion {
    val callState = after.copy(prematch = InMatch(before.read, after.version, emptyMap(), emptyList()))

    return expand(callState, env.copy(context = Env.Context.MATCH)).thenValue { state, patternEnv, value ->
        if (isCyclic(state.prematch as InMatch)) {
            Expansion.Error("recursive", at)
        } else {
            Expansion.Expanded(state.copy(prematch = before.prematch), patternEnv.copy(context = env.context), value)
        }
    }
}

/**
 * `elixir_clauses:parallel_match/4`: each side of a match inside a pattern, left to right, with its own write half, so
 * the variables each side binds feed the cycle check.
 */
internal fun parallelMatch(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion {
    val writes = mutableListOf<Map<Variable, Int>>()

    return mapfold(unpackMatch(node, emptyList()), state, env) { side, sideState, sideEnv ->
        Expander.expand(side, sideState.copy(write = Write.Writing(emptyMap())), sideEnv, run).then { s, e ->
            writes.add((s.write as Write.Writing).vars)
            Expansion.Expanded(s, e, NODE)
        }
    }.then { matched, matchedEnv ->
        val prematch = matched.prematch as InMatch
        val stored = storeCycles(writes.asReversed(), prematch)
        val write = when (val outer = state.write) {
            Write.NotWriting -> Write.NotWriting
            is Write.Writing -> Write.Writing(ExState.mergeVars(outer.vars, stored.writes))
        }

        Expansion.Expanded(
            matched.copy(write = write, prematch = prematch.copy(cycles = stored.cycles, skip = stored.skip)),
            matchedEnv,
            NODE,
        )
    }
}

/**
 * `elixir_expand:refute_parallel_bitstring_match/4`, before 1.18: two bitstrings matched in parallel, as the sides of
 * a `=` inside a pattern are, raise at the right one.
 */
internal fun refuteParallelBitstringMatch(
    left: ElixirAst,
    right: ElixirAst,
    parallel: Boolean,
    state: ExState,
    env: Env,
    level: ElixirLanguageLevel,
): Expansion =
    parallelBitstring(expandedShape(left), expandedShape(right), parallel, env, level)
        ?: Expansion.Expanded(state, env, NODE)

/** The error, or the `Unported`, that matching [left] and [right] in parallel gives, if any. */
private fun parallelBitstring(
    left: ElixirAst,
    right: ElixirAst,
    parallel: Boolean,
    env: Env,
    level: ElixirLanguageLevel,
): Expansion? {
    fun each(lefts: List<ElixirAst>, rights: List<ElixirAst>) =
        lefts.zip(rights).firstNotNullOfOrNull { (l, r) ->
            parallelBitstring(expandedShape(l), expandedShape(r), parallel, env, level)
        }

    return when {
        isBitstring(left) && isBitstring(right) && parallel -> Expansion.Error("parallel_bitstring_match", right)
        isCall(right, "=", 2) -> {
            val (matchLeft, matchRight) = (right as ElixirAst.Call).arguments!!

            parallelBitstring(left, expandedShape(matchLeft), true, env, level)
                ?: parallelBitstring(left, expandedShape(matchRight), parallel, env, level)
        }
        left is ElixirAst.ListNode && right is ElixirAst.ListNode -> each(left.elements, right.elements)
        left is ElixirAst.Tuple && right is ElixirAst.Tuple && (left.elements.size == 2) == (right.elements.size == 2) ->
            each(left.elements, right.elements)
        isMap(left) && isMap(right) -> {
            val leftFields = fields(left)
            val rightFields = fields(right)
            val rightNonLiteral = rightFields.filterNot { (key, _) -> isLiteral(key) }

            // A non-literal key's term carries its metadata, so whether two pair up depends on where they sit.
            if (leftFields.any { (key, value) ->
                    !isLiteral(key) && rightNonLiteral.any { (_, other) ->
                        parallelBitstring(expandedShape(value), expandedShape(other), parallel, env, level) != null
                    }
                }
            ) {
                Expansion.Unported(right)
            } else {
                val rightValues = literalKeyed(rightFields, env, level).toMap()

                // `lists:sort/1` puts the fields in key order.
                literalKeyed(leftFields, env, level)
                    .sortedWith { (key, _), (otherKey, _) -> compareTerms(key, otherKey) }
                    .firstNotNullOfOrNull { (key, value) ->
                        rightValues[key]?.let {
                            parallelBitstring(expandedShape(value), expandedShape(it), parallel, env, level)
                        }
                    }
            }
        }
        else -> null
    }
}

private fun fields(map: ElixirAst): List<Pair<ElixirAst, ElixirAst>> =
    (map as ElixirAst.Call).arguments!!
        .mapNotNull { field -> (expandedShape(field) as? ElixirAst.Tuple)?.elements?.takeIf { it.size == 2 } }
        .map { (key, value) -> key to value }

/** The fields whose keys are literals, by the key's term. */
private fun literalKeyed(
    fields: List<Pair<ElixirAst, ElixirAst>>,
    env: Env,
    level: ElixirLanguageLevel,
): List<Pair<OtpErlangObject, ElixirAst>> =
    fields.filter { (key, _) -> isLiteral(key) }.map { (key, value) -> literalShape(key, env, level).toOtp() to value }

/**
 * Erlang's term order over the terms a literal key can be: numbers, then atoms, tuples, lists and binaries.
 */
private fun compareTerms(left: OtpErlangObject, right: OtpErlangObject): Int {
    val ranks = compareValues(rank(left), rank(right))
    if (ranks != 0) return ranks

    return when (left) {
        is OtpErlangLong, is OtpErlangDouble -> number(left).compareTo(number(right))
        is OtpErlangAtom -> left.atomValue().compareTo((right as OtpErlangAtom).atomValue())
        is OtpErlangTuple -> {
            val other = right as OtpErlangTuple

            compareValues(left.arity(), other.arity()).takeIf { it != 0 } ?: compareElements(left.elements(), other.elements())
        }
        is OtpErlangBinary -> {
            val bytes = left.binaryValue().map { it.toInt() and 0xFF }
            val otherBytes = (right as OtpErlangBinary).binaryValue().map { it.toInt() and 0xFF }

            bytes.zip(otherBytes).firstNotNullOfOrNull { (a, b) -> compareValues(a, b).takeIf { it != 0 } }
                ?: compareValues(bytes.size, otherBytes.size)
        }
        else -> compareElements(elements(left), elements(right))
    }
}

private fun rank(term: OtpErlangObject) =
    when (term) {
        is OtpErlangLong, is OtpErlangDouble -> 0
        is OtpErlangAtom -> 1
        is OtpErlangTuple -> 2
        is OtpErlangList, is OtpErlangString -> 3
        is OtpErlangBinary -> 4
        else -> throw IllegalArgumentException("not a literal key's term: $term")
    }

private fun number(term: OtpErlangObject): BigDecimal =
    when (term) {
        is OtpErlangLong -> BigDecimal(term.bigIntegerValue())
        else -> BigDecimal((term as OtpErlangDouble).doubleValue())
    }

private fun elements(list: OtpErlangObject): Array<OtpErlangObject> =
    when (list) {
        is OtpErlangString -> list.stringValue().codePoints().toArray().map { OtpErlangLong(it.toLong()) }.toTypedArray()
        else -> (list as OtpErlangList).elements()
    }

private fun compareElements(left: Array<OtpErlangObject>, right: Array<OtpErlangObject>): Int =
    left.zip(right).firstNotNullOfOrNull { (a, b) -> compareTerms(a, b).takeIf { it != 0 } }
        ?: compareValues(left.size, right.size)

/** `elixir_clauses:unpack_match/4`: a chain of matches as its sides, left to right. */
private fun unpackMatch(node: ElixirAst, acc: List<ElixirAst>): List<ElixirAst> {
    if (!isCall(node, "=", 2)) return listOf(node) + acc

    val (left, right) = (node as ElixirAst.Call).arguments!!

    return unpackMatch(left, unpackMatch(right, acc))
}

private class StoredCycles(
    val cycles: Map<Variable, Map<Variable, Dependency>>,
    val skip: List<Set<Variable>>,
    val writes: Map<Variable, Int>,
)

/** `elixir_clauses:store_cycles/3`, over [writes] last side first, as `parallel_match` accumulates them. */
private fun storeCycles(writes: List<Map<Variable, Int>>, prematch: InMatch): StoredCycles {
    var cycles = prematch.cycles
    var skip = prematch.skip
    var acc = emptyMap<Variable, Int>()

    writes.forEachIndexed { index, write ->
        val dependsOn = acc.keys + writes.drop(index + 1).flatMap { it.keys }

        cycles = write.keys.fold(cycles) { accCycles, variable ->
            val current = accCycles[variable]
            val updated = if (current == null) {
                dependsOn.associateWith { Dependency.ONE_SIDE }
            } else {
                current.mapValues { (other, dependency) ->
                    if (other in dependsOn) Dependency.REPEATED else dependency
                } + (dependsOn - current.keys).associateWith { Dependency.ONE_SIDE }
            }

            accCycles + (variable to updated)
        }

        if (dependsOn.size > 1) skip = listOf(dependsOn) + skip

        acc = acc + write
    }

    return StoredCycles(cycles, skip, acc)
}

/** Whether `elixir_clauses:validate_cycles/4` raises `recursive`. */
private fun isCyclic(prematch: InMatch): Boolean {
    var seen = emptyMap<Variable, Boolean>()

    for (current in prematch.cycles.keys.sortedWith(TERM_ORDER)) {
        seen = recurCycles(prematch, current, null, seen) ?: return true
    }

    return false
}

/**
 * `elixir_clauses:recur_cycles/8`: [seen] grown by the variables reachable from [current], or `null` where it raises.
 *
 * @param source the variable [current] was reached from, `null` at the root
 */
private fun recurCycles(
    prematch: InMatch,
    current: Variable,
    source: Variable?,
    seen: Map<Variable, Boolean>,
): Map<Variable, Boolean>? {
    if (current in seen) return seen

    val dependsOn = prematch.cycles.getValue(current)
    if (current in dependsOn) return null

    val once = mutableSetOf<Variable>()
    val always = mutableListOf<Variable>()

    for (group in prematch.skip) {
        if (current in group) {
            for (variable in group) {
                if (variable in dependsOn) once.add(variable) else always.add(variable)
            }
        }
    }

    var accSeen = always.associateWith { false } + seen + (current to true)

    for (variable in dependsOn.keys.sortedWith(TERM_ORDER)) {
        when {
            dependsOn.getValue(variable) == Dependency.REPEATED -> return null
            variable == source || variable in once -> Unit
            // The original `seen`, not `accSeen`, as Elixir's guard reads it.
            seen[variable] == true -> return null
            else -> accSeen = recurCycles(prematch, variable, current, accSeen) ?: return null
        }
    }

    return accSeen
}

private val CONTEXT_TERM_ORDER: Comparator<Variable.Context> = compareBy(
    {
        when (it) {
            is Variable.Context.Atom -> 1
            is Variable.Context.Counter -> if (it.counter is Env.Counter.Unique) 0 else 2
        }
    },
    {
        when (it) {
            is Variable.Context.Atom -> it.text
            is Variable.Context.Counter -> (it.counter as? Env.Counter.InModule)?.module
        }
    },
    {
        when (it) {
            is Variable.Context.Atom -> null
            is Variable.Context.Counter -> when (val counter = it.counter) {
                is Env.Counter.InModule -> counter.n
                is Env.Counter.Unique -> counter.n
            }
        }
    },
)

/**
 * Erlang's term order on `{name, context}`, which a small map folds in. A context is an atom, a `{Module, n}` counter, or
 * outside a module an integer counter, and a number sorts before an atom, which sorts before a tuple.
 */
internal val TERM_ORDER: Comparator<Variable> =
    compareBy<Variable> { it.name }.thenComparing({ it.context }, CONTEXT_TERM_ORDER)
