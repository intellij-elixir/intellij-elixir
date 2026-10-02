package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangObject
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_SIZE_IN_MAP_KEY_PATTERN
import org.elixir_lang.language_level.ElixirLanguageFeature.PIN_IN_MAP_KEY_PATTERN
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/** `elixir_map:expand_map/4`. */
internal fun expandMap(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val args = node.arguments!!
    val update = args.singleOrNull()?.takeIf { isCall(it, "|", 2) } as ElixirAst.Call?

    return when {
        update == null -> expandArgs(args, state, env, run).then { s, e -> validated(node, args, s, e, run.level) }
        env.context != Env.Context.NONE -> Expansion.Error("update_syntax_in_wrong_context", node)
        else -> {
            val (map, pairs) = update.arguments!!

            if (pairs is ElixirAst.ListNode) {
                expandArgs(listOf(map) + pairs.elements, state, env, run).then { s, e ->
                    validated(node, pairs.elements, s, e, run.level)
                }
            } else {
                Expansion.Unported(node)
            }
        }
    }
}

private fun validated(node: ElixirAst, kv: List<ElixirAst>, state: ExState, env: Env, level: ElixirLanguageLevel) =
    kvError(kv, env, level)?.let { Expansion.Error(it, node) } ?: Expansion.Expanded(state, env, NODE)

/**
 * The error `elixir_map:validate_kv/4` raises, if any: each argument must be a pair, and in a pattern each key must be
 * one a pattern may have, and no literal key may repeat.
 */
private fun kvError(args: List<ElixirAst>, env: Env, level: ElixirLanguageLevel): String? {
    val context = env.context
    val used = mutableSetOf<OtpErlangObject>()

    for (arg in args) {
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
    val key = expandedShape(node)

    return when {
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
    val key = expandedShape(node)

    return when {
        key.hasMetadata() -> false
        key is ElixirAst.Tuple -> key.elements.all(::isLiteral)
        key is ElixirAst.ListNode -> key.elements.all(::isLiteral)
        else -> true
    }
}

/** A literal key as its expansion in [env] is, at every depth, so equal keys give equal terms. */
internal fun literalShape(node: ElixirAst, env: Env, level: ElixirLanguageLevel): ElixirAst =
    when (val key = expandedShape(node)) {
        is ElixirAst.Tuple -> ElixirAst.Tuple(key.meta, key.elements.map { literalShape(it, env, level) })
        is ElixirAst.ListNode -> ElixirAst.ListNode(key.meta, key.elements.map { literalShape(it, env, level) })
        else ->
            (withoutBlocks(node) as? ElixirAst.Alias)
                ?.let { aliasesModule(it, env, level) }
                ?.let { ElixirAst.Literal.Atom(key.meta, it) }
                ?: key
    }

private fun withoutBlocks(node: ElixirAst): ElixirAst =
    if (node is ElixirAst.Block && node.expressions.size == 1) withoutBlocks(node.expressions.single()) else node
