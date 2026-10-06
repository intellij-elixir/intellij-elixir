package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import org.elixir_lang.expander.ExState.Prematch.Bitsize
import org.elixir_lang.expander.ExState.Prematch.InMatch
import org.elixir_lang.expander.ExState.Prematch.OutsideMatch
import org.elixir_lang.language_level.ElixirLanguageFeature.BARE_SEGMENT_PASSES_BITSTRING_META
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_LIST_OR_ATOM_SEGMENT_REJECTED
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_PATTERN_SEGMENT_VALIDATED
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_SIZE_EXPANDED_AS_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_SIZE_HIDES_ITS_OWN_SEGMENT
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_SPEC_NAME_EXPANDED_AS_CALL
import org.elixir_lang.language_level.ElixirLanguageFeature.HALF_FLOAT_SEGMENT
import org.elixir_lang.language_level.ElixirLanguageFeature.PINNED_BINARY_SEGMENT_INFERS_SIZE
import org.elixir_lang.language_level.ElixirLanguageFeature.PINNED_SEGMENT_INFERS_SIZE_ONLY_WHEN_SIZED
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term
import java.math.BigDecimal
import java.math.BigInteger

/**
 * `elixir_bitstring:expand/5`.
 *
 * @param requireSize whether every segment needs a size, as in a generator's pattern, and not only those before the
 *   last in a pattern
 */
internal fun expandBitstring(
    node: ElixirAst.Call,
    state: ExState,
    env: Env,
    run: Run,
    requireSize: Boolean = false,
): Expansion {
    val segments = node.arguments!!

    return if (env.context == Env.Context.MATCH) {
        expandSegments(node, segments, state, state, env, run, requireSize).then { s, e ->
            if (!BITSTRING_PATTERN_SEGMENT_VALIDATED.isSufficient(run.level) && segments.any(::containsMatch)) {
                Expansion.Error("nested_match", node)
            } else {
                Expansion.Expanded(s, e, NODE)
            }
        }
    } else {
        argumentScope(state, env) { scope -> expandSegments(node, segments, scope, state, env, run, requireSize) }
    }
}

/**
 * `elixir_bitstring:expand/8`.
 *
 * @param original the state where the bitstring began
 */
private fun expandSegments(
    bitstring: ElixirAst,
    segments: List<ElixirAst>,
    state: ExState,
    original: ExState,
    env: Env,
    run: Run,
    requireSize: Boolean,
): Expansion {
    val level = run.level
    val context = env.context
    var accState = state
    var accEnv = env
    var bareMeta = bitstring
    val report = Reporter { site, at -> reportOrEnd(site, at, env, run) }

    for ((index, segment) in segments.withIndex()) {
        val matchSize = requireSize || matchSize(context, index, segments)
        val typed = isCall(segment, "::", 2)
        val value = if (typed) (segment as ElixirAst.Call).arguments!![0] else segment
        val (metaNode, nextBareMeta) = if (typed) segment to bareMeta else bareMeta(segment, bareMeta, level)

        val expansion = expandValue(value, accState, original, accEnv, run)
        if (expansion !is Expansion.Expanded) return expansion

        valueError(value, metaNode, context, level, report)?.let { return it }

        val shape = valueShape(value, context)
        val described = if (typed) {
            val hides = BITSTRING_SIZE_HIDES_ITS_OWN_SEGMENT.isSufficient(level)
            val specState = if (hides) expansion.state.copy(read = accState.read, write = accState.write) else expansion.state
            val spec = (segment as ElixirAst.Call).arguments!![1]

            when (val specs = expandSpecs(spec, specState, original, expansion.env, segment, run)) {
                is Specs.Stopped -> return specs.expansion
                is Specs.Expanded -> {
                    accState = if (hides) specs.state.copy(read = expansion.state.read, write = expansion.state.write) else specs.state
                    accEnv = specs.env

                    describeTyped(segment, shape, specs.args, context, matchSize, level, report)
                }
            }
        } else {
            accState = expansion.state
            accEnv = expansion.env
            bareMeta = nextBareMeta

            describeBare(metaNode, shape, context, matchSize, level, report)
        }

        when (described) {
            is Described.Segment -> Unit
            is Described.Ended -> return described.expansion
            is Described.Unported -> return Expansion.Unported(described.at)
            is Described.NumberSizeRaises ->
                return Expansion.Error(ErrorSite.BAD_UNIT_ARGUMENT.kind, segment).also {
                    run.crash = Crash(it, ARITHMETIC_ERROR)
                }
        }
    }

    return Expansion.Expanded(accState, accEnv, NODE)
}

