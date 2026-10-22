package org.elixir_lang.expander.interpreter

import com.ericsson.otp.erlang.OtpErlangBitstr
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.expander.GUARD_OPERATORS
import org.elixir_lang.expander.ModuleClauses
import org.elixir_lang.expander.expandedShape
import org.elixir_lang.expander.isCall
import org.elixir_lang.expander.isVariable
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_SIZE_EXPANDED_AS_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.BIT_GENERATOR_SKIP_KEEPS_DEFAULT_SIZE_ONLY
import org.elixir_lang.language_level.ElixirLanguageFeature.BIT_GENERATOR_SKIP_KEEPS_OUTER_SIZES
import org.elixir_lang.lowering.ElixirAst
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import kotlin.math.abs
import kotlin.math.round

/**
 * The bitstrings of a clause: an expanded one's, whose every segment is `value::spec` with its spec built out in full,
 * or a source one's, whose every segment's spec its expansion recorded.
 */
internal class Bits(private val evaluation: Evaluation) {
    private enum class Type(val utf: Boolean = false) {
        INTEGER, FLOAT, BINARY, BITSTRING, UTF8(utf = true), UTF16(utf = true), UTF32(utf = true)
    }

    /** @property pinned from source, the pin whose value's size is the segment's, where its expansion inferred one */
    private class Spec(
        val type: Type,
        val size: ElixirAst?,
        val unit: Int?,
        val signed: Boolean,
        val little: Boolean,
        val pinned: ElixirAst? = null,
    )

    /** Which values a generator's skip clause matches as `_`, past a run its pattern doesn't match. */
    private enum class Skip {
        /** `v3_core:skip_segments/3`: each but a variable, which is kept so a later size can read it. */
        LITERALS,

        /** `elixir_erl_for:no_unbound_var/3`: each. */
        ALL,
    }

    /** `<<segments>>` built. */
    fun build(segments: List<ElixirAst>, frame: Frame): OtpErlangObject {
        val writer = Writer()
        var unknown = false
        val writes = ArrayList<() -> Unit>()
        // `v3_core` evaluates every segment's value and then its size, in order, before it writes any: each is a part.
        val turn = InTurn<Thrown>()

        for (segment in spliced(segments, frame)) {
            var read: Pair<ElixirAst, Spec>? = null
            var value: OtpErlangObject? = null

            turn.take(evaluation.part {
                val (written, spec) = segment(segment, frame)
                val valueNode = frame.expanded(written)

                read = valueNode to spec
                evaluation.eval(valueNode, frame).also { value = it }
            })
            if (turn.raised) break

            // Where the spec declined, the segment has no size to run.
            val (valueNode, spec) = read ?: continue

            if (valueNode is ElixirAst.Literal.Binary && spec.type.utf) {
                writes += { for (code in codePoints(valueNode)) write(writer, integer(code.toLong()), spec, null) }
                continue
            }

            var evaluatedSize: OtpErlangObject? = null
            val sizeNode = spec.size

            if (sizeNode != null) {
                turn.take(evaluation.part { evaluation.eval(sizeNode, frame).also { evaluatedSize = it } })
                if (turn.raised) break
            }

            // Where a part declined, nothing is written.
            val segmentValue = value ?: continue
            val segmentSize = evaluatedSize

            writes += {
                val size = segmentSize?.let(::seen)

                if (segmentValue === UnknownBinary && takesWhole(spec, size, null)) {
                    unknown = true
                } else if (!write(writer, seen(segmentValue), spec, size)) {
                    evaluation.error(atom("badarg"))
                }
            }
        }

        when (val evaluated = turn.outcome) {
            is PartsOutcome.Declines -> throw evaluated.declined
            is PartsOutcome.Raises -> throw evaluated.raise
            is PartsOutcome.Values -> writes.forEach { it() }
        }

        return when {
            !unknown -> writer.bitstring()
            writer.aligned -> UnknownBinary
            else -> Unknown
        }
    }

