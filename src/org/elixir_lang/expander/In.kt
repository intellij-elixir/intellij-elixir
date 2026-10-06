package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.GENERATED_ARGUMENTS_ONE_BASED
import org.elixir_lang.language_level.ElixirLanguageFeature.GUARDS_INFER_TYPES
import org.elixir_lang.language_level.ElixirLanguageFeature.IN_EMPTY_LISTS_MEMBER
import org.elixir_lang.language_level.ElixirLanguageFeature.IN_ENUM_IN
import org.elixir_lang.language_level.ElixirLanguageFeature.IN_LIST_LISTS_MEMBER
import org.elixir_lang.language_level.ElixirLanguageFeature.IN_LITERAL_UNWRAPPED
import org.elixir_lang.language_level.ElixirLanguageFeature.IN_RANGE_FIELDS_ANY_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.IN_RANGE_LITERAL_GENERATED
import org.elixir_lang.language_level.ElixirLanguageFeature.IN_SMALL_LITERAL_LIST
import org.elixir_lang.language_level.ElixirLanguageFeature.RANGE_GUARD_STEP_COMPUTED
import org.elixir_lang.language_level.ElixirLanguageFeature.RANGE_STRUCT_SYNTAX
import org.elixir_lang.language_level.ElixirLanguageFeature.STEP_OPERATOR
import org.elixir_lang.lowering.ElixirAst
import java.math.BigInteger

/** `Kernel.in/2`: the right side expanded, then a list, a literal range or, in a body, `Enum`'s membership test. */
internal val IN = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val (left, right) = node.arguments!!

        return try {
            Summary.Output.Built(In(node, state, env, run).output(left, right))
        } catch (stop: Stop) {
            stop.output
        }
    }
}

/** Where `in/2`'s own `Macro.expand/2` stops, or the macro raises, from inside its recursion. */
private class Stop(val output: Summary.Output) : RuntimeException(null, null, false, false)

/** The first, last and step of a literal range, the step `null` before [STEP_OPERATOR]. */
private class RangeFields(val first: ElixirAst, val last: ElixirAst, val step: ElixirAst?)

private class In(private val node: ElixirAst.Call, private val state: ExState, private val env: Env, private val run: Run) {
    private val s = Synthetic(node.meta)
    private val level = run.level
    private val inBody = env.context == Env.Context.NONE

    fun output(left: ElixirAst, written: ElixirAst): ElixirAst {
        return when (val right = expand(written)) {
            is ElixirAst.ListNode if right.elements.isEmpty() ->
                when {
                    !inBody -> s.atom("false")
                    IN_EMPTY_LISTS_MEMBER.isSufficient(level) -> member(left, s.list())
                    else -> block(s.call("=", listOf(s.variable("_", KERNEL), left)), s.atom("false"))
                }
            is ElixirAst.ListNode -> list(left, written, right)
            else -> {
                val fields = rangeFields(right)

                when {
                    fields != null -> inVar(left) { inRange(it, fields) }
                    !inBody -> throw Stop(Summary.Output.Raised("in_invalid_guard_argument"))
                    IN_ENUM_IN.isSufficient(level) -> s.remoteCall(elixirEnum(), "__in__", listOf(left, right))
                    else -> s.remoteCall(elixirEnum(), "member?", listOf(right, left))
                }
            }
        }
    }

    /**
     * A non-empty [list], [written] before it was expanded: in a guard, a comparison with each element; in a body, by
     * era, the elements bound to variables first, a small literal list compared inline, or `:lists.member/2` of
     * [written].
     */
    private fun list(left: ElixirAst, written: ElixirAst, list: ElixirAst.ListNode): ElixirAst =
        when {
            !inBody -> inList(left, list.elements)
            IN_LIST_LISTS_MEMBER.isSufficient(level) -> member(left, written)
            IN_SMALL_LITERAL_LIST.isSufficient(level) ->
                if (isSmallLiteralList(written)) inVar(left) { inList(it, list.elements) } else member(left, written)
            else -> {
                val bound = mutableListOf<Pair<ElixirAst, ElixirAst>>()
                val evaled = ensureEvaled(list.elements, bound)
                val compared = inVar(left) { inList(it, evaled) }

                if (bound.isEmpty()) {
                    compared
                } else {
                    val match = s.call("=", listOf(s.tuple(bound.map { it.first }, emptyList()), s.tuple(bound.map { it.second }, emptyList())))

                    block(match, compared)
                }
            }
        }

    /** `small_literal_list?/1`: at most 32 elements, each a binary, atom or number, as written. */
    private fun isSmallLiteralList(written: ElixirAst): Boolean =
        written is ElixirAst.ListNode && written.elements.size <= 32 && written.elements.all { it is ElixirAst.Literal }