/** `is_match_size/2`: whether segment [index] is in a pattern and another follows it. */
private fun matchSize(context: Env.Context, index: Int, segments: List<ElixirAst>) =
    context == Env.Context.MATCH && index != segments.lastIndex

/**
 * `extract_meta/2` for a segment without `::`: the node it reports at, and the node the next such segment falls back
 * to when it has no metadata of its own.
 */
private fun bareMeta(segment: ElixirAst, previous: ElixirAst, level: ElixirLanguageLevel): Pair<ElixirAst, ElixirAst> {
    val metaNode = segment.takeIf { it.hasMetadata() } ?: previous

    return metaNode to if (BARE_SEGMENT_PASSES_BITSTRING_META.isSufficient(level)) previous else metaNode
}

/** `expand_expr/4` with `expand_match/3` in a pattern and `expand_arg/3` otherwise. */
private fun expandValue(value: ElixirAst, state: ExState, original: ExState, env: Env, run: Run): Expansion {
    fun expand(node: ElixirAst) =
        if (env.context == Env.Context.MATCH) {
            Expander.expand(node, state, env, run)
        } else {
            expandArg(node, state, original, env, run)
        }

    val inline = interpolated(value, env.context) ?: return expand(value)

    // Anything but a binary expands the whole call instead, from the state before it.
    return expand(inline).thenValue { s, e, v ->
        if (v is Term.Binary) Expansion.Expanded(s, e, v) else expand(value)
    }
}

/** The argument of the `to_string` call an interpolation makes, which a pattern or a guard inlines. */
private fun interpolated(value: ElixirAst, context: Env.Context): ElixirAst? {
    if (context == Env.Context.NONE || value !is ElixirAst.Call) return null

    val dot = value.callee as? ElixirAst.Call ?: return null
    if ((dot.callee as? ElixirAst.Literal.Atom)?.name != ".") return null

    val (module, function) = dot.arguments?.takeIf { it.size == 2 } ?: return null

    return value.arguments?.singleOrNull()?.takeIf {
        (module as? ElixirAst.Literal.Atom)?.name in TO_STRING_MODULES &&
            (function as? ElixirAst.Literal.Atom)?.name == "to_string"
    }
}

private val TO_STRING_MODULES = setOf("Elixir.Kernel", "Elixir.String.Chars")

/** A segment's value in the shape its expansion has. */
private fun valueShape(value: ElixirAst, context: Env.Context): ElixirAst =
    expandedShape(interpolated(value, context) ?: value)

/** `invalid_literal` before 1.18, which `expand_expr/5` raises, and `unknown_match` from 1.19. */
private fun valueError(
    value: ElixirAst,
    metaNode: ElixirAst,
    context: Env.Context,
    level: ElixirLanguageLevel,
    report: Reporter,
): Expansion? =
    if (BITSTRING_PATTERN_SEGMENT_VALIDATED.isSufficient(level)) {
        val shape = valueShape(value, context)

        if (context == Env.Context.MATCH && !isMatchSegment(shape)) {
            report(ErrorSite.UNKNOWN_MATCH, if (shape.hasMetadata()) shape else metaNode)
        } else {
            null
        }
    } else {
        val shape = expandedShape(value)

        if (BITSTRING_LIST_OR_ATOM_SEGMENT_REJECTED.isSufficient(level) &&
            interpolated(value, context) == null &&
            (shape is ElixirAst.ListNode || shape is ElixirAst.Literal.Atom)
        ) {
            Expansion.Error("invalid_literal", metaNode)
        } else {
            null
        }
    }

private fun isMatchSegment(shape: ElixirAst) =
    isVariable(shape) ||
        isBitstring(shape) ||
        isCall(shape, "^", 1) ||
        shape is ElixirAst.Literal.Integer ||
        shape is ElixirAst.Literal.Float ||
        shape is ElixirAst.Literal.Binary