    /**
     * Whether a segment of [spec] and [size] takes a bitstring of [length] bits whole, as one with no size does where
     * its unit divides the length. A `null` length is any binary, whatever its bytes.
     */
    private fun takesWhole(spec: Spec, size: OtpErlangObject?, length: Int?) =
        size == null && (spec.type == Type.BINARY || spec.type == Type.BITSTRING) && (length ?: 8) % unit(spec) == 0

    /** [values] joined, as a comprehension `into: ""` joins them. */
    fun concat(values: List<OtpErlangObject>): OtpErlangObject {
        val writer = Writer()

        for (value in values) {
            if (value is UnknownValue) return value
            if (value !is OtpErlangBitstr) evaluation.error(tuple(atom("badarg"), value))

            writer.bitstring(value, bitLength(value))
        }

        return writer.bitstring()
    }

    /** Whether `<<segments>>` matches [value], binding its variables in [pending]. */
    fun match(
        segments: List<ElixirAst>,
        value: OtpErlangObject,
        frame: Frame,
        pending: PatternBindings,
    ): Boolean {
        val bitstring = value as? OtpErlangBitstr ?: return false
        val length = bitLength(bitstring)

        return matchFrom(spliced(segments, frame), bitstring, 0, length, frame, pending) == length
    }

    /**
     * [emit] once per run of the [written] segments matched from the start of [value], each in a scope binding its variables. A run
     * the segments don't match is skipped where its size is known, and ends the generator otherwise. [comprehension]
     * says whether `elixir_erl_for` builds an Erlang comprehension, as it does where every generator is a bitstring's,
     * or a reduce, whose sizes may read only the variables of [outer], the scope the `for` starts in.
     */
    fun generate(
        written: List<ElixirAst>,
        value: OtpErlangObject,
        frame: Frame,
        comprehension: Boolean,
        outer: Frame,
        emit: (Frame) -> Unit,
    ) {
        seen(value)

        val segments = spliced(written, frame)
        val bitstring = value as? OtpErlangBitstr
            ?: evaluation.error(tuple(atom(if (comprehension) "bad_generator" else "badarg"), value))
        val length = bitLength(bitstring)
        val skip = when {
            comprehension -> Skip.LITERALS
            reduceSkips(segments, frame, outer) -> Skip.ALL
            else -> null
        }
        var offset = 0

        while (offset < length) {
            ProgressManager.checkCanceled()

            val scope = frame.copy()
            val pending = PatternBindings(fresh = true)
            val end = matchFrom(segments, bitstring, offset, length, scope, pending)

            if (end == null) {
                val skipped = skip?.let { matchFrom(segments, bitstring, offset, length, frame.copy(), PatternBindings(fresh = true), it) } ?: return

                // Elixir loops for ever on a run that takes no bits.
                if (skipped == offset) decline("a generator whose segments take no bits")

                offset = skipped
                continue
            }
            if (end == offset) decline("a generator whose segments take no bits")

            scope.vars.putAll(pending.vars)
            emit(scope)
            offset = end
        }
    }

    /**
     * Whether `elixir_erl_for` gives a reduce over [segments] its skip clause, which it builds where it keeps every
     * segment's size: the default size always; from [BIT_GENERATOR_SKIP_KEEPS_OUTER_SIZES] any [knownSize]; in
     * [BIT_GENERATOR_SKIP_KEEPS_DEFAULT_SIZE_ONLY] no other; before both, any size but a variable.
     */
    private fun reduceSkips(segments: List<ElixirAst>, frame: Frame, outer: Frame): Boolean {
        val level = evaluation.run.level

        return segments.all { segment ->
            val size = segment(segment, frame).second.size

            when {
                size == null -> true
                BIT_GENERATOR_SKIP_KEEPS_OUTER_SIZES.isSufficient(level) -> knownSize(size, frame, outer)
                BIT_GENERATOR_SKIP_KEEPS_DEFAULT_SIZE_ONLY.isSufficient(level) -> false
                else -> !isVariable(frame.expanded(size))
            }
        }
    }

