package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.TextRange

/**
 * Where a node came from, and the metadata Elixir gives it.
 *
 * [start] and [end] are the node's own source span, and can differ from the `line` Elixir reports in [keys]: an
 * operator reports its operator's line, and older Elixir versions left some newlines uncounted.
 *
 * @property origin the node's range in the file it was lowered from, valid for that version of the file only
 * @property start 1-based line and column of the node's first character
 * @property end 1-based line and column just past the node's last character
 * @property keys the metadata Elixir emits for this node, in Elixir's order
 * @property built the expander built the node rather than lowering it; [origin] is the source node it was built for
 */
class Meta(
    val origin: TextRange,
    val start: Position,
    val end: Position,
    val keys: List<Key> = emptyList(),
    val built: Boolean = false,
) {
    /** Lines from 1, and columns from 1 counted in code points, as Elixir counts them. */
    class Position(val line: Int, val column: Int)

    sealed class Key {
        /** `line`, followed by `column` when the options ask for columns. */
        class Location(val position: Position) : Key()

        /** @property tokenMetadata emitted only when the options ask for token metadata */
        class Entry(val name: String, val value: Value, val tokenMetadata: Boolean = false) : Key()
    }

    sealed class Value {
        class Atom(val name: String) : Value()
        class Integer(val value: Long) : Value()
        class Binary(val text: String) : Value()
        class Keywords(val keys: kotlin.collections.List<Key>) : Value()

        /** Only the expander builds one: a quoted import's `[{arity, Module}]`. */
        class List(val elements: kotlin.collections.List<Value>) : Value()

        /** Only the expander builds one: a hygiene counter's `{Module, n}`. */
        class Tuple(val elements: kotlin.collections.List<Value>) : Value()
    }

    fun toOtp(options: ParserOptions): OtpErlangList = toOtp(keys, options)

    private companion object {
        fun toOtp(keys: List<Key>, options: ParserOptions): OtpErlangList =
            OtpErlangList(
                keys.flatMap { key ->
                    when (key) {
                        is Key.Location -> listOfNotNull(
                            keyword("line", OtpErlangLong(key.position.line.toLong())),
                            if (options.columns) keyword("column", OtpErlangLong(key.position.column.toLong())) else null
                        )
                        is Key.Entry ->
                            if (key.tokenMetadata && !options.tokenMetadata) {
                                emptyList()
                            } else {
                                listOf(keyword(key.name, toOtp(key.value, options)))
                            }
                    }
                }.toTypedArray()
            )

        fun toOtp(value: Value, options: ParserOptions): OtpErlangObject =
            when (value) {
                is Value.Atom -> OtpErlangAtom(value.name)
                is Value.Integer -> OtpErlangLong(value.value)
                is Value.Binary -> OtpErlangBinary(value.text.toByteArray(Charsets.UTF_8))
                is Value.Keywords -> toOtp(value.keys, options)
                is Value.List -> OtpErlangList(value.elements.map { toOtp(it, options) }.toTypedArray())
                is Value.Tuple -> OtpErlangTuple(value.elements.map { toOtp(it, options) }.toTypedArray())
            }

        fun keyword(key: String, value: OtpErlangObject) = OtpErlangTuple(arrayOf(OtpErlangAtom(key), value))
    }
}
