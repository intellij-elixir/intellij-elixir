package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_SIZE_IN_MAP_KEY_PATTERN
import org.elixir_lang.language_level.ElixirLanguageFeature.PIN_IN_MAP_KEY_PATTERN
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/** `elixir_map:expand_map/4`. */
internal fun expandMap(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion =
    expandMapPairs(node, state, env, run).withValue(NODE)

/** `elixir_map:expand_map/4`, whose value is the list of the expanded pairs, those after the `|` in an update. */
internal fun expandMapPairs(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val args = node.arguments!!
    val update = mapUpdate(node)

    return when {
        update == null ->
            expandAndValidate(node, args, emptyList(), state, env, run) { asts ->
                asts.map { ElixirAst.Call(node.meta, node.callee, (it as ElixirAst.ListNode).elements) }
            }
        env.context != Env.Context.NONE -> Expansion.Error("update_syntax_in_wrong_context", node)
        else -> {
            val (map, pairs) = update.arguments!!

            if (pairs is ElixirAst.ListNode) {
                expandAndValidate(node, pairs.elements, listOf(map), state, env, run) { asts ->
                    asts.map {
                        val (expandedMap, expandedPairs) = (it as ElixirAst.ListNode).elements.let { all -> all.first() to all.drop(1) }
                        val bar = ElixirAst.Call(update.meta, update.callee, listOf(expandedMap, ElixirAst.ListNode(pairs.meta, expandedPairs)))

                        ElixirAst.Call(node.meta, node.callee, listOf(bar))
                    }
                }
            } else {
                Expansion.Unported(node)
            }
        }
    }
}

/** The `|` of [map], `%{map | pairs}`, or `null` for a map that isn't an update. */
internal fun mapUpdate(map: ElixirAst.Call): ElixirAst.Call? =
    map.arguments!!.singleOrNull()?.takeIf { isCall(it, "|", 2) } as ElixirAst.Call?

/**
 * [before], then [kv], expanded, and [kv] validated as expanded. Outside a pattern no key is checked and a written
 * tuple keeps its size, so only a pattern, or an argument that might expand to a pair, asks for the AST.
 */
private inline fun expandAndValidate(
    node: ElixirAst,
    kv: List<ElixirAst>,
    before: List<ElixirAst>,
    state: ExState,
    env: Env,
    run: Run,
    ast: (ExpandedAst) -> ExpandedAst,
): Expansion {
    val args = before + kv
    val asked = env.context == Env.Context.MATCH || kv.any { it !is ElixirAst.Tuple }
    val expansion = if (asked) run.askingAst { expandArgs(args, state, env, run) } else expandArgs(args, state, env, run)

    return expansion.thenAst { s, e, values, asts ->
        if (asked && asts !is ExpandedAst.Built) return Expansion.Unported(node)

        val pairs = Term.List((values as Term.List).elements.drop(before.size))
        val pairNodes = if (asked) asts.elements.drop(before.size) else kv

        pairs.elements.zip(pairNodes).firstNotNullOfOrNull { (pair, at) -> unknownStop(pair, at) }?.let { return it }

        kvError(pairNodes, env, run.level)?.let { Expansion.Error(it, node) } ?: Expansion.Expanded(s, e, pairs, ast(asts))
    }
}

/**
 * The error `elixir_map:validate_kv/4` raises, if any: each argument must be a pair, and in a pattern each key must be
 * one a pattern may have, and no literal key may repeat.
 */
private fun kvError(args: List<ElixirAst>, env: Env, level: ElixirLanguageLevel): String? {
    val context = env.context
    val used = mutableSetOf<OtpErlangObject>()

    for (arg in args) {
        ProgressManager.checkCanceled()

        val pair = expandedShape(arg) as? ElixirAst.Tuple
        if (pair == null || pair.elements.size != 2) return "not_kv_pair"

        val key = expandedShape(pair.elements[0])

        if (!PIN_IN_MAP_KEY_PATTERN.isSufficient(level) && isCall(key, "^", 1)) continue

        if (context == Env.Context.MATCH) matchKeyError(key, level)?.let { return it }

        // Outside a pattern a repeated key only warns.
        if (isLiteral(key) && !used.add(literalShape(pair.elements[0], env, level).toOtp()) &&
            context == Env.Context.MATCH
        ) {
            return "repeated_key"
        }
    }

    return null
}

/** The error `elixir_map:validate_match_key/3` raises for a key in a pattern, if any. */
private fun matchKeyError(node: ElixirAst, level: ElixirLanguageLevel): String? {
    ProgressManager.checkCanceled()

    val key = expandedShape(node)

    return when {
        // What the expander holds of a value it can't read: the value itself is escaped, so holds no variable.
        isCall(key, "@", 1) || isVariableNamed(key, "__DIR__") -> null
        isVariable(key) -> "invalid_variable_in_map_key_match"
        isCall(key, "::", 2) && BITSTRING_SIZE_IN_MAP_KEY_PATTERN.isSufficient(level) ->
            matchKeyError((key as ElixirAst.Call).arguments!!.first(), level)
        isCall(key, "::", 2) -> {
            val (value, spec) = (key as ElixirAst.Call).arguments!!

            matchKeyError(value, level) ?: sizeAndUnitArguments(spec).firstNotNullOfOrNull { matchKeyError(it, level) }
        }
        isCall(key, "^", 1) && isVariable((key as ElixirAst.Call).arguments!!.single()) ->
            if (PIN_IN_MAP_KEY_PATTERN.isSufficient(level)) null else "invalid_pin_in_map_key_match"
        isMap(key) -> null
        key is ElixirAst.Call ->
            matchKeyError(key.callee, level) ?: key.arguments.orEmpty().firstNotNullOfOrNull { matchKeyError(it, level) }
        key is ElixirAst.Alias -> key.segments.firstNotNullOfOrNull { matchKeyError(it, level) }
        key is ElixirAst.Block -> key.expressions.firstNotNullOfOrNull { matchKeyError(it, level) }
        key is ElixirAst.Tuple -> key.elements.firstNotNullOfOrNull { matchKeyError(it, level) }
        key is ElixirAst.ListNode -> key.elements.firstNotNullOfOrNull { matchKeyError(it, level) }
        else -> null
    }
}

/** `elixir_map:is_literal/1`, on the expanded key. */
internal fun isLiteral(node: ElixirAst): Boolean {
    ProgressManager.checkCanceled()

    val key = expandedShape(node)

    return when {
        key.hasMetadata() -> false
        key is ElixirAst.Tuple -> key.elements.all(::isLiteral)
        key is ElixirAst.ListNode -> key.elements.all(::isLiteral)
        else -> true
    }
}

/** A literal key as its expansion in [env] is, at every depth, so equal keys give equal terms. */
internal fun literalShape(node: ElixirAst, env: Env, level: ElixirLanguageLevel): ElixirAst {
    ProgressManager.checkCanceled()

    return when (val key = expandedShape(node)) {
        is ElixirAst.Tuple -> ElixirAst.Tuple(key.meta, key.elements.map { literalShape(it, env, level) })
        is ElixirAst.ListNode -> ElixirAst.ListNode(key.meta, key.elements.map { literalShape(it, env, level) })
        else ->
            (withoutBlocks(node) as? ElixirAst.Alias)
                ?.let { aliasesModule(it, env, level) }
                ?.let { ElixirAst.Literal.Atom(key.meta, it) }
                ?: key
    }
}

private fun withoutBlocks(node: ElixirAst): ElixirAst {
    ProgressManager.checkCanceled()

    return if (node is ElixirAst.Block && node.expressions.size == 1) withoutBlocks(node.expressions.single()) else node
}
