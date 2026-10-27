package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.expander.ExState.Prematch.Dependency
import org.elixir_lang.expander.ExState.Prematch.InMatch
import org.elixir_lang.expander.ExState.Write
import org.elixir_lang.language_level.ElixirLanguageFeature.DUPLICATE_MATCH_UNPACKED_ONCE
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.lowering.ParserOptions

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

    return expand(callState, env.copy(context = Env.Context.MATCH)).thenAst { state, patternEnv, value, ast ->
        if (isCyclic(state.prematch as InMatch)) {
            Expansion.Error("recursive", at)
        } else {
            Expansion.Expanded(state.copy(prematch = before.prematch), patternEnv.copy(context = env.context), value, ast)
        }
    }
}

/**
 * `elixir_clauses:parallel_match/4`: each side of a match inside a pattern, left to right, with its own write half, so
 * the variables each side binds feed the cycle check.
 */
internal fun parallelMatch(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion {
    val writes = mutableListOf<Map<Variable, Int>>()
    val sides = unpackMatch(node, node.meta, emptyList(), run.level)

    return mapfold(sides.map { it.second }, state, env, run) { side, sideState, sideEnv ->
        Expander.expand(side, sideState.copy(write = Write.Writing(emptyMap())), sideEnv, run).thenAst { s, e, _, ast ->
            writes.add((s.write as Write.Writing).vars)
            Expansion.Expanded(s, e, NODE, ast)
        }
    }.thenAst { matched, matchedEnv, _, asts ->
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
            run.ast {
                val callee = (node as ElixirAst.Call).callee
                val built = asts.elements

                built.dropLast(1).zip(sides).foldRight(built.last()) { (side, held), acc ->
                    ElixirAst.Call(held.first, callee, listOf(side, acc))
                }
            },
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
        ?: Expansion.Expanded(state, env, NODE, ExpandedAst.NotAsked)

/** The error, or the `Unported`, that matching [left] and [right] in parallel gives, if any. */
private fun parallelBitstring(
    left: ElixirAst,
    right: ElixirAst,
    parallel: Boolean,
    env: Env,
    level: ElixirLanguageLevel,
): Expansion? {
    ProgressManager.checkCanceled()

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
        // Elixir reads a macro's expansion, which may be a bitstring.
        parallel && isPatternMacro(left, level) && mayHoldBitstring(right, level) -> Expansion.Unported(left)
        parallel && isPatternMacro(right, level) && mayHoldBitstring(left, level) -> Expansion.Unported(right)
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
                    .sortedWith { (key, _), (otherKey, _) -> termOrder(level).compare(key, otherKey) }
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

/**
 * A call other than a special form or a signed number: a macro in a pattern, whose expansion the check reads. A sign
 * on a number is that number in a pattern.
 */
private fun isPatternMacro(node: ElixirAst, level: ElixirLanguageLevel): Boolean {
    val arguments = (node as? ElixirAst.Call)?.arguments ?: return false
    val name = (node.callee as? ElixirAst.Literal.Atom)?.name

    return name == null || !specialForm(name, arguments.size, level) && !isSignedNumber(name, arguments)
}

/** Whether [node] is, or once expanded may be or hold, a bitstring. */
private fun mayHoldBitstring(node: ElixirAst, level: ElixirLanguageLevel): Boolean =
    isBitstring(node) ||
        node is ElixirAst.ListNode ||
        node is ElixirAst.Tuple ||
        isMap(node) ||
        isCall(node, "=", 2) ||
        isPatternMacro(node, level)

private fun fields(map: ElixirAst): List<Pair<ElixirAst, ElixirAst>> =
    (map as ElixirAst.Call).arguments!!
        .mapNotNull { field ->
            ProgressManager.checkCanceled()

            (expandedShape(field) as? ElixirAst.Tuple)?.elements?.takeIf { it.size == 2 }
        }
        .map { (key, value) -> key to value }

/** The fields whose keys are literals, by the key's term. */
private fun literalKeyed(
    fields: List<Pair<ElixirAst, ElixirAst>>,
    env: Env,
    level: ElixirLanguageLevel,
): List<Pair<OtpErlangObject, ElixirAst>> =
    fields.filter { (key, _) -> isLiteral(key) }.map { (key, value) ->
        ProgressManager.checkCanceled()

        literalShape(key, env, level).toOtp() to value
    }

/**
 * `elixir_clauses:unpack_match/4`: a chain of matches as its sides, left to right, each with the metadata of the match
 * that held it, or [meta] for [node] itself. From 1.18.0 a match of a 3-tuple against the same term, metadata and all,
 * is the one side, which Elixir warns of.
 */
private fun unpackMatch(
    node: ElixirAst,
    meta: Meta,
    acc: List<Pair<Meta, ElixirAst>>,
    level: ElixirLanguageLevel,
): List<Pair<Meta, ElixirAst>> {
    ProgressManager.checkCanceled()

    if (!isCall(node, "=", 2)) return listOf(meta to node) + acc

    val (left, right) = (node as ElixirAst.Call).arguments!!

    if (DUPLICATE_MATCH_UNPACKED_ONCE.isSufficient(level)) {
        // The compiler reads the file with columns.
        val options = ParserOptions(columns = true)
        val term = left.toOtp(options)

        if ((term as? OtpErlangTuple)?.arity() == 3 && exactlyEqual(term, right.toOtp(options), level)) {
            return unpackMatch(left, node.meta, acc, level)
        }
    }

    return unpackMatch(left, node.meta, unpackMatch(right, node.meta, acc, level), level)
}

/** `=:=`, where two terms only the VM orders are two different ones. */
private fun exactlyEqual(left: OtpErlangObject, right: OtpErlangObject, level: ElixirLanguageLevel): Boolean =
    try {
        termsEqual(left, right, exact = true, level)
    } catch (_: UnorderedTerms) {
        false
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
        ProgressManager.checkCanceled()

        val dependsOn = acc.keys + writes.drop(index + 1).flatMap { it.keys }

        cycles = write.keys.fold(cycles) { accCycles, variable ->
            ProgressManager.checkCanceled()

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

    for (current in prematch.cycles.keys.sortedWith(VARIABLE_ORDER)) {
        ProgressManager.checkCanceled()

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
    ProgressManager.checkCanceled()

    if (current in seen) return seen

    val dependsOn = prematch.cycles.getValue(current)
    if (current in dependsOn) return null

    val once = mutableSetOf<Variable>()
    val always = mutableListOf<Variable>()

    for (group in prematch.skip) {
        ProgressManager.checkCanceled()

        if (current in group) {
            for (variable in group) {
                if (variable in dependsOn) once.add(variable) else always.add(variable)
            }
        }
    }

    var accSeen = always.associateWith { false } + seen + (current to true)

    for (variable in dependsOn.keys.sortedWith(VARIABLE_ORDER)) {
        ProgressManager.checkCanceled()

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