/** `find_match/1`, before 1.19: a `=` in any call's arguments, block's expressions or `{}` tuple's elements. */
private fun containsMatch(node: ElixirAst): Boolean {
    val shape = expandedShape(node)

    return when {
        isCall(shape, "=", 2) -> true
        shape is ElixirAst.Call -> shape.arguments.orEmpty().any(::containsMatch)
        shape is ElixirAst.Block -> shape.expressions.any(::containsMatch)
        shape is ElixirAst.Tuple && shape.elements.size != 2 -> shape.elements.any(::containsMatch)
        else -> false
    }
}

// Specs

/** An expanded spec argument: a literal's term, or the expression it expanded to. */
private sealed interface SpecArg {
    data class Literal(val term: OtpErlangObject) : SpecArg

    class Expression(val node: ElixirAst) : SpecArg

    companion object {
        fun of(node: ElixirAst): SpecArg =
            when (val shape = expandedShape(node)) {
                is ElixirAst.Literal -> Literal(shape.toOtp())
                else -> Expression(shape)
            }
    }
}

private val SpecArg?.integer: BigInteger?
    get() = ((this as? SpecArg.Literal)?.term as? OtpErlangLong)?.bigIntegerValue()

private val SpecArg?.number: BigDecimal?
    get() =
        when (val term = (this as? SpecArg.Literal)?.term) {
            is OtpErlangLong -> term.bigIntegerValue().toBigDecimal()
            is OtpErlangDouble -> term.doubleValue().toBigDecimal()
            else -> null
        }

private sealed interface Specs {
    /** The last argument given to each spec key. */
    class Expanded(val args: Map<String, SpecArg>, val state: ExState, val env: Env) : Specs

    class Stopped(val expansion: Expansion) : Specs
}

/** `expand_each_spec/6` over `unpack_specs/2`. */
private fun expandSpecs(spec: ElixirAst, state: ExState, original: ExState, env: Env, segment: ElixirAst, run: Run): Specs {
    var accState = state
    var accEnv = env
    val args = mutableMapOf<String, SpecArg>()

    for (unpacked in unpackSpecs(spec)) {
        val builtin = when (unpacked) {
            is Unpacked.Builtin -> unpacked
            is Unpacked.Named -> {
                when (val named = expandNamedSpec(unpacked.node as ElixirAst.Call, accState, accEnv, run)) {
                    is NamedSpec.Size ->
                        Unpacked.Builtin(unpacked.node, "size", ElixirAst.Literal.Integer(unpacked.node.meta, named.value))
                    NamedSpec.Unchanged -> {
                        reportOrEnd(ErrorSite.UNDEFINED_BITTYPE, segment, accEnv, run)?.let { return Specs.Stopped(it) }
                        continue
                    }
                    is NamedSpec.Other -> return Specs.Stopped(named.expansion)
                }
            }
            is Unpacked.Other -> {
                if (unpacked.node is ElixirAst.Placeholder) return Specs.Stopped(Expansion.Unported(unpacked.node))

                reportOrEnd(ErrorSite.UNDEFINED_BITTYPE, segment, accEnv, run)?.let { return Specs.Stopped(it) }
                continue
            }
        }
        val arg = builtin.arg

        if (arg !is ElixirAst.Literal.Atom && arg !is ElixirAst.Literal.Integer) {
            when (val expansion = expandSpecArg(arg, accState, original, accEnv, run)) {
                is Expansion.Expanded -> {
                    accState = expansion.state
                    accEnv = expansion.env
                }
                is Expansion.Error, is Expansion.Unported, is Expansion.Opaque -> return Specs.Stopped(expansion)
            }
        }

        val value = SpecArg.of(literalShape(arg, accEnv, run.level))

        if (builtin.key == "unit" && value.integer == null) {
            reportOrEnd(ErrorSite.BAD_UNIT_ARGUMENT, segment, accEnv, run)?.let { return Specs.Stopped(it) }
        }

        sizeArgError(builtin.key, value, accState, original, accEnv, run.level)?.let {
            return Specs.Stopped(Expansion.Error(it, segment))
        }

        val previous = args[builtin.key]

        if (previous != null) {
            when (sameTerm(previous, value)) {
                false ->
                    reportOrEnd(ErrorSite.BITTYPE_MISMATCH_SPEC, segment, accEnv, run)?.let {
                        return Specs.Stopped(it)
                    }
                // Expanded terms carry their metadata, columns included from 1.16.
                null -> return Specs.Stopped(Expansion.Unported(unpacked.node))
                true -> Unit
            }
        }

        args[builtin.key] = value
    }

    return Specs.Expanded(args, accState, accEnv)
}