    /** `ensure_evaled/3`: each element not a literal, and each cons tail not a list, bound to an `argN` in [bound]. */
    private fun ensureEvaled(elements: List<ElixirAst>, bound: MutableList<Pair<ElixirAst, ElixirAst>>): List<ElixirAst> =
        elements.map { element ->
            if (isCall(element, "|", 2)) {
                element as ElixirAst.Call
                val (head, tail) = element.arguments!!
                val evaledHead = ensureEvaledElement(head, bound)
                val expandedTail = expand(tail)
                val evaledTail = if (expandedTail is ElixirAst.ListNode) {
                    s.list(ensureEvaled(expandedTail.elements, bound))
                } else {
                    ensureEvaledVar(expandedTail, bound)
                }

                ElixirAst.Call(element.meta, element.callee, listOf(evaledHead, evaledTail))
            } else {
                ensureEvaledElement(element, bound)
            }
        }

    private fun ensureEvaledElement(element: ElixirAst, bound: MutableList<Pair<ElixirAst, ElixirAst>>): ElixirAst =
        element as? ElixirAst.Literal ?: ensureEvaledVar(element, bound)

    /** `argN`, numbered from 0 before [GENERATED_ARGUMENTS_ONE_BASED] and from 1 after. */
    private fun ensureEvaledVar(element: ElixirAst, bound: MutableList<Pair<ElixirAst, ElixirAst>>): ElixirAst {
        val index = if (GENERATED_ARGUMENTS_ONE_BASED.isSufficient(level)) bound.size + 1 else bound.size
        val variable = s.variable("arg$index", KERNEL)

        bound.add(variable to element)

        return variable
    }

    /** `in_var/3`: [left] as it is in a guard, a variable or, from [IN_LITERAL_UNWRAPPED], a literal; else bound to `var`. */
    private fun inVar(left: ElixirAst, compare: (ElixirAst) -> ElixirAst): ElixirAst =
        when {
            !inBody || isVariable(left) -> compare(left)
            left is ElixirAst.Literal && IN_LITERAL_UNWRAPPED.isSufficient(level) -> compare(left)
            else -> {
                val variable = s.variable("var", KERNEL)

                block(s.call("=", listOf(variable, left)), compare(variable))
            }
        }

    /** `in_list/5`: each element compared, joined left to right by `or`. */
    private fun inList(left: ElixirAst, elements: List<ElixirAst>): ElixirAst =
        elements.map { comp(left, it) }.reduce { joined, compared -> orElse(joined, compared) }

    /** `comp/4`: a cons element compares its head and then its tail, expanded; anything else compares itself. */
    private fun comp(left: ElixirAst, element: ElixirAst): ElixirAst {
        if (!isCall(element, "|", 2)) return strictlyEqual(left, element)

        val (head, tail) = (element as ElixirAst.Call).arguments!!
        val expandedTail = expand(tail)

        return when {
            expandedTail is ElixirAst.ListNode && expandedTail.elements.isEmpty() -> strictlyEqual(left, head)
            expandedTail is ElixirAst.ListNode -> orElse(strictlyEqual(left, head), inList(left, expandedTail.elements))
            inBody -> orElse(strictlyEqual(left, head), member(left, expandedTail))
            else -> throw Stop(Summary.Output.Raised("in_invalid_guard_argument"))
        }
    }

    /** `in_range/3,4`: a literal comparison where the step is an integer, the bounds' signs compared where it isn't. */
    private fun inRange(left: ElixirAst, fields: RangeFields): ElixirAst {
        val first = expand(fields.first)
        val last = expand(fields.last)
        val step = fields.step?.let(::expand)

        return when {
            step == null ->
                if (first is ElixirAst.Literal.Integer && last is ElixirAst.Literal.Integer) {
                    inRangeLiteral(left, first, last, if (first.value < last.value) BigInteger.ONE else BigInteger.ONE.negate())
                } else {
                    nilStepRange(left, first, last)
                }
            step is ElixirAst.Literal.Integer -> inRangeLiteral(left, first, last, step.value)
            !RANGE_GUARD_STEP_COMPUTED.isSufficient(level) && step is ElixirAst.Literal.Atom && step.name == "nil" ->
                nilStepRange(left, first, last)
            else -> {
                val signs = localOr(
                    localAnd(s.remoteCall("erlang", ">", listOf(step, zero())), increasing(left, first, last)),
                    localAnd(s.remoteCall("erlang", "<", listOf(step, zero())), decreasing(left, first, last)),
                )

                inRangeStep(integers(left, first, last, signs), left, first, step)
            }
        }
    }

