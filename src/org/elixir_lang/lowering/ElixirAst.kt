package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/**
 * Elixir's AST, as `Code.string_to_quoted` returns it, where every node keeps the source range it was lowered from
 * and holds no PSI.
 */
sealed class ElixirAst {
    abstract val meta: Meta

    /**
     * `{callee, meta, arguments}`: a local call's callee is an atom, a remote call's is the `.` call.
     *
     * @property arguments `null` for the context that a variable, or a bare name that may be a local call, has in
     *   place of arguments
     * @property context that context, read only when [arguments] is `null`; the lowering always gives
     *   [VariableContext.Nil], and only the expander builds a [VariableContext.Atom]
     */
    class Call(
        override val meta: Meta,
        val callee: ElixirAst,
        val arguments: List<ElixirAst>?,
        val context: VariableContext = VariableContext.Nil,
    ) : ElixirAst()

    sealed class VariableContext {
        data object Nil : VariableContext()

        data class Atom(val name: String) : VariableContext()
    }

    /** `{:__aliases__, meta, segments}`: atoms, after an expression when the alias starts with one (`__MODULE__.A`). */
    class Alias(override val meta: Meta, val segments: List<ElixirAst>) : ElixirAst()

    /** A term that is its own AST. Elixir gives it no metadata, so only [meta]'s position and origin are kept. */
    sealed class Literal : ElixirAst() {
        class Atom(override val meta: Meta, val name: String) : Literal()
        class Integer(override val meta: Meta, val value: BigInteger) : Literal()
        class Float(override val meta: Meta, val value: Double) : Literal()

        /** A binary, which escapes can make invalid UTF-8. */
        class Binary(override val meta: Meta, val bytes: ByteArray) : Literal()
    }

    /** A list, which is its own AST; a charlist is a list of integers. */
    class ListNode(override val meta: Meta, val elements: List<ElixirAst>) : ElixirAst()

    /** A pair is its own AST; any other size is `{:{}, meta, elements}`. */
    class Tuple(override val meta: Meta, val elements: List<ElixirAst>) : ElixirAst()

    /** `{:__block__, meta, expressions}`. */
    class Block(override val meta: Meta, val expressions: List<ElixirAst>) : ElixirAst()

    /** Stands for source that has no AST of its own yet. */
    class Placeholder(override val meta: Meta, val reason: Reason) : ElixirAst() {
        sealed class Reason {
            /** No lowering exists yet for [shape]. */
            class Unlowered(val shape: Class<out PsiElement>) : Reason()

            /** Broken code: an error element the parser recovered from, or a shape Elixir's parser rejects. */
            object Error : Reason()

            /** A value only a compiler can build, such as the bytes PCRE compiles a regular expression to. */
            object Compiled : Reason()
        }

        /** Whether an expansion cannot go on past this node. A [Reason.Compiled] node is a value, so it never stops one. */
        val stopsExpansion: Boolean get() = reason !is Reason.Compiled
    }

    /** Whether Elixir gives this node metadata of its own, which a parent can add keys to. */
    internal fun hasMetadata(): Boolean =
        when (this) {
            is Call, is Alias, is Block, is Placeholder -> true
            is Tuple -> elements.size != 2
            is Literal, is ListNode -> false
        }

    /** The AST as `Code.string_to_quoted` under [options] returns it; a [Placeholder] becomes `__cursor__`. */
    fun toOtp(options: ParserOptions = ParserOptions.DEFAULT): OtpErlangObject =
        when (this) {
            is Call -> tuple(
                callee.toOtp(options),
                meta.toOtp(options),
                arguments?.let { list(it, options) } ?: OtpErlangAtom(
                    when (context) {
                        VariableContext.Nil -> "nil"
                        is VariableContext.Atom -> context.name
                    }
                )
            )
            is Alias -> tuple(OtpErlangAtom("__aliases__"), meta.toOtp(options), list(segments, options))
            is Literal.Atom -> OtpErlangAtom(name)
            is Literal.Integer -> OtpErlangLong(value)
            is Literal.Float -> OtpErlangDouble(value)
            is Literal.Binary -> OtpErlangBinary(bytes)
            is ListNode -> list(elements, options)
            is Tuple ->
                if (elements.size == 2) {
                    tuple(elements[0].toOtp(options), elements[1].toOtp(options))
                } else {
                    tuple(OtpErlangAtom("{}"), meta.toOtp(options), list(elements, options))
                }
            is Block -> tuple(OtpErlangAtom("__block__"), meta.toOtp(options), list(expressions, options))
            is Placeholder -> tuple(OtpErlangAtom("__cursor__"), meta.toOtp(options), OtpErlangList())
        }