/** What `Macro.expand/2` makes of a spec `validate_spec/2` doesn't know. */
private sealed interface NamedSpec {
    /** The integer the spec expands to, which is a size. */
    class Size(val value: BigInteger) : NamedSpec

    /** The spec left as it is, which is `undefined_bittype`. */
    data object Unchanged : NamedSpec

    /** Where expanding the spec stopped. */
    class Other(val expansion: Expansion) : NamedSpec
}

/** `Macro.expand/2` of a spec `validate_spec/2` doesn't know. From 1.15 a name is first made a call of no arguments. */
private fun expandNamedSpec(spec: ElixirAst.Call, state: ExState, env: Env, run: Run): NamedSpec {
    val call = when {
        spec.arguments != null -> spec
        BITSTRING_SPEC_NAME_EXPANDED_AS_CALL.isSufficient(run.level) ->
            ElixirAst.Call(spec.meta, spec.callee, emptyList())
        else -> return NamedSpec.Unchanged
    }

    return when (val expanded = macroExpand(call, state, env, run)) {
        is MacroExpanded.Node -> {
            val node = expanded.node

            when {
                !expanded.expanded -> NamedSpec.Unchanged
                node is ElixirAst.Literal.Integer -> NamedSpec.Size(node.value)
                // Elixir unpacks the specs it expands to again.
                else -> NamedSpec.Other(Expansion.Unported(call))
            }
        }
        // A call is never `__DIR__`.
        MacroExpanded.Dir -> NamedSpec.Other(Expansion.Unported(call))
        is MacroExpanded.Stopped -> NamedSpec.Other(expanded.expansion)
    }
}

/** Whether two expanded spec arguments are the same term, or `null` where that depends on their metadata. */
private fun sameTerm(left: SpecArg, right: SpecArg): Boolean? =
    when {
        left is SpecArg.Literal && right is SpecArg.Literal -> left.term == right.term
        left is SpecArg.Literal || right is SpecArg.Literal -> false
        else -> null
    }

/** `expand_spec_arg/4`. */
private fun expandSpecArg(arg: ElixirAst, state: ExState, original: ExState, env: Env, run: Run): Expansion =
    if (env.context == Env.Context.MATCH) {
        val inMatch = state.prematch as InMatch
        val (sizeState, sizeContext) = if (BITSTRING_SIZE_EXPANDED_AS_GUARD.isSufficient(run.level)) {
            state.copy(prematch = Bitsize(inMatch, original.read)) to Env.Context.GUARD
        } else {
            state.copy(prematch = OutsideMatch(OutsideMatch.Mode.Raise)) to Env.Context.NONE
        }

        Expander.expand(arg, sizeState, env.copy(context = sizeContext), run).then { s, e ->
            Expansion.Expanded(s.copy(prematch = inMatch), e.copy(context = Env.Context.MATCH), NODE)
        }
    } else {
        Expander.expand(arg, state.resetRead(original), env, run)
    }

/**
 * `validate_spec_arg/6` for a size, which before 1.14 must be an integer, or a variable that the pattern bound before
 * the bitstring's own segments did not.
 */
private fun sizeArgError(
    key: String,
    value: SpecArg,
    state: ExState,
    original: ExState,
    env: Env,
    level: ElixirLanguageLevel,
): String? =
    when {
        key != "size" || BITSTRING_SIZE_EXPANDED_AS_GUARD.isSufficient(level) || value.integer != null -> null
        value is SpecArg.Expression && isVariable(value.node) -> {
            val variable = variable(value.node)
            val readable = env.context != Env.Context.MATCH ||
                variable in (state.prematch as InMatch).read ||
                (variable in state.read && variable !in original.read)

            if (readable) null else "undefined_var_in_spec"
        }
        else -> "bad_size_argument"
    }

private sealed interface Unpacked {
    val node: ElixirAst

    /** A spec `validate_spec/2` knows. */
    class Builtin(override val node: ElixirAst, val key: String, val arg: ElixirAst) : Unpacked

    /** A name `validate_spec/2` doesn't know. */
    class Named(override val node: ElixirAst) : Unpacked

