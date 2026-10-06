package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.RANGE_GUARD_STEP_COMPUTED
import org.elixir_lang.language_level.ElixirLanguageFeature.RANGE_NEW_WRAPPED_IN_IDENTITY
import org.elixir_lang.language_level.ElixirLanguageFeature.RANGE_STRUCT_SYNTAX
import org.elixir_lang.language_level.ElixirLanguageFeature.STEP_OPERATOR
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import java.math.BigInteger

/** `Kernel.../2`: both sides expanded, then a literal range, or `Range.new/2` in a body where either isn't an integer. */
internal val RANGE = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val (first, last) = node.arguments!!.map { expandArgument(it, state, env, run) { stopped -> return stopped } }

        if (!areIntegers(first, last)) return Summary.Output.Raised("range_not_integers")

        val s = Synthetic(node.meta)
        val level = run.level

        return Summary.Output.Built(
            if (first is ElixirAst.Literal.Integer && last is ElixirAst.Literal.Integer) {
                val fields = listOf("first" to first, "last" to last)
                val step = if (first.value <= last.value) BigInteger.ONE else BigInteger.ONE.negate()

                range(s, level, if (STEP_OPERATOR.isSufficient(level)) fields + ("step" to s.integer(step)) else fields)
            } else {
                when (env.context) {
                    Env.Context.NONE -> {
                        val new = s.remoteCall(elixirRange(s), "new", listOf(first, last))

                        if (RANGE_NEW_WRAPPED_IN_IDENTITY.isSufficient(level)) {
                            s.remoteCall(kernelAlias(s, "Function"), "identity", listOf(new))
                        } else {
                            new
                        }
                    }
                    Env.Context.GUARD -> {
                        val fields = listOf("first" to first, "last" to last)

                        when {
                            RANGE_GUARD_STEP_COMPUTED.isSufficient(level) -> range(s, level, fields + ("step" to guardStep(s, first, last)))
                            STEP_OPERATOR.isSufficient(level) -> range(s, level, fields + ("step" to s.atom("nil")))
                            else -> range(s, level, fields)
                        }
                    }
                    Env.Context.MATCH -> range(s, level, listOf("first" to first, "last" to last))
                }
            },
        )
    }
}

/** `Kernel...///3`: every side expanded, then a literal range, or `Range.new/3` in a body where any isn't an integer. */
internal val STEP_RANGE = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val (first, last, step) = node.arguments!!.map { expandArgument(it, state, env, run) { stopped -> return stopped } }

        if (!areIntegers(first, last)) return Summary.Output.Raised("range_not_integers")
        if (!isIntegerStep(step)) return Summary.Output.Raised("range_zero_step")

        val s = Synthetic(node.meta)

        return Summary.Output.Built(
            if (env.context != Env.Context.NONE || listOf(first, last, step).all { it is ElixirAst.Literal.Integer }) {
                range(s, run.level, listOf("first" to first, "last" to last, "step" to step))
            } else {
                s.remoteCall(elixirRange(s), "new", listOf(first, last, step))
            },
        )
    }
}

/** `Kernel.../0`: `0..-1//1` in every context. */
internal val FULL_RANGE = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val s = Synthetic(node.meta)
        val fields = listOf(
            "first" to s.integer(BigInteger.ZERO),
            "last" to s.integer(BigInteger.ONE.negate()),
            "step" to s.integer(BigInteger.ONE),
        )

        return Summary.Output.Built(range(s, run.level, fields))
    }
}

/** `validate_range!/2`: neither side a float, atom, binary or list. */
private fun areIntegers(first: ElixirAst, last: ElixirAst): Boolean = !isNotInteger(first) && !isNotInteger(last)

/** `validate_step!/1`: not a float, atom, binary or list, and not `0`. */
private fun isIntegerStep(step: ElixirAst): Boolean =
    !isNotInteger(step) && (step as? ElixirAst.Literal.Integer)?.value != BigInteger.ZERO

private fun isNotInteger(node: ElixirAst): Boolean =
    node is ElixirAst.Literal.Float || node is ElixirAst.Literal.Atom || isBinaryValue(node) || node is ElixirAst.ListNode

/** The literal range of [fields]: a `%Range{}` from [RANGE_STRUCT_SYNTAX], a map with `__struct__` first before. */
private fun range(s: Synthetic, level: ElixirLanguageLevel, fields: List<Pair<String, ElixirAst>>): ElixirAst {
    val pairs = fields.map { (key, value) -> s.tuple(s.atom(key), value) }

    return if (RANGE_STRUCT_SYNTAX.isSufficient(level)) {
        s.call("%", listOf(s.atom("Elixir.Range"), s.call("%{}", pairs)))
    } else {
        s.call("%{}", listOf(s.tuple(s.atom("__struct__"), s.atom("Elixir.Range"))) + pairs)
    }
}

/** `:erlang.map_get(:erlang.>(first, last), %{false: 1, true: -1})`. */
private fun guardStep(s: Synthetic, first: ElixirAst, last: ElixirAst): ElixirAst =
    s.remoteCall(
        "erlang",
        "map_get",
        listOf(
            s.remoteCall("erlang", ">", listOf(first, last)),
            s.call(
                "%{}",
                listOf(
                    s.tuple(s.atom("false"), s.integer(BigInteger.ONE)),
                    s.tuple(s.atom("true"), s.integer(BigInteger.ONE.negate())),
                ),
            ),
        ),
    )

/** `Elixir.Range`, as `Kernel`'s `quote` writes it. */
private fun elixirRange(s: Synthetic): ElixirAst.Alias = ElixirAst.Alias(s.meta(), listOf(s.atom("Elixir"), s.atom("Range")))
