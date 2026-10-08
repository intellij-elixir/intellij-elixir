package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.psi.PsiElement
import java.math.BigInteger

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

    private companion object {
        fun tuple(vararg elements: OtpErlangObject) = OtpErlangTuple(elements)

        fun list(elements: List<ElixirAst>, options: ParserOptions): OtpErlangObject {
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