    /** Neither, which is `undefined_bittype`. */
    class Other(override val node: ElixirAst) : Unpacked
}

/** The arguments of the `size` and `unit` specs in [spec], the only spec arguments its expansion keeps as written. */
internal fun sizeAndUnitArguments(spec: ElixirAst): List<ElixirAst> =
    unpackSpecs(spec).filterIsInstance<Unpacked.Builtin>().filter { it.key == "size" || it.key == "unit" }.map { it.arg }

/** `unpack_specs/2`, then `validate_spec/2` of each. */
private fun unpackSpecs(spec: ElixirAst): List<Unpacked> {
    if (isCall(spec, "-", 2)) return (spec as ElixirAst.Call).arguments!!.flatMap(::unpackSpecs)

    if (isCall(spec, "*", 2)) {
        val (size, unit) = (spec as ElixirAst.Call).arguments!!
        val unitSpec = Unpacked.Builtin(spec, "unit", unit)

        return if (isUnderscore(size)) {
            listOf(unitSpec)
        } else {
            listOf(Unpacked.Builtin(spec, "size", size), unitSpec)
        }
    }

    if (spec is ElixirAst.Literal.Integer) return listOf(Unpacked.Builtin(spec, "size", spec))

    if (spec !is ElixirAst.Call || spec.callee !is ElixirAst.Literal.Atom) return listOf(Unpacked.Other(spec))

    val name = spec.callee.name
    val args = spec.arguments.orEmpty()
    val builtin = when {
        name == "size" || name == "unit" -> args.singleOrNull()?.let { Unpacked.Builtin(spec, name, it) }
        args.isNotEmpty() -> null
        else -> ARGUMENTLESS_SPECS[name]?.let { (key, value) ->
            Unpacked.Builtin(spec, key, ElixirAst.Literal.Atom(spec.meta, value))
        }
    }

    return listOf(builtin ?: Unpacked.Named(spec))
}

/** Each spec that takes no argument, as the key it sets and that key's value. */
private val ARGUMENTLESS_SPECS = mapOf(
    "big" to ("endianness" to "big"),
    "little" to ("endianness" to "little"),
    "native" to ("endianness" to "native"),
    "integer" to ("type" to "integer"),
    "float" to ("type" to "float"),
    "binary" to ("type" to "binary"),
    "bytes" to ("type" to "binary"),
    "bitstring" to ("type" to "bitstring"),
    "bits" to ("type" to "bitstring"),
    "utf8" to ("type" to "utf8"),
    "utf16" to ("type" to "utf16"),
    "utf32" to ("type" to "utf32"),
    "signed" to ("sign" to "signed"),
    "unsigned" to ("sign" to "unsigned"),
)

// What a segment builds, which a bitstring nesting this one checks

/** One element of an expanded bitstring's arguments. */
private class Part(
    /** Where an `unsized_binary` for this part is reported. */
    val at: ElixirAst,
    val value: ElixirAst,
    /** `binary` or `bitstring` when that is the whole spec built. */
    val alone: String?,
)

private sealed interface Described {
    /** @property alignment modulo 8, or `null` where it is `unknown` */
    class Segment(val parts: List<Part>, val alignment: Int?) : Described

    /** An error Elixir doesn't carry on from. */
    class Ended(val expansion: Expansion) : Described

    class Unported(val at: ElixirAst) : Described

    /** `number_size/2` multiplying an integer size by a unit that isn't a number, which raises `ArithmeticError`. */
    data object NumberSizeRaises : Described
}

/** Reports a site's error, giving `null` where Elixir carries on, and otherwise the expansion that ends there. */
private fun interface Reporter {
    operator fun invoke(site: ErrorSite, at: ElixirAst): Expansion?
}

/** For a bitstring already expanded, whose errors were reported then. */
private val ALREADY_REPORTED = Reporter { _, _ -> null }

/** `expr_type/1`. */
private fun exprType(shape: ElixirAst) =
    when {
        shape is ElixirAst.Literal.Integer -> "integer"
        shape is ElixirAst.Literal.Float -> "float"
        shape is ElixirAst.Literal.Binary -> "binary"
        isBitstring(shape) -> "bitstring"
        else -> "default"
    }