    /**
     * Whether [size], of a segment in [frame], is one `no_unbound_var_size/2` keeps: an integer, a variable [outer] binds
     * at the version the size reads, or an operator of them, as [operands] reads it. From source, a size its expansion
     * dispatched is judged by what it expanded to: an attribute's value, or a macro's output.
     */
    private fun knownSize(size: ElixirAst, frame: Frame, outer: Frame): Boolean {
        ProgressManager.checkCanceled()

        val expanded = frame.expanded(size)

        if (expanded is ElixirAst.Literal.Integer) return true
        if (isVariable(expanded)) return outer.binding(expanded)?.let { it in outer.vars } == true

        return operands(expanded, frame)?.all { knownSize(it, frame, outer) } == true
    }

    /** [segments] as their expansion left them: from source, a nested bitstring it spliced in is its own segments. */
    private fun spliced(segments: List<ElixirAst>, frame: Frame): List<ElixirAst> =
        if (!frame.source) {
            segments
        } else {
            segments.flatMap { segment ->
                if (frame.module.dispatches.segmentOf(segment)?.spliced == true) {
                    val nested = expandedShape(frame.expanded(valueOf(segment))) as ElixirAst.Call

                    spliced(nested.arguments.orEmpty(), frame)
                } else {
                    listOf(segment)
                }
            }
        }

    /**
     * The operands of [size] where `elixir_erl_pass` translates it to `{op, _, Op, Left, Right}`: a call of an `:erlang`
     * operator `elixir_utils:guard_op/2` holds for, or from source a call its expansion dispatched to one.
     */
    private fun operands(size: ElixirAst, frame: Frame): List<ElixirAst>? {
        val call = size as? ElixirAst.Call ?: return null
        val operands = call.arguments?.takeIf { it.size == 2 } ?: return null
        val (module, name) = when (val callee = call.callee) {
            is ElixirAst.Literal.Atom ->
                (frame.module.dispatches.of(call) as? ModuleClauses.Dispatched.Remote)?.let { it.module to it.name }
            else -> (callee as? ElixirAst.Call)?.takeIf { isCall(it, ".", 2) }?.arguments?.let { (module, name) ->
                (module as? ElixirAst.Literal.Atom)?.name to (name as? ElixirAst.Literal.Atom)?.name
            }
        } ?: return null

        return operands.takeIf { module == "erlang" && name in GUARD_OPERATORS }
    }

    /** The offset after [segments], matched against [value] from [start], or `null` where they don't match. */
    private fun matchFrom(
        segments: List<ElixirAst>,
        value: OtpErlangBitstr,
        start: Int,
        length: Int,
        frame: Frame,
        pending: PatternBindings,
        skip: Skip? = null,
    ): Int? {
        val reader = Reader(value.binaryValue())
        var offset = start

        for (segment in segments) {
            val (written, spec) = segment(segment, frame)
            val pattern = frame.expanded(written)
            val any = skip == Skip.ALL || skip == Skip.LITERALS && !isVariable(pattern)

            if (pattern is ElixirAst.Literal.Binary && spec.type.utf) {
                for (code in codePoints(pattern)) {
                    val (read, next) = read(reader, offset, length, spec, null) ?: return null

                    if (!any && (read as OtpErlangLong).longValue() != code.toLong()) return null

                    offset = next
                }
                continue
            }

            val size = spec.size?.let {
                val scope = frame.copy().also { scope -> scope.vars.putAll(pending.vars) }

                if (BITSTRING_SIZE_EXPANDED_AS_GUARD.isSufficient(evaluation.run.level)) {
                    evaluation.guardValue(it, scope) ?: return null
                } else {
                    evaluation.eval(it, scope)
                }
            } ?: spec.pinned?.let { pinnedSize(evaluation.eval(it, frame), spec) }
            val bits = when (size) {
                // A literal binary matches its own bytes, not the rest.
                null -> (pattern as? ElixirAst.Literal.Binary)?.let { it.bytes.size * 8 }
                is UnknownValue -> decline("a segment of unknown size")
                // No type reads more bits than remain.
                else -> (size as? OtpErlangLong)?.let { bits(it, spec) }?.takeIf { it <= BigInteger.valueOf((length - offset).toLong()) }
                    ?.toInt() ?: return null
            }
            val (read, next) = read(reader, offset, length, spec, bits) ?: return null

            if (!any && !evaluation.match(pattern, read, frame, pending)) return null

            offset = next
        }

        return offset
    }