    /** `in_range/4`'s `nil` step, and 1.11's non-literal range: the direction from comparing [first] and [last]. */
    private fun nilStepRange(left: ElixirAst, first: ElixirAst, last: ElixirAst): ElixirAst {
        val directions = localOr(
            localAnd(s.remoteCall("erlang", "=<", listOf(first, last)), increasing(left, first, last)),
            localAnd(s.remoteCall("erlang", "<", listOf(last, first)), decreasing(left, first, last)),
        )

        return integers(left, first, last, directions)
    }

    /** `is_integer(left) and is_integer(first) and is_integer(last) and [rest]`, with `Kernel`'s quoted `and/2`. */
    private fun integers(left: ElixirAst, first: ElixirAst, last: ElixirAst, rest: ElixirAst): ElixirAst =
        listOf(isInteger(left), isInteger(first), isInteger(last), rest).reduce { joined, next -> localAnd(joined, next) }

    /** `in_range_literal/4`: one bound; or [left] an integer within the bounds in the step's direction. */
    private fun inRangeLiteral(left: ElixirAst, first: ElixirAst, last: ElixirAst, step: BigInteger): ElixirAst {
        if (first is ElixirAst.Literal.Integer && last is ElixirAst.Literal.Integer && first.value == last.value) {
            return strictlyEqual(left, first)
        }

        val compare = when (step.signum()) {
            1 -> increasing(left, first, last)
            -1 -> decreasing(left, first, last)
            else -> throw Stop(Summary.Output.Unported(node))
        }
        val quoted = if (GUARDS_INFER_TYPES.isSufficient(level)) {
            val generated = if (IN_RANGE_LITERAL_GENERATED.isSufficient(level)) GENERATED else emptyList()
            val kernel = ElixirAst.Alias(s.meta(generated + entry("alias", "false")), listOf(s.atom("Kernel")))

            s.remoteCall(kernel, "and", listOf(isInteger(left), compare), generated)
        } else {
            andAlso(s.remoteCall("erlang", "is_integer", listOf(left)), compare)
        }

        return inRangeStep(quoted, left, first, s.integer(step))
    }

    /** `in_range_step/4`: [quoted] alone for a step of 1 or -1; else with `rem(left - first, step) == 0`. */
    private fun inRangeStep(quoted: ElixirAst, left: ElixirAst, first: ElixirAst, step: ElixirAst): ElixirAst {
        val unit = when (step) {
            is ElixirAst.Literal.Integer -> step.value.abs() == BigInteger.ONE
            is ElixirAst.Literal.Float -> step.value == 1.0 || step.value == -1.0
            else -> false
        }

        if (unit) return quoted

        val difference = s.call("-", listOf(left, first), kernelImportKeys(level, 1, 2))
        val remainder = s.remoteCall("erlang", "rem", listOf(difference, step))

        return andAlso(quoted, strictlyEqual(remainder, zero()))
    }

    /** `rangeFields/1` of each era, or `null` where [right] isn't a literal range. */
    private fun rangeFields(right: ElixirAst): RangeFields? {
        val step = STEP_OPERATOR.isSufficient(level)

        return when {
            RANGE_STRUCT_SYNTAX.isSufficient(level) -> {
                val fields = when {
                    isCall(right, "%", 2) -> {
                        val (module, map) = (right as ElixirAst.Call).arguments!!

                        if (isElixirRange(module) && isMap(map)) (map as ElixirAst.Call).arguments!! else return null
                    }
                    isMap(right) -> {
                        val (first, rest) = (right as ElixirAst.Call).arguments!!.let { it.firstOrNull() to it.drop(1) }

                        if (first != null && isStructRange(first)) rest else return null
                    }
                    else -> return null
                }

                anyOrder(fields, listOf("first", "last", "step"))
            }
            !isMap(right) -> null
            IN_RANGE_FIELDS_ANY_ORDER.isSufficient(level) -> {
                val fields = (right as ElixirAst.Call).arguments!!
                val structs = fields.filter { pairKey(it) == "__struct__" }

                if (structs.size == 1 && isStructRange(structs.single())) {
                    anyOrder(fields - structs.single(), listOf("first", "last", "step"))
                } else {
                    if (structs.size > 1) throw Stop(Summary.Output.Unported(right))

                    null
                }
            }
            else -> {
                val fields = (right as ElixirAst.Call).arguments!!
                val keys = listOf("first", "last") + if (step) listOf("step") else emptyList()

                if (fields.size == keys.size + 1 && isStructRange(fields.first()) && fields.drop(1).map(::pairKey) == keys) {
                    val values = fields.drop(1).map { (it as ElixirAst.Tuple).elements[1] }

                    RangeFields(values[0], values[1], values.getOrNull(2))
                } else {
                    null
                }
            }
        }
    }