/** A segment without `::`, whose spec `infer_spec/2` takes from its value, and which leaves the alignment alone. */
private fun describeBare(
    metaNode: ElixirAst,
    shape: ElixirAst,
    context: Env.Context,
    matchSize: Boolean,
    level: ElixirLanguageLevel,
    report: Reporter,
): Described {
    val alone = exprType(shape).takeIf { it in BINARIES }

    return concat(metaNode, shape, alone, 0, context, matchSize, level, report)
}

/** `expand_specs/7` from a segment's expanded spec arguments, then `concat_or_prepend_bitstring/6`. */
private fun describeTyped(
    segment: ElixirAst,
    shape: ElixirAst,
    args: Map<String, SpecArg>,
    context: Env.Context,
    matchSize: Boolean,
    level: ElixirLanguageLevel,
    report: Reporter,
): Described {
    fun ended(site: ErrorSite) = report(site, segment)?.let(Described::Ended)

    /** A site whose helper's return value `expand_specs/7` can't match. */
    fun crashed(site: ErrorSite) = ended(site) ?: Described.Unported(segment)

    val exprType = exprType(shape)
    val type = ((args["type"] as SpecArg.Literal?)?.term as OtpErlangAtom?)?.atomValue()
    val size = args["size"]
    val unit = args["unit"]
    val sign = args["sign"]
    val merged = mergedType(exprType, type)

    if (!typesAgree(exprType, type)) ended(ErrorSite.BITTYPE_MISMATCH_TYPE)?.let { return it }

    val inferSize = isCall(shape, "^", 1) &&
        PINNED_BINARY_SEGMENT_INFERS_SIZE.isSufficient(level) &&
        (matchSize || !PINNED_SEGMENT_INFERS_SIZE_ONLY_WHEN_SIZED.isSufficient(level))

    if (matchSize && !inferSize && exprType == "default" && merged in BINARIES && size == null) {
        ended(ErrorSite.UNSIZED_BINARY_REQUIRED)?.let { return it }
    }

    val sizeOrUnit = size != null || unit != null

    if (sizeOrUnit && exprType == "bitstring") ended(ErrorSite.BITTYPE_LITERAL_BITSTRING)?.let { return it }
    if (sizeOrUnit && exprType == "binary") ended(ErrorSite.BITTYPE_LITERAL_STRING)?.let { return it }

    when (merged) {
        "utf8", "utf16", "utf32" ->
            when {
                sizeOrUnit -> ended(ErrorSite.BITTYPE_UTF)
                sign != null -> ended(ErrorSite.BITTYPE_SIGNED)
                else -> null
            }?.let { return it }
        "binary", "bitstring" ->
            when {
                merged == "bitstring" && unit != null && unit.number?.compareTo(BigDecimal.ONE) != 0 ->
                    ended(ErrorSite.BITTYPE_MISMATCH_UNIT)
                sign != null -> ended(ErrorSite.BITTYPE_SIGNED)
                else -> null
            }?.let { return it }
        else -> {
            val integerSize = size.integer

            if (integerSize != null && unit != null && unit.number == null) return Described.NumberSizeRaises

            val numberSize = integerSize?.let { s -> if (unit == null) s else unit.integer?.let { s * it } }

            if (merged == "float" && numberSize != null) {
                if (numberSize !in validFloatSizes(level)) return crashed(ErrorSite.BITTYPE_FLOAT_SIZE)
            } else if (size == null && unit != null) {
                return crashed(ErrorSite.BITTYPE_UNIT)
            }
        }
    }

    // `size_and_unit/5` drops a literal's size and unit, so they don't stop it being spliced in.
    val alone = merged.takeIf { it in BINARIES && !(sizeOrUnit && exprType !in BINARIES) && !inferSize }

    return concat(segment, shape, alone, alignment(merged, size, unit), context, matchSize, level, report)
}

private val BINARIES = setOf("binary", "bitstring")

/** `valid_float_size/1`. */
private fun validFloatSizes(level: ElixirLanguageLevel) =
    listOfNotNull(16.takeIf { HALF_FLOAT_SEGMENT.isSufficient(level) }, 32, 64).map(Int::toBigInteger)

/** `type/4`: the type a segment's value and its type spec give it, which is the spec's where they conflict. */
private fun mergedType(exprType: String, type: String?): String =
    type ?: if (exprType == "default") "integer" else exprType

