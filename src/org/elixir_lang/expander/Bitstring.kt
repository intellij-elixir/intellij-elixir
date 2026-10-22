package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.expander.ExState.Prematch.Bitsize
import org.elixir_lang.expander.ExState.Prematch.InMatch
import org.elixir_lang.expander.ExState.Prematch.OutsideMatch
import org.elixir_lang.language_level.ElixirLanguageFeature.BARE_SEGMENT_PASSES_BITSTRING_META
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_LIST_OR_ATOM_SEGMENT_REJECTED
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_PATTERN_SEGMENT_VALIDATED
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_SIZE_EXPANDED_AS_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_SIZE_HIDES_ITS_OWN_SEGMENT
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
        argumentScope(state) { scope -> expandSegments(node, segments, scope, state, env, run, requireSize) }
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
    var parts = emptyList<Part>()
    var alignment: Int? = 0
    val report = Reporter { site, at -> reportOrEnd(site, at, env, run) }

    for ((index, segment) in segments.withIndex()) {
        ProgressManager.checkCanceled()

        val matchSize = requireSize || matchSize(context, index, segments)
        val typed = isCall(segment, "::", 2)
        val value = if (typed) (segment as ElixirAst.Call).arguments!![0] else segment
        val (metaNode, nextBareMeta) = if (typed) segment to bareMeta else bareMeta(segment, bareMeta, level)

        val expansion = expandValue(value, accState, original, accEnv, run)
        if (expansion !is Expansion.Expanded) return expansion
        // The value's checks and `expr_type/1` read it.
        unknownStop(expansion.value, value)?.let { return it }

        valueError(value, expansion.value, metaNode, context, run, report)?.let { return it }

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

                    describeTyped(segment, shape, expansion.value, specs, matchSize, env, run, report)
                }
            }
        } else {
            accState = expansion.state
            accEnv = expansion.env
            bareMeta = nextBareMeta

            describeBare(segment, metaNode, shape, expansion.value, matchSize, env, run, report)
        }

        when (described) {
            is Described.Segment -> {
                parts = parts + described.parts
                alignment = alignment?.let { a -> described.alignment?.let { (a + it) % 8 } }
            }
            is Described.Ended -> return described.expansion
            is Described.Unported -> return Expansion.Unported(described.at)
            is Described.NumberSizeRaises ->
                return Expansion.Error(ErrorSite.BAD_UNIT_ARGUMENT.kind, segment).also {
                    run.crash = Crash(it, ARITHMETIC_ERROR)
                }
        }
    }

    run.bitstrings[bitstring] = BitstringParts(parts, alignment)

    // Outside a pattern or a guard it builds a binary when every part is one, whatever the content; there it is no
    // literal, which the pattern and guard readers of `Term.Binary` take it for.
    val binary = context == Env.Context.NONE && alignment == 0 && parts.isNotEmpty() && parts.all { it.alone == "binary" }

    return Expansion.Expanded(accState, accEnv, if (binary) Term.Binary(null) else NODE)
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
        unknownStop(v, inline)?.let { return@thenValue it }

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

/**
 * `invalid_literal` before 1.18, which `expand_expr/5` raises, and `unknown_match` from 1.19, each judged on
 * [expanded], the segment's expanded value.
 */
private fun valueError(
    value: ElixirAst,
    expanded: Term,
    metaNode: ElixirAst,
    context: Env.Context,
    run: Run,
    report: Reporter,
): Expansion? =
    if (BITSTRING_PATTERN_SEGMENT_VALIDATED.isSufficient(run.level)) {
        val shape = valueShape(value, context)

        if (context == Env.Context.MATCH && !isMatchSegment(shape, expanded, run)) {
            report(ErrorSite.UNKNOWN_MATCH, if (shape.hasMetadata()) shape else metaNode)
        } else {
            null
        }
    } else if (BITSTRING_LIST_OR_ATOM_SEGMENT_REJECTED.isSufficient(run.level) &&
        interpolated(value, context) == null &&
        (expanded is Term.List || expanded is Term.Atom)
    ) {
        Expansion.Error("invalid_literal", metaNode)
    } else {
        null
    }