    private fun read(reader: Reader, offset: Int, length: Int, spec: Spec, bits: Int?): Pair<OtpErlangObject, Int>? {
        val remaining = length - offset

        return when (spec.type) {
            Type.INTEGER -> {
                val count = bits ?: 8

                if (count > remaining) return null
                // Past it, from OTP 26 the VM aborts, and before it the limit depends on the patch release.
                if (!fitsEveryVmInteger(count)) decline("an integer segment of $count bits")

                integer(reader.integer(offset, count, spec.signed, spec.little)) to offset + count
            }
            Type.FLOAT -> {
                val count = bits ?: 64

                if (count > remaining) return null

                val raw = reader.integer(offset, count, false, spec.little)
                val double = when (count) {
                    16 -> halfToDouble(raw.toInt())
                    32 -> java.lang.Float.intBitsToFloat(raw.toInt()).toDouble()
                    64 -> java.lang.Double.longBitsToDouble(raw.toLong())
                    else -> return null
                }

                if (double.isNaN() || double.isInfinite()) return null

                OtpErlangDouble(double) to offset + count
            }
            Type.BINARY, Type.BITSTRING -> {
                val count = bits ?: remaining

                if (count > remaining || bits == null && !takesWhole(spec, null, remaining)) return null

                reader.bitstring(offset, count) to offset + count
            }
            Type.UTF8 -> utf8(reader, offset, remaining)
            Type.UTF16 -> utf16(reader, offset, remaining, spec.little)
            Type.UTF32 -> {
                if (remaining < 32) return null

                val code = reader.integer(offset, 32, false, spec.little).toInt()

                if (!isScalar(code)) return null

                integer(code.toLong()) to offset + 32
            }
        }
    }

    private fun utf8(reader: Reader, offset: Int, remaining: Int): Pair<OtpErlangObject, Int>? {
        if (remaining < 8) return null

        val first = reader.integer(offset, 8, signed = false, little = false).toInt()
        val (count, initial) = when {
            first < 0x80 -> 1 to first
            first and 0xE0 == 0xC0 -> 2 to (first and 0x1F)
            first and 0xF0 == 0xE0 -> 3 to (first and 0x0F)
            first and 0xF8 == 0xF0 -> 4 to (first and 0x07)
            else -> return null
        }

        if (remaining < count * 8) return null

        var code = initial

        for (index in 1 until count) {
            val byte = reader.integer(offset + index * 8, 8, signed = false, little = false).toInt()

            if (byte and 0xC0 != 0x80) return null

            code = (code shl 6) or (byte and 0x3F)
        }

        val minimum = when (count) {
            1 -> 0
            2 -> 0x80
            3 -> 0x800
            else -> 0x10000
        }

        if (code < minimum || !isScalar(code)) return null

        return integer(code.toLong()) to offset + count * 8
    }

