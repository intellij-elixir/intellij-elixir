package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBitstr
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple

/** [term] as `io:format("~w", [Term])` writes it, so an expected term can be pasted from a leg's output. */
internal fun written(term: OtpErlangObject): String =
    when (term) {
        is OtpErlangAtom -> writtenAtom(term.atomValue())
        is OtpErlangLong -> term.bigIntegerValue().toString()
        is OtpErlangDouble -> term.doubleValue().toString().replace("E", "e")
        is OtpErlangBitstr -> writtenBits(term)
        is OtpErlangString -> term.stringValue().codePoints().toArray().joinToString(",", "[", "]")
        is OtpErlangList -> {
            val elements = term.elements().joinToString(",", transform = ::written)

            term.lastTail?.let { "[$elements|${written(it)}]" } ?: "[$elements]"
        }
        is OtpErlangTuple -> term.elements().joinToString(",", "{", "}", transform = ::written)
        else -> error("no ~w for $term")
    }

private fun writtenAtom(name: String): String =
    if (UNQUOTED.matches(name) && name !in RESERVED) {
        name
    } else {
        name.codePoints().toArray().joinToString("", "'", "'") { codePoint ->
            when {
                codePoint == '\\'.code || codePoint == '\''.code -> "\\" + Character.toString(codePoint)
                codePoint > 0xFF -> "\\x{%X}".format(codePoint)
                else -> Character.toString(codePoint)
            }
        }
    }

/** `<<1,2>>`, or `<<1,2:3>>` when the last byte holds only its leading bits. */
private fun writtenBits(term: OtpErlangBitstr): String {
    val bytes = term.binaryValue().map { it.toInt() and 0xFF }
    val bits = 8 - term.pad_bits()

    return if (bits == 8 || bytes.isEmpty()) {
        bytes.joinToString(",", "<<", ">>")
    } else {
        (bytes.dropLast(1).map(Int::toString) + "${bytes.last() shr term.pad_bits()}:$bits").joinToString(",", "<<", ">>")
    }
}

/** A Latin-1 lower-case letter, then Latin-1 letters, digits, `_` and `@`. */
private val UNQUOTED = Regex("[a-zß-öø-ÿ][A-Za-z0-9_@À-ÖØ-öø-ÿ]*")

/** `erl_scan:reserved_word/1`, which `~w` quotes. */
private val RESERVED = setOf(
    "after", "and", "andalso", "band", "begin", "bnot", "bor", "bsl", "bsr", "bxor", "case", "catch", "cond", "div",
    "else", "end", "fun", "if", "let", "maybe", "not", "of", "or", "orelse", "receive", "rem", "try", "when", "xor",
)
