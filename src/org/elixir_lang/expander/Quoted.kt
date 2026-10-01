package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst
import java.math.BigInteger

/** A term a `quote` evaluates to, with no source origin: the quoted AST, its metadata included. */
sealed class Quoted {
    data class Atom(val name: String) : Quoted()

    data class Integer(val value: BigInteger) : Quoted()

    data class Float(val value: Double) : Quoted()

    class Binary(val bytes: ByteArray) : Quoted() {
        override fun equals(other: Any?) = other is Binary && bytes.contentEquals(other.bytes)

        override fun hashCode() = bytes.contentHashCode()
    }

    data class List(val elements: kotlin.collections.List<Quoted>) : Quoted()

    data class Tuple(val elements: kotlin.collections.List<Quoted>) : Quoted()

    /** A term none of the others can hold, such as a pid or a function. */
    data object Unknown : Quoted()
}

/**
 * The term [escaped], an expression built only of literals, tuples and lists, evaluates to, or `null` when it holds
 * anything else, such as a variable from `unquote` or a remote call.
 */
fun quotedValue(escaped: ElixirAst): Quoted? =
    when (escaped) {
        is ElixirAst.Literal.Atom -> Quoted.Atom(escaped.name)
        is ElixirAst.Literal.Integer -> Quoted.Integer(escaped.value)
        is ElixirAst.Literal.Float -> Quoted.Float(escaped.value)
        is ElixirAst.Literal.Binary -> Quoted.Binary(escaped.bytes)
        is ElixirAst.ListNode -> Quoted.List(escaped.elements.map { quotedValue(it) ?: return null })
        is ElixirAst.Tuple -> Quoted.Tuple(escaped.elements.map { quotedValue(it) ?: return null })
        is ElixirAst.Call, is ElixirAst.Alias, is ElixirAst.Block, is ElixirAst.Placeholder -> null
    }