/** `validate_expr/3`: a variable, a pin, a number or a binary, as [expanded] is, or a bitstring, as [shape] is. */
private fun isMatchSegment(shape: ElixirAst, expanded: Term, run: Run) =
    expanded == VARIABLE_NODE ||
        expanded == Term.Node(Term.Node.Kind.PIN) ||
        expanded is Term.Integer ||
        expanded is Term.NonTuple ||
        expanded is Term.Binary ||
        isBuilt(shape, run)

/** `find_match/1`, before 1.19: a `=` in any call's arguments, block's expressions or `{}` tuple's elements. */
private fun containsMatch(node: ElixirAst): Boolean {
    ProgressManager.checkCanceled()

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

/**
 * An expanded spec argument: a literal's term, the variable it expanded to, another expression, a value the expansion
 * doesn't hold, or another value.
 */
private sealed interface SpecArg {
    data class Literal(val term: OtpErlangObject) : SpecArg

    class Variable(val node: ElixirAst) : SpecArg

    /** The AST of an expression other than a variable, which is no literal's term. */
    data object Expression : SpecArg

    /** An unknown attribute's read, which may be a literal's: a check that reads it stops at [stop]. */
    class Unknown(val stop: Expansion.Unported) : SpecArg

    /** A float that isn't written as a literal, such as a macro's, whose value isn't kept. */
    data object Float : SpecArg

    /** A value that is no number or atom, as a list is, whose term isn't kept. */
    data object Other : SpecArg

    companion object {
        /**
         * [arg], which expanded to [value], or to [variable]: an integer's or an atom's term, a literal's where
         * [value] doesn't hold it, as a float's, the variable, an expression where [value] is its AST, unknown where
         * it is an unknown attribute's read, stopping at [spec], a float, and otherwise another value.
         */
        fun of(value: Term, variable: ElixirAst?, arg: ElixirAst, spec: ElixirAst): SpecArg =
            when {
                value is Term.Integer -> Literal(OtpErlangLong(value.value))
                value is Term.Atom -> Literal(OtpErlangAtom(value.name))
                variable != null -> Variable(variable)
                else -> (expandedShape(arg) as? ElixirAst.Literal)?.let { Literal(it.toOtp()) }
                    ?: unknownStop(value, spec)?.let(::Unknown)
                    ?: when (value) {
                        is Term.Node -> Expression
                        Term.NonTuple -> Float
                        else -> Other
                    }
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
    /**
     * The last argument given to each spec key.
     *
     * @property size the size spec's argument as written, which runs where the segment does
     */
    class Expanded(val args: Map<String, SpecArg>, val size: ElixirAst?, val state: ExState, val env: Env) : Specs

    class Stopped(val expansion: Expansion) : Specs
}

/** `expand_each_spec/6` over `unpack_specs/2`. */
private fun expandSpecs(spec: ElixirAst, state: ExState, original: ExState, env: Env, segment: ElixirAst, run: Run): Specs {
    var accState = state
    var accEnv = env
    val args = mutableMapOf<String, SpecArg>()
    var size: ElixirAst? = null
    val pending = ArrayDeque(unpackSpecs(spec))

    while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()

        val unpacked = pending.removeFirst()
        val builtin = when (unpacked) {
            is Unpacked.Builtin -> unpacked
            is Unpacked.Named -> {
                when (val named = expandNamedSpec(unpacked.node as ElixirAst.Call, accState, accEnv, run)) {
                    is NamedSpec.Expanded -> {
                        pending.addAll(0, unpackSpecs(named.node))
                        continue
                    }
                    NamedSpec.Unchanged -> {
                        reportOrEnd(ErrorSite.UNDEFINED_BITTYPE, segment, accEnv, run)?.let { return Specs.Stopped(it) }
                        continue
                    }
                    is NamedSpec.Other -> return Specs.Stopped(named.expansion)
                }
            }
            is Unpacked.Other -> {
                if (unpacked.node is ElixirAst.Placeholder && unpacked.node.stopsExpansion) return Specs.Stopped(Expansion.Unported(unpacked.node))

                reportOrEnd(ErrorSite.UNDEFINED_BITTYPE, segment, accEnv, run)?.let { return Specs.Stopped(it) }
                continue
            }
        }
        val arg = builtin.arg
        // As `validate_spec_arg/6` and the checks after it read it: the value the argument expanded to.
        val value = if (arg is ElixirAst.Literal.Atom || arg is ElixirAst.Literal.Integer) {
            SpecArg.of(literalValue(arg), null, arg, unpacked.node)
        } else {
            when (val expansion = expandSpecArg(arg, accState, original, accEnv, run)) {
                is Expansion.Expanded -> {
                    accState = expansion.state
                    accEnv = expansion.env
                    SpecArg.of(expansion.value, expansion.variable, arg, unpacked.node)
                }
                is Expansion.Error, is Expansion.Unported, is Expansion.Opaque -> return Specs.Stopped(expansion)
            }
        }

        if (builtin.key == "unit" && value.integer == null) {
            if (value is SpecArg.Unknown) return Specs.Stopped(value.stop)

            reportOrEnd(ErrorSite.BAD_UNIT_ARGUMENT, segment, accEnv, run)?.let { return Specs.Stopped(it) }
        }

        sizeArgCheck(builtin.key, value, accState, original, accEnv, run.level, segment)?.let {
            return Specs.Stopped(it)
        }

        val previous = args[builtin.key]

        if (previous != null) {
            when (sameTerm(previous, value)) {
                false ->
                    reportOrEnd(ErrorSite.BITTYPE_MISMATCH_SPEC, segment, accEnv, run)?.let {
                        return Specs.Stopped(it)
                    }
                null -> return Specs.Stopped(Expansion.Unported(unpacked.node))
                true -> Unit
            }
        }

        args[builtin.key] = value
        if (builtin.key == "size") size = arg
    }

    return Specs.Expanded(args, size, accState, accEnv)
}

/** What `Macro.expand/2` makes of a spec `validate_spec/2` doesn't know. */
private sealed interface NamedSpec {
    /** What the spec expands to, whose specs are unpacked ahead of the rest. */
    class Expanded(val node: ElixirAst) : NamedSpec

    /** The spec left as it is, which is `undefined_bittype`. */
    data object Unchanged : NamedSpec

    /** Where expanding the spec stopped. */
    class Other(val expansion: Expansion) : NamedSpec
}

/** `Macro.expand/2` of a spec `validate_spec/2` doesn't know, a name first made a call of no arguments. */
private fun expandNamedSpec(spec: ElixirAst.Call, state: ExState, env: Env, run: Run): NamedSpec {
    val call = if (spec.arguments != null) spec else ElixirAst.Call(spec.meta, spec.callee, emptyList())

    return when (val expanded = macroExpand(call, state, env, run)) {
        is MacroExpanded.Node -> if (expanded.expanded) NamedSpec.Expanded(expanded.node) else NamedSpec.Unchanged
        // A call is never `__DIR__`.
        MacroExpanded.Dir -> NamedSpec.Other(Expansion.Unported(call))
        is MacroExpanded.Stopped -> NamedSpec.Other(expanded.expansion)
    }
}

/**
 * Whether two expanded spec arguments are the same term, or `null` where that isn't known: a value the expansion
 * doesn't hold, a value whose term isn't kept, two floats, one of whose values isn't kept, or two expressions, whose
 * terms hold their metadata. A float is no other kind of term.
 */
private fun sameTerm(left: SpecArg, right: SpecArg): Boolean? =
    when {
        listOf(left, right).any { it is SpecArg.Unknown } -> null
        left is SpecArg.Float || right is SpecArg.Float ->
            if (listOf(left, right).all { it is SpecArg.Float || (it as? SpecArg.Literal)?.term is OtpErlangDouble }) {
                null
            } else {
                false
            }
        listOf(left, right).any { it is SpecArg.Other } -> null
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

        when (val expansion = Expander.expand(arg, sizeState, env.copy(context = sizeContext), run)) {
            is Expansion.Expanded -> expansion.copy(
                state = expansion.state.copy(prematch = inMatch),
                env = expansion.env.copy(context = Env.Context.MATCH),
            )
            is Expansion.Error, is Expansion.Unported, is Expansion.Opaque -> expansion
        }
    } else {
        Expander.expand(arg, state.resetRead(original), env, run)
    }

/**
 * `validate_spec_arg/6` for a size, which before 1.14 must be an integer, or a variable that the pattern bound before
 * the bitstring's own segments did not: its error at [segment], or the stop of a value that isn't held.
 */
private fun sizeArgCheck(
    key: String,
    value: SpecArg,
    state: ExState,
    original: ExState,
    env: Env,
    level: ElixirLanguageLevel,
    segment: ElixirAst,
): Expansion? =
    when {
        key != "size" || BITSTRING_SIZE_EXPANDED_AS_GUARD.isSufficient(level) || value.integer != null -> null
        value is SpecArg.Unknown -> value.stop
        value is SpecArg.Variable -> {
            val variable = variable(value.node)
            val readable = env.context != Env.Context.MATCH ||
                variable in (state.prematch as InMatch).read ||
                (variable in state.read && variable !in original.read)

            if (readable) null else Expansion.Error("undefined_var_in_spec", segment)
        }
        else -> Expansion.Error("bad_size_argument", segment)
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
    ProgressManager.checkCanceled()

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
internal class Part(
    /** Where an `unsized_binary` for this part is reported. */
    val at: ElixirAst,
    /** Whether the part's value is a binary once expanded, which is what `is_binary/1` asks of it. */
    val binary: Boolean,
    /** `binary` or `bitstring` when that is the whole spec built. */
    val alone: String?,
)

/**
 * What an expanded bitstring builds, for the bitstring that nests it.
 *
 * @property alignment modulo 8, or `null` where it is `unknown`
 */
internal class BitstringParts(val parts: List<Part>, val alignment: Int?)

private sealed interface Described {
    /** Whether a nested bitstring's parts are spliced in, in place of the segment. */
    val spliced: Boolean get() = false

    /** @property alignment modulo 8, or `null` where it is `unknown` */
    class Segment(val parts: List<Part>, val alignment: Int?, override val spliced: Boolean = false) : Described

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

/** Whether [shape], a segment's value, expanded to a bitstring: one written as `<<>>`, or a macro's output. */
private fun isBuilt(shape: ElixirAst, run: Run) = isBitstring(shape) || shape in run.bitstrings

/** `expr_type/1` of a segment's value, from [shape] and from [value], its expanded term where one is at hand. */
private fun exprType(shape: ElixirAst, value: Term?, run: Run) =
    when {
        shape is ElixirAst.Literal.Integer || value is Term.Integer -> "integer"
        shape is ElixirAst.Literal.Float || value is Term.NonTuple -> "float"
        isBuilt(shape, run) -> "bitstring"
        shape is ElixirAst.Literal.Binary || value is Term.Binary -> "binary"
        else -> "default"
    }

/** A segment without `::`, whose spec `infer_spec/2` takes from its value, and which leaves the alignment alone. */
private fun describeBare(
    segment: ElixirAst,
    metaNode: ElixirAst,
    shape: ElixirAst,
    value: Term?,
    matchSize: Boolean,
    env: Env,
    run: Run,
    report: Reporter,
): Described {
    val exprType = exprType(shape, value, run)
    val described = concat(metaNode, shape, exprType == "binary", exprType.takeIf { it in BINARIES }, 0, matchSize, run, report)

    recordSegment(
        segment,
        ModuleClauses.Segment(mergedType(exprType, null), null, null, false, "big", pinned = false, described.spliced),
        env,
        run,
    )

    return described
}

/** `expand_specs/7` from a segment's expanded spec arguments, then `concat_or_prepend_bitstring/6`. */
private fun describeTyped(
    segment: ElixirAst,
    shape: ElixirAst,
    value: Term?,
    specs: Specs.Expanded,
    matchSize: Boolean,
    env: Env,
    run: Run,
    report: Reporter,
): Described {
    val level = run.level
    val args = specs.args

    fun ended(site: ErrorSite) = report(site, segment)?.let(Described::Ended)

    /** A site whose helper's return value `expand_specs/7` can't match. */
    fun crashed(site: ErrorSite) = ended(site) ?: Described.Unported(segment)

    val exprType = exprType(shape, value, run)
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
                // `Unit /= 1` compares by value, which a float's isn't kept.
                merged == "bitstring" && unit is SpecArg.Float -> Described.Unported(segment)
                merged == "bitstring" && unit != null && unit.number?.compareTo(BigDecimal.ONE) != 0 ->
                    ended(ErrorSite.BITTYPE_MISMATCH_UNIT)
                sign != null -> ended(ErrorSite.BITTYPE_SIGNED)
                else -> null
            }?.let { return it }
        else -> {
            // `valid_float_size/1` reads it.
            if (merged == "float" && size is SpecArg.Unknown) return Described.Ended(size.stop)

            val integerSize = size.integer

            if (integerSize != null && unit != null && unit.number == null && unit !is SpecArg.Float) {
                return Described.NumberSizeRaises
            }

            val numberSize = integerSize?.let { s -> if (unit == null) s else unit.integer?.let { s * it } }

            if (merged == "float" && numberSize != null) {
                if (numberSize !in validFloatSizes(level)) return crashed(ErrorSite.BITTYPE_FLOAT_SIZE)
            } else if (size == null && unit != null) {
                return crashed(ErrorSite.BITTYPE_UNIT)
            }
        }
    }

    val endianness = ((args["endianness"] as SpecArg.Literal?)?.term as OtpErlangAtom?)?.atomValue() ?: "big"
    val signed = ((sign as SpecArg.Literal?)?.term as OtpErlangAtom?)?.atomValue() == "signed"

    // `size_and_unit/5` drops a literal's size and unit, so they don't stop it being spliced in.
    val alone = merged.takeIf { it in BINARIES && !(sizeOrUnit && exprType !in BINARIES) && !inferSize }
    val described = concat(segment, shape, exprType == "binary", alone, alignment(merged, size, unit), matchSize, run, report)
    val pinned = inferSize && merged in BINARIES && !sizeOrUnit

    recordSegment(
        segment,
        ModuleClauses.Segment(merged, specs.size, unit.integer, signed, endianness, pinned, described.spliced),
        env,
        run,
    )

    return described
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
 * @param binary whether the segment's value is a binary once expanded
 * @param matchSize whether this segment needs a size: it is in a pattern and another follows it, or it is in a
 *   generator's pattern
 */
private fun concat(
    at: ElixirAst,
    shape: ElixirAst,
    binary: Boolean,
    alone: String?,
    alignment: Int?,
    matchSize: Boolean,
    run: Run,
    report: Reporter,
): Described {
    val self = Described.Segment(listOf(Part(at, binary, alone)), alignment)
    if (!isBuilt(shape, run)) return self

    val inner = run.bitstrings[shape] ?: return Described.Unported(shape)
    if (inner.parts.isEmpty()) return Described.Segment(emptyList(), alignment, spliced = true)

    if (matchSize) {
        val last = inner.parts.last()

        if ((last.alone == "binary" && !last.binary) || last.alone == "bitstring") {
            report(ErrorSite.UNSIZED_BINARY_NESTED, last.at)?.let { return Described.Ended(it) }
        }
    }

    return when (alone) {
        "binary" ->
            when (inner.alignment) {
                null -> self
                0 -> Described.Segment(inner.parts, alignment, spliced = true)
                else ->
                    report(ErrorSite.UNALIGNED_BINARY, at)?.let(Described::Ended)
                        ?: Described.Segment(inner.parts, alignment, spliced = true)
            }
        "bitstring" -> Described.Segment(inner.parts, alignment, spliced = true)
        else -> self
    }
}