    /**
     * `:lists.usort/1` of [fields] equal to [keys] with their values: each a pair of one of [keys], in any order. A
     * repeated key is [Summary.Output.Unported], since whether `usort` folds it depends on the values' metadata.
     */
    private fun anyOrder(fields: List<ElixirAst>, keys: List<String>): RangeFields? {
        val byKey = fields.groupBy(::pairKey)

        if (byKey.keys.any { it == null }) return null
        if (byKey.values.any { it.size > 1 }) throw Stop(Summary.Output.Unported(node))
        if (byKey.keys != keys.toSet()) return null

        val values = keys.map { (byKey.getValue(it).single() as ElixirAst.Tuple).elements[1] }

        return RangeFields(values[0], values[1], values[2])
    }

    private fun isStructRange(field: ElixirAst): Boolean =
        pairKey(field) == "__struct__" && isElixirRange((field as ElixirAst.Tuple).elements[1])

    private fun isElixirRange(node: ElixirAst): Boolean = (node as? ElixirAst.Literal.Atom)?.name == "Elixir.Range"

    /** The atom key of a `{key, value}` pair, or `null` for anything else. */
    private fun pairKey(node: ElixirAst): String? =
        ((node as? ElixirAst.Tuple)?.takeIf { it.elements.size == 2 }?.elements?.get(0) as? ElixirAst.Literal.Atom)?.name

    /** `increasing_compare/3`. */
    private fun increasing(left: ElixirAst, first: ElixirAst, last: ElixirAst): ElixirAst =
        andAlso(s.remoteCall("erlang", ">=", listOf(left, first)), s.remoteCall("erlang", "=<", listOf(left, last)))

    /** `decreasing_compare/3`. */
    private fun decreasing(left: ElixirAst, first: ElixirAst, last: ElixirAst): ElixirAst =
        andAlso(s.remoteCall("erlang", "=<", listOf(left, first)), s.remoteCall("erlang", ">=", listOf(left, last)))

    /** `:erlang.is_integer/1`, generated from [GUARDS_INFER_TYPES]. */
    private fun isInteger(node: ElixirAst): ElixirAst =
        s.remoteCall("erlang", "is_integer", listOf(node), if (GUARDS_INFER_TYPES.isSufficient(level)) GENERATED else emptyList())

    /** `Kernel.and/2` from [GUARDS_INFER_TYPES], `:erlang.andalso/2` before. */
    private fun andAlso(left: ElixirAst, right: ElixirAst): ElixirAst = guardOperator("and", "andalso", left, right)

    /** `Kernel.or/2` from [GUARDS_INFER_TYPES], `:erlang.orelse/2` before. */
    private fun orElse(left: ElixirAst, right: ElixirAst): ElixirAst = guardOperator("or", "orelse", left, right)

    private fun guardOperator(kernel: String, erlang: String, left: ElixirAst, right: ElixirAst): ElixirAst =
        if (GUARDS_INFER_TYPES.isSufficient(level)) {
            s.remoteCall(kernelAlias(s, "Kernel"), kernel, listOf(left, right))
        } else {
            s.remoteCall("erlang", erlang, listOf(left, right))
        }

    /** `and/2` as `Kernel`'s `quote` gives a local call to it. */
    private fun localAnd(left: ElixirAst, right: ElixirAst): ElixirAst =
        s.call("and", listOf(left, right), kernelImportKeys(level, 2))

    /** `or/2` as `Kernel`'s `quote` gives a local call to it. */
    private fun localOr(left: ElixirAst, right: ElixirAst): ElixirAst =
        s.call("or", listOf(left, right), kernelImportKeys(level, 2))

    private fun strictlyEqual(left: ElixirAst, right: ElixirAst): ElixirAst = s.remoteCall("erlang", "=:=", listOf(left, right))

    private fun member(left: ElixirAst, right: ElixirAst): ElixirAst = s.remoteCall("lists", "member", listOf(left, right))

    private fun zero(): ElixirAst = s.integer(BigInteger.ZERO)

    private fun block(vararg expressions: ElixirAst): ElixirAst = ElixirAst.Block(s.meta(), expressions.toList())

    /** `Elixir.Enum`, which `Kernel`'s `quote` writes in full, so it has no `alias: false`. */
    private fun elixirEnum(): ElixirAst = ElixirAst.Alias(s.meta(), listOf(s.atom("Elixir"), s.atom("Enum")))

    /** `Macro.expand/2` of [node], or the macro's [Summary.Output] where it stops. */
    private fun expand(node: ElixirAst): ElixirAst = expandArgument(node, state, env, run) { throw Stop(it) }
}
