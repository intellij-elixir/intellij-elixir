package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.ANNOTATE_CASE
import org.elixir_lang.language_level.ElixirLanguageFeature.BOOLEAN_CHECK_ERROR_GENERATED
import org.elixir_lang.language_level.ElixirLanguageFeature.FALSE_OR_NIL_INLINE
import org.elixir_lang.language_level.ElixirLanguageFeature.UNLESS_DIRECT_CASE
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta

/** `Kernel.if/2`. */
internal val IF = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val (condition, clauses) = node.arguments!!
        val (doClause, elseClause) = doElse(node, clauses) ?: return Summary.Output.Raised("invalid_if_keys")
        val s = Synthetic(node.meta)

        return Summary.Output.Built(truthCase(s, "if", true, condition, elseClause, doClause, run.level))
    }
}

/** `Kernel.unless/2`. */
internal val UNLESS = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val (condition, clauses) = node.arguments!!
        val (doClause, elseClause) = doElse(node, clauses) ?: return Summary.Output.Raised("invalid_if_keys")
        val s = Synthetic(node.meta)
        val level = run.level

        return Summary.Output.Built(
            if (UNLESS_DIRECT_CASE.isSufficient(level)) {
                truthCase(s, "unless", true, condition, doClause, elseClause, level)
            } else {
                s.call(
                    "if",
                    listOf(condition, s.keywords(listOf("do" to elseClause, "else" to doClause))),
                    kernelImportKeys(level, 2),
                )
            },
        )
    }
}

/** `Kernel.&&/2`. */
internal val AND_AND = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        assertNoMatchOrGuardScope(env)?.let { return it }

        val (left, right) = node.arguments!!
        val s = Synthetic(node.meta)

        return Summary.Output.Built(truthCase(s, "&&", false, left, x(s), right, run.level))
    }
}

/** `Kernel.||/2`. */
internal val OR_OR = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        assertNoMatchOrGuardScope(env)?.let { return it }

        val (left, right) = node.arguments!!
        val s = Synthetic(node.meta)
        val level = run.level

        return Summary.Output.Built(
            s.case(
                left,
                listOf(s.arrow(listOf(falseOrNil(s, level)), right), s.arrow(listOf(x(s)), x(s))),
                annotation(level, "||", optimizeBoolean = false),
            ),
        )
    }
}

/** `Kernel.!/1`: `!!value` is one clause of its own. */
internal val NOT = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        assertNoMatchOrGuardScope(env)?.let { return it }

        val argument = node.arguments!!.single()
        val s = Synthetic(node.meta)

        return Summary.Output.Built(
            if (isCall(argument, "!", 1)) {
                truthCase(s, "!!", true, (argument as ElixirAst.Call).arguments!!.single(), s.atom("false"), s.atom("true"), run.level)
            } else {
                truthCase(s, "!", true, argument, s.atom("true"), s.atom("false"), run.level)
            },
        )
    }
}

/** `Kernel.and/2`. */
internal val AND = booleanOperator("and", "andalso")

/** `Kernel.or/2`. */
internal val OR = booleanOperator("or", "orelse")

/** `and/2` or `or/2`, by context: a `case` checking a boolean; `invalid_match!`; `:erlang.[guard]/2`. */
private fun booleanOperator(operator: String, guard: String) = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val (left, right) = node.arguments!!
        val s = Synthetic(node.meta)

        return when (env.context) {
            Env.Context.NONE -> {
                val (trueClause, falseClause) = if (operator == "or") s.atom("true") to right else right to s.atom("false")

                Summary.Output.Built(buildBooleanCheck(s, operator, left, trueClause, falseClause, run.level))
            }
            Env.Context.MATCH -> Summary.Output.Raised("kernel_invalid_match")
            Env.Context.GUARD -> Summary.Output.Built(s.remoteCall("erlang", guard, listOf(left, right)))
        }
    }
}