/** Whether `type/4` accepts a segment's value of [exprType] under its type spec. */
private fun typesAgree(exprType: String, type: String?): Boolean =
    when {
        type == null || exprType == "default" -> true
        exprType == "binary" -> type in setOf("binary", "bitstring", "utf8", "utf16", "utf32")
        exprType == "bitstring" -> type in BINARIES
        exprType == "integer" -> type in setOf("integer", "float", "utf8", "utf16", "utf32")
        exprType == "float" -> type == "float"
        else -> false
    }

/** `compute_alignment/3`, modulo 8. */
private fun alignment(type: String, size: SpecArg?, unit: SpecArg?): Int? {
    fun times(defaultSize: BigInteger?, defaultUnit: BigInteger?): BigInteger? {
        val s = if (size == null) defaultSize else size.integer
        val u = if (unit == null) defaultUnit else unit.integer

        return if (s != null && u != null) s * u else null
    }

    val bits = when (type) {
        "integer" -> times(8.toBigInteger(), BigInteger.ONE)
        "bitstring" -> times(null, BigInteger.ONE)
        else -> times(null, null)
    }

    return when {
        bits != null -> bits.mod(8.toBigInteger()).toInt()
        type == "integer" || type == "bitstring" -> null
        else -> 0
    }
}

/**
 * `concat_or_prepend_bitstring/6`: a nested bitstring whose whole spec is `binary` or `bitstring` is spliced in,
 * after checking its last part and, for `binary`, its alignment.
 *
 * @param at where the segment's own errors are reported
 * @param matchSize whether this segment needs a size: it is in a pattern and another follows it, or it is in a
 *   generator's pattern
 */
private fun concat(
    at: ElixirAst,
    shape: ElixirAst,
    alone: String?,
    alignment: Int?,
    context: Env.Context,
    matchSize: Boolean,
    level: ElixirLanguageLevel,
    report: Reporter,
): Described {
    val self = Described.Segment(listOf(Part(at, shape, alone)), alignment)
    if (!isBitstring(shape)) return self

    val inner = when (val described = describe(shape as ElixirAst.Call, context, level)) {
        is Described.Segment -> described
        else -> return Described.Unported(shape)
    }
    if (inner.parts.isEmpty()) return Described.Segment(emptyList(), alignment)

    if (matchSize) {
        val last = inner.parts.last()

        if ((last.alone == "binary" && last.value !is ElixirAst.Literal.Binary) || last.alone == "bitstring") {
            report(ErrorSite.UNSIZED_BINARY_NESTED, last.at)?.let { return Described.Ended(it) }
        }
    }

    return when (alone) {
        "binary" ->
            when (inner.alignment) {
                null -> self
                0 -> Described.Segment(inner.parts, alignment)
                else ->
                    report(ErrorSite.UNALIGNED_BINARY, at)?.let(Described::Ended)
                        ?: Described.Segment(inner.parts, alignment)
            }
        "bitstring" -> Described.Segment(inner.parts, alignment)
        else -> self
    }
}

/** The parts and alignment of a nested bitstring, which has already expanded without error. */
private fun describe(bitstring: ElixirAst.Call, context: Env.Context, level: ElixirLanguageLevel): Described {
    val segments = bitstring.arguments!!
    var parts = emptyList<Part>()
    var alignment: Int? = 0
    var bareMeta: ElixirAst = bitstring

    for ((index, segment) in segments.withIndex()) {
        val matchSize = matchSize(context, index, segments)
        val described = if (isCall(segment, "::", 2)) {
            val (value, spec) = (segment as ElixirAst.Call).arguments!!
            val args = unpackSpecs(spec).filterIsInstance<Unpacked.Builtin>().associate { it.key to SpecArg.of(it.arg) }

            describeTyped(segment, valueShape(value, context), args, context, matchSize, level, ALREADY_REPORTED)
        } else {
            val (metaNode, nextBareMeta) = bareMeta(segment, bareMeta, level)
            bareMeta = nextBareMeta

            describeBare(metaNode, valueShape(segment, context), context, matchSize, level, ALREADY_REPORTED)
        }

        when (described) {
            is Described.Segment -> {
                parts = parts + described.parts
                alignment = alignment?.let { a -> described.alignment?.let { (a + it) % 8 } }
            }
            else -> return described
        }
    }

    return Described.Segment(parts, alignment)
}