    companion object {
        /**
         * The node quoted [term] is, with [origin] as every node's origin: [toOtp]'s inverse under
         * [ParserOptions.DEFAULT]. Null for a term no quoted expression is, such as a map or a bitstring.
         */
        fun fromOtp(term: OtpErlangObject, origin: TextRange): ElixirAst? {
            val bare = { Meta(origin, NO_POSITION, NO_POSITION) }

            return when (term) {
                is OtpErlangAtom -> Literal.Atom(bare(), term.atomValue())
                is OtpErlangLong -> Literal.Integer(bare(), term.bigIntegerValue())
                is OtpErlangDouble -> Literal.Float(bare(), term.doubleValue())
                is OtpErlangBinary -> Literal.Binary(bare(), term.binaryValue())
                is OtpErlangString, is OtpErlangList -> elements(term, origin)?.let { ListNode(bare(), it) }
                is OtpErlangTuple -> fromTuple(term, origin)
                else -> null
            }
        }

        /** The elements of the proper list [term], which a list of bytes decodes to an [OtpErlangString] as. */
        private fun elements(term: OtpErlangObject, origin: TextRange): List<ElixirAst>? =
            when (term) {
                is OtpErlangString ->
                    term.stringValue().codePoints().toArray().map {
                        Literal.Integer(Meta(origin, NO_POSITION, NO_POSITION), it.toBigInteger())
                    }
                is OtpErlangList -> term.takeIf { it.lastTail == null }?.elements()?.map { fromOtp(it, origin) ?: return null }
                else -> null
            }

        private fun fromTuple(tuple: OtpErlangTuple, origin: TextRange): ElixirAst? {
            if (tuple.arity() == 2) {
                return Tuple(Meta(origin, NO_POSITION, NO_POSITION), tuple.elements().map { fromOtp(it, origin) ?: return null })
            }
            if (tuple.arity() != 3) return null

            val (head, metaTerm, last) = tuple.elements()
            val meta = meta(metaTerm, origin) ?: return null

            if (last is OtpErlangAtom) {
                val context = last.atomValue()

                return Call(
                    meta,
                    fromOtp(head, origin) ?: return null,
                    null,
                    if (context == "nil") VariableContext.Nil else VariableContext.Atom(context),
                )
            }

            val arguments = elements(last, origin) ?: return null

            return when (head) {
                // `toOtp` writes a pair as `{a, b}`, so `{:{}, meta, [a, b]}` stays a call.
                TUPLE if arguments.size != 2 -> Tuple(meta, arguments)
                ALIASES -> Alias(meta, arguments)
                BLOCK -> Block(meta, arguments)
                else -> Call(meta, fromOtp(head, origin) ?: return null, arguments)
            }
        }

        /**
         * [term]'s keys, with its `line` as a [Meta.Key.Location] where the line is, as a lowered node's, so its
         * location reads the same. `column` stays an entry: [ParserOptions.DEFAULT] writes a location's line only.
         */
        private fun meta(term: OtpErlangObject, origin: TextRange): Meta? {
            val keys = keywords(term) ?: return null
            val integer = { name: String ->
                keys.firstOrNull { it.name == name }?.let { (it.value as? Meta.Value.Integer)?.value?.toInt() }
            }
            val line = integer("line")
            val position = Meta.Position(line ?: 0, integer("column") ?: 0)

            return Meta(
                origin,
                position,
                position,
                keys.map { key -> if (line != null && key.name == "line") Meta.Key.Location(position) else key },
            )
        }

        /** Each entry of the keyword list [term], or null for any other term. */
        private fun keywords(term: OtpErlangObject): List<Meta.Key.Entry>? =
            (term as? OtpErlangList)?.takeIf { it.lastTail == null }?.elements()?.map { element ->
                val (key, value) = (element as? OtpErlangTuple)?.takeIf { it.arity() == 2 }?.elements() ?: return null

                Meta.Key.Entry((key as? OtpErlangAtom)?.atomValue() ?: return null, value(value) ?: return null)
            }

        private fun value(term: OtpErlangObject): Meta.Value? =
            when (term) {
                is OtpErlangAtom -> Meta.Value.Atom(term.atomValue())
                is OtpErlangLong -> term.takeIf { it.isLong }?.let { Meta.Value.Integer(it.longValue()) }
                is OtpErlangBinary -> utf8(term.binaryValue())?.let(Meta.Value::Binary)
                is OtpErlangList ->
                    keywords(term)?.let(Meta.Value::Keywords)
                        ?: term.takeIf { it.lastTail == null }?.elements()?.map { value(it) ?: return null }?.let(Meta.Value::List)
                is OtpErlangTuple -> Meta.Value.Tuple(term.elements().map { value(it) ?: return null })
                else -> null
            }

        /** [bytes] as text, or null where they aren't UTF-8, which a [Meta.Value.Binary] couldn't give back. */
        private fun utf8(bytes: ByteArray): String? =
            try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                null
            }

        private val NO_POSITION = Meta.Position(0, 0)
        private val TUPLE = OtpErlangAtom("{}")
        private val ALIASES = OtpErlangAtom("__aliases__")
        private val BLOCK = OtpErlangAtom("__block__")

        private fun tuple(vararg elements: OtpErlangObject) = OtpErlangTuple(elements)

        private fun list(elements: List<ElixirAst>, options: ParserOptions): OtpErlangObject {
            val terms = elements.map { it.toOtp(options) }
            val bytes = terms.mapNotNull { term -> (term as? OtpErlangLong)?.takeIf { it.signum() >= 0 && it.bitLength() <= 8 } }

            return if (terms.isNotEmpty() && bytes.size == terms.size) {
                OtpErlangString(String(CharArray(bytes.size) { bytes[it].longValue().toInt().toChar() }))
            } else {
                OtpErlangList(terms.toTypedArray())
            }
        }
    }
}