/** `build_boolean_check/4`. */
private fun buildBooleanCheck(
    s: Synthetic,
    operator: String,
    check: ElixirAst,
    trueClause: ElixirAst,
    falseClause: ElixirAst,
    level: ElixirLanguageLevel,
): ElixirAst {
    val generated = if (BOOLEAN_CHECK_ERROR_GENERATED.isSufficient(level)) GENERATED else emptyList()
    val other = s.variable("other", KERNEL, generated)
    val error = s.remoteCall("erlang", "error", listOf(s.tuple(listOf(s.atom("badbool"), s.atom(operator), other), generated)), generated)

    return s.case(
        check,
        listOf(
            s.arrow(listOf(s.atom("false")), falseClause),
            s.arrow(listOf(s.atom("true")), trueClause),
            s.arrow(listOf(other), error, generated),
        ),
        annotation(level, operator, optimizeBoolean = true),
    )
}

/** `case Subject do x when <x is false or nil> -> Falsy; _ -> Truthy end`, annotated for [operator]. */
private fun truthCase(
    s: Synthetic,
    operator: String,
    optimizeBoolean: Boolean,
    subject: ElixirAst,
    falsy: ElixirAst,
    truthy: ElixirAst,
    level: ElixirLanguageLevel,
): ElixirAst =
    s.case(
        subject,
        listOf(s.arrow(listOf(falseOrNil(s, level)), falsy), s.arrow(listOf(s.variable("_", KERNEL)), truthy)),
        annotation(level, operator, optimizeBoolean),
    )

/**
 * The `case`'s metadata: from [ANNOTATE_CASE], `annotate_case/2`'s `type_check` (`{:case, operator}` from
 * [UNLESS_DIRECT_CASE], `:expr` before), after `optimize_boolean` where [optimizeBoolean]; before it,
 * `optimize_boolean/1`'s alone.
 */
private fun annotation(level: ElixirLanguageLevel, operator: String, optimizeBoolean: Boolean): List<Meta.Key> {
    val optimize = if (optimizeBoolean) listOf(entry("optimize_boolean", "true")) else emptyList()

    return if (ANNOTATE_CASE.isSufficient(level)) {
        val typeCheck = if (UNLESS_DIRECT_CASE.isSufficient(level)) {
            Meta.Value.Tuple(listOf(Meta.Value.Atom("case"), Meta.Value.Atom(operator)))
        } else {
            Meta.Value.Atom("expr")
        }

        optimize + Meta.Key.Entry("type_check", typeCheck)
    } else {
        optimize
    }
}

/**
 * `x when <x is false or nil>`: from [FALSE_OR_NIL_INLINE], `x_is_false_or_nil/0`'s generated
 * `:erlang.orelse(:erlang.=:=(x, false), :erlang.=:=(x, nil))`; before it, `Kernel.in(x, [false, nil])`.
 */
private fun falseOrNil(s: Synthetic, level: ElixirLanguageLevel): ElixirAst {
    val guard = if (FALSE_OR_NIL_INLINE.isSufficient(level)) {
        val x = s.variable("x", KERNEL, GENERATED)

        fun isAtom(atom: String) = s.remoteCall("erlang", "=:=", listOf(x, s.atom(atom)), GENERATED)

        s.remoteCall("erlang", "orelse", listOf(isAtom("false"), isAtom("nil")), GENERATED)
    } else {
        s.remoteCall(KERNEL, "in", listOf(x(s), s.list(s.atom("false"), s.atom("nil"))))
    }

    return s.call("when", listOf(x(s), guard))
}

private fun x(s: Synthetic) = s.variable("x", KERNEL)

/** `build_if/2`'s and `build_unless/2`'s keys: the `do` and the `else`, `nil` by default, or `null` for others. */
private fun doElse(node: ElixirAst.Call, clauses: ElixirAst): Pair<ElixirAst, ElixirAst>? {
    val options = (clauses as? ElixirAst.ListNode)?.elements ?: return null
    val keys = options.map(::keyOf)
    val values = options.map { option -> (option as ElixirAst.Tuple).takeIf { keyOf(it) != null }?.elements?.get(1) }

    return when (keys) {
        listOf("do") -> values[0]!! to Synthetic(node.meta).atom("nil")
        listOf("do", "else") -> values[0]!! to values[1]!!
        else -> null
    }
}

/** `assert_no_match_or_guard_scope/2`: the raise in a match or a guard, or `null` elsewhere. */
internal fun assertNoMatchOrGuardScope(env: Env): Summary.Output? =
    when (env.context) {
        Env.Context.MATCH -> Summary.Output.Raised("kernel_invalid_match")
        Env.Context.GUARD -> Summary.Output.Raised("kernel_invalid_guard")
        Env.Context.NONE -> null
    }