    private fun utf16(reader: Reader, offset: Int, remaining: Int, little: Boolean): Pair<OtpErlangObject, Int>? {
        if (remaining < 16) return null

        val high = reader.integer(offset, 16, false, little).toInt()

        if (high !in 0xD800..0xDFFF) return integer(high.toLong()) to offset + 16
        if (high > 0xDBFF || remaining < 32) return null

        val low = reader.integer(offset + 16, 16, false, little).toInt()

        if (low !in 0xDC00..0xDFFF) return null

        return integer((0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)).toLong()) to offset + 32
    }

    /** Whether [value], with [size], is written as [spec] says, `false` where Erlang raises `badarg`. */
    private fun write(writer: Writer, value: OtpErlangObject, spec: Spec, size: OtpErlangObject?): Boolean {
        val bits = when (size) {
            null -> null
            else -> {
                val bits = (size as? OtpErlangLong)?.let { bits(it, spec) } ?: return false

                // Past an `Int`, a binary segment is longer than its value and a float has no such size; an integer is too long to build.
                bits.takeIf { it.bitLength() < Int.SIZE_BITS }?.toInt()
                    ?: if (spec.type == Type.INTEGER) decline("an integer segment of over ${Int.MAX_VALUE} bits") else return false
            }
        }

        return when (spec.type) {
            Type.INTEGER -> {
                val integer = (value as? OtpErlangLong)?.bigIntegerValue() ?: return false

                writer.integer(integer, bits ?: 8, spec.little)
                true
            }
            Type.FLOAT -> {
                val double = when (value) {
                    is OtpErlangLong -> value.bigIntegerValue().toDouble().takeIf { it.isFinite() } ?: return false
                    is OtpErlangDouble -> value.doubleValue()
                    else -> return false
                }
                val raw = when (bits ?: 64) {
                    16 -> BigInteger.valueOf(doubleToHalf(double).toLong())
                    32 -> BigInteger.valueOf(java.lang.Float.floatToIntBits(double.toFloat()).toLong() and 0xFFFFFFFFL)
                    64 -> BigInteger.valueOf(java.lang.Double.doubleToLongBits(double))
                    else -> return false
                }

                writer.integer(raw, bits ?: 64, spec.little)
                true
            }
            Type.BINARY, Type.BITSTRING -> {
                val bitstring = value as? OtpErlangBitstr ?: return false
                val length = bitLength(bitstring)

                when {
                    takesWhole(spec, size, length) -> writer.bitstring(bitstring, length)
                    bits != null && bits <= length -> writer.bitstring(bitstring, bits)
                    else -> return false
                }
                true
            }
            Type.UTF8, Type.UTF16, Type.UTF32 -> {
                val code = (value as? OtpErlangLong)?.takeIf { it.isLong }?.longValue()?.takeIf { it in 0..0x10FFFF }?.toInt()
                    ?: return false

                if (!isScalar(code)) return false

                val bytes = when (spec.type) {
                    Type.UTF8 -> String(Character.toChars(code)).toByteArray(Charsets.UTF_8)
                    Type.UTF16 -> String(Character.toChars(code)).toByteArray(if (spec.little) Charsets.UTF_16LE else Charsets.UTF_16BE)
                    else -> BigInteger.valueOf(code.toLong()).let { code32 ->
                        ByteArray(4) { index -> code32.shiftRight(8 * (3 - index)).toByte() }.let { if (spec.little) it.reversedArray() else it }
                    }
                }

                writer.bitstring(bitstring(bytes, 0), bytes.size * 8)
                true
            }
        }
    }

    /** [segment]'s value and spec. */
    private fun segment(segment: ElixirAst, frame: Frame): Pair<ElixirAst, Spec> =
        if (frame.source) recorded(segment, frame) else expanded(segment)

    /** A source segment, `value::spec` or a bare value, its spec as its expansion recorded it. */
    private fun recorded(segment: ElixirAst, frame: Frame): Pair<ElixirAst, Spec> {
        val record = frame.module.dispatches.segmentOf(segment)
            ?: decline("a bitstring segment its expansion didn't reach")
        val value = valueOf(segment)
        val type = when (record.type) {
            "integer" -> Type.INTEGER
            "float" -> Type.FLOAT
            "binary" -> Type.BINARY
            "bitstring" -> Type.BITSTRING
            "utf8" -> Type.UTF8
            "utf16" -> Type.UTF16
            "utf32" -> Type.UTF32
            else -> decline("the bitstring type ${record.type}")
        }
        val little = when (record.endianness) {
            "big" -> false
            "little" -> true
            else -> decline("a ${record.endianness}-endian segment")
        }

        val unit = record.unit?.let(::compiledUnit)

        return value to Spec(type, record.size, unit, record.signed, little, value.takeIf { record.pinned })
    }

    /** A unit `erl_bits:type_to_record/1` accepts, `1..256`: past it, the module the segment is in doesn't compile. */
    private fun compiledUnit(unit: BigInteger): Int =
        unit.takeIf { it.signum() > 0 && it <= MAX_UNIT }?.toInt() ?: decline("a bitstring unit of $unit")

    /** A source segment's value: the left of its `::`, or the segment itself. */
    private fun valueOf(segment: ElixirAst): ElixirAst =
        (segment as? ElixirAst.Call)?.takeIf { isCall(it, "::", 2) }?.arguments?.first() ?: segment

    /** The size `expand_specs/7` infers for a pin of [value]: its `byte_size/1` for a binary, else its `bit_size/1`. */
    private fun pinnedSize(value: OtpErlangObject, spec: Spec): OtpErlangObject =
        evaluation.natives.call("erlang", if (spec.type == Type.BINARY) "byte_size" else "bit_size", listOf(value))!!

    /** `{'::', _, [value, spec]}`, its spec read from the `-`-joined parts the expansion built. */
    private fun expanded(segment: ElixirAst): Pair<ElixirAst, Spec> {
        val (value, specNode) = (segment as? ElixirAst.Call)?.takeIf { isCall(it, "::", 2) }?.arguments
            ?: decline("a bitstring segment without a spec")
        var type = Type.INTEGER
        var size: ElixirAst? = null
        var unit: Int? = null
        var signed = false
        var little = false

        for (part in parts(specNode)) {
            val call = part as? ElixirAst.Call ?: decline("a bitstring spec part")
            val name = (call.callee as? ElixirAst.Literal.Atom)?.name ?: decline("a bitstring spec part")
            val args = call.arguments.orEmpty()

            when (name) {
                "integer" -> type = Type.INTEGER
                "float" -> type = Type.FLOAT
                "binary", "bytes" -> type = Type.BINARY
                "bitstring", "bits" -> type = Type.BITSTRING
                "utf8" -> type = Type.UTF8
                "utf16" -> type = Type.UTF16
                "utf32" -> type = Type.UTF32
                "signed" -> signed = true
                "unsigned" -> signed = false
                "big" -> little = false
                "little" -> little = true
                "size" -> size = args.singleOrNull() ?: decline("a bitstring size")
                "unit" -> unit = (args.singleOrNull() as? ElixirAst.Literal.Integer)?.value?.toInt() ?: decline("a bitstring unit")
                else -> decline("the bitstring spec $name")
            }
        }

        return value to Spec(type, size, unit, signed, little)
    }

    private fun parts(spec: ElixirAst): List<ElixirAst> {
        ProgressManager.checkCanceled()

        return if (isCall(spec, "-", 2)) (spec as ElixirAst.Call).arguments!!.flatMap(::parts) else listOf(spec)
    }

    private fun defaultUnit(type: Type) = if (type == Type.BINARY) 8 else 1

    private fun unit(spec: Spec) = spec.unit ?: defaultUnit(spec.type)

    /** The bits a segment of [spec] with [size] takes, `null` for a negative size. */
    private fun bits(size: OtpErlangLong, spec: Spec): BigInteger? =
        size.bigIntegerValue().takeIf { it.signum() >= 0 }?.multiply(BigInteger.valueOf(unit(spec).toLong()))

    /** A literal string's characters, as a utf segment writes or matches one segment per character. */
    private fun codePoints(literal: ElixirAst.Literal.Binary): IntArray {
        val text = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(literal.bytes)).toString()
        } catch (_: CharacterCodingException) {
            decline("a utf segment's literal that isn't UTF-8")
        }

        return text.codePoints().toArray()
    }

    private fun isScalar(code: Int) = code in 0..0x10FFFF && code !in 0xD800..0xDFFF

    private class Writer {
        private var bytes = ByteArray(16)
        private var length = 0

        private fun bit(set: Boolean) {
            ProgressManager.checkCanceled()

            if (length / 8 == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            if (set) bytes[length / 8] = (bytes[length / 8].toInt() or (0x80 ushr (length % 8))).toByte()
            length++
        }

        private fun reserve(count: Int) = reserveBits(length.toLong(), count.toLong())

        /** [value]'s low [count] bits. */
        fun integer(value: BigInteger, count: Int, little: Boolean) {
            reserve(count)

            for (position in 0 until count) bit(value.testBit(valueBit(position, count, little)))
        }

        /** [value]'s first [count] bits. */
        fun bitstring(value: OtpErlangBitstr, count: Int) {
            reserve(count)

            val source = value.binaryValue()

            for (index in 0 until count) bit(source[index / 8].toInt() and (0x80 ushr (index % 8)) != 0)
        }

        /** Whether the bits written so far fill whole bytes. */
        val aligned: Boolean get() = length % 8 == 0

        fun bitstring(): OtpErlangBitstr = bitstring(bytes.copyOf((length + 7) / 8), (8 - length % 8) % 8)
    }

    private class Reader(private val bytes: ByteArray) {
        private fun bit(index: Int) = bytes[index / 8].toInt() and (0x80 ushr (index % 8)) != 0

        /** [count] bits from [offset] as an integer. */
        fun integer(offset: Int, count: Int, signed: Boolean, little: Boolean): BigInteger {
            // Big-endian, as `BigInteger` reads a magnitude, so that each bit is set once.
            val magnitude = ByteArray((count + 7) / 8)

            for (position in 0 until count) {
                ProgressManager.checkCanceled()

                if (bit(offset + position)) {
                    val set = valueBit(position, count, little)
                    val index = magnitude.size - 1 - set / 8

                    magnitude[index] = (magnitude[index].toInt() or (1 shl (set % 8))).toByte()
                }
            }

            val value = BigInteger(1, magnitude)

            return if (signed && count > 0 && value.testBit(count - 1)) value - BigInteger.ONE.shiftLeft(count) else value
        }

        fun bitstring(offset: Int, count: Int): OtpErlangBitstr {
            val writer = Writer()

            for (index in 0 until count) writer.integer(if (bit(offset + index)) BigInteger.ONE else BigInteger.ZERO, 1, false)

            return writer.bitstring()
        }
    }

    private companion object {
        val MAX_UNIT: BigInteger = BigInteger.valueOf(256)

        /**
         * The bit of a [count]-bit integer at [position] in its segment. Little-endian, its whole bytes come lowest first,
         * then its top `count % 8` bits, as `<<0x123::12-little>>` is `<<0x23, 0x1::4>>`.
         */
        fun valueBit(position: Int, count: Int, little: Boolean): Int {
            val whole = count / 8 * 8

            return when {
                !little -> count - 1 - position
                position < whole -> position / 8 * 8 + 7 - position % 8
                else -> count - 1 - (position - whole)
            }
        }

        fun bitLength(value: OtpErlangBitstr) = value.binaryValue().size * 8 - value.pad_bits()

        /** The IEEE 754 binary16 [half] as a double. */
        fun halfToDouble(half: Int): Double {
            val sign = if (half and 0x8000 != 0) -1.0 else 1.0
            val exponent = (half shr 10) and 0x1F
            val fraction = half and 0x3FF

            return sign * when (exponent) {
                0 -> Math.scalb(fraction.toDouble(), -24)
                0x1F -> if (fraction == 0) Double.POSITIVE_INFINITY else Double.NaN
                else -> Math.scalb((fraction or 0x400).toDouble(), exponent - 25)
            }
        }

        /**
         * [value] rounded to the nearest IEEE 754 binary16, ties to even, as C's `(_Float16)` cast does: one too large
         * is infinite.
         */
        fun doubleToHalf(value: Double): Int {
            val sign = if (value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)) 0x8000 else 0
            val magnitude = abs(value)

            if (magnitude == 0.0) return sign
            // Halves are multiples of 2^-24 below 2^-14, and have 11 significant bits above it.
            val exponent = maxOf(Math.getExponent(magnitude), -14)
            val units = round(Math.scalb(magnitude, 10 - exponent)).toLong()
            val (biased, significand) =
                if (units == 0x800L) (exponent + 16) to 0x400L else (exponent + 15) to units

            return when {
                biased >= 0x1F -> sign or 0x7C00
                significand < 0x400L -> sign or significand.toInt()
                else -> sign or (biased shl 10) or (significand.toInt() and 0x3FF)
            }
        }
    }
}

/** The most bits a bitstring the interpreter builds holds: 16 MiB. */
private const val MAX_BITS = 1 shl 27

/**
 * Declines a bitstring of [held] bits growing by [count] past [MAX_BITS]. The VM would build it, but the interpreter
 * holds it on the IDE's heap and copies it as it grows, and no macro's output needs one that large.
 */
internal fun reserveBits(held: Long, count: Long) {
    if (count > MAX_BITS - held) decline("a bitstring of over $MAX_BITS bits")
}
