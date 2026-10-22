package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * [term] on one line, as Elixir's `inspect` prints it, so an expected term can be pasted from `Code.string_to_quoted`.
 *
 * A list of integers prints as `~c"..."` only when every one is ASCII-printable, as Elixir's `inspect` decides. A map's
 * pairs print in the order the term holds them.
 */
internal fun inspect(term: OtpErlangObject): String =
    when (term) {
        is OtpErlangAtom -> atom(term.atomValue())
        is OtpErlangLong -> term.bigIntegerValue().toString()
        is OtpErlangDouble -> term.doubleValue().toString().replace("E", "e")
        is OtpErlangBinary -> binary(term.binaryValue())
        is OtpErlangString -> {
            val text = term.stringValue()

            if (text.all { it.code in 32..126 || it in ASCII_PRINTABLE_CONTROLS }) {
                "~c\"" + escape(text) + "\""
            } else {
                text.codePoints().toArray().joinToString(", ", "[", "]")
            }
        }
        is OtpErlangList -> {
            val elements = term.elements().toList()
            val tail = term.lastTail

            if (tail != null) {
                elements.joinToString(", ", "[", " | ${inspect(tail)}]") { inspect(it) }
            } else if (elements.isNotEmpty() && elements.all { keyword(it) != null }) {
                elements.joinToString(", ", "[", "]") { element ->
                    val pair = element as OtpErlangTuple

                    keyword(pair)!! + " " + inspect(pair.elementAt(1))
                }
            } else {
                elements.joinToString(", ", "[", "]") { inspect(it) }
            }
        }
        is OtpErlangTuple -> term.elements().joinToString(", ", "{", "}") { inspect(it) }
        is OtpErlangMap -> {
            val pairs = term.keys().map { OtpErlangTuple(arrayOf(it, term.get(it))) }

            pairs.joinToString(", ", "%{", "}") { pair ->
                keyword(pair)?.let { "$it ${inspect(pair.elementAt(1))}" }
                    ?: "${inspect(pair.elementAt(0))} => ${inspect(pair.elementAt(1))}"
            }
        }
        else -> error("no inspection for ${term.javaClass}")
    }

private val ASCII_PRINTABLE_CONTROLS = setOf('\n', '\r', '\t', '\u000B', '\b', '\u000C', '\u001B', '\u007F', '\u0007')
private val IDENTIFIER = Regex("[\\p{Ll}_][\\p{L}\\p{N}_@]*[?!]?")
private val OPERATORS = setOf(
    "{}", "<<>>", "%{}", "%", ".", "..", "..//", "...", "=", "|", "<>", "&", "@", "+", "-", "*", "/", "!", "^", "**",
    "||", "|||", "&&", "&&&", "==", "!=", "=~", "===", "!==", "<", ">", "<=", ">=", "|>", "<<<", ">>>", "<<~", "~>>",
    "<~", "~>", "<~>", "<-", "\\\\", "++", "--", "+++", "---", "->",
)

private fun atom(name: String): String =
    when {
        name == "true" || name == "false" || name == "nil" -> name
        name.startsWith("Elixir.") -> name.removePrefix("Elixir.")
        IDENTIFIER.matches(name) || name.firstOrNull()?.isUpperCase() == true && name.all { it.isLetterOrDigit() || it == '_' } ||
            name in OPERATORS -> ":$name"
        else -> ":\"" + escape(name) + "\""
    }

private fun keyword(element: OtpErlangObject): String? =
    (element as? OtpErlangTuple)
        ?.takeIf { it.arity() == 2 }
        ?.let { it.elementAt(0) as? OtpErlangAtom }
        ?.atomValue()
        ?.let { if (IDENTIFIER.matches(it)) "$it:" else "\"" + escape(it) + "\":" }

private fun binary(bytes: ByteArray): String =
    try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        "\"" + escape(decoder.decode(ByteBuffer.wrap(bytes)).toString()) + "\""
    } catch (_: CharacterCodingException) {
        bytes.joinToString(", ", "<<", ">>") { (it.toInt() and 0xFF).toString() }
    }

private fun escape(text: String): String =
    buildString {
        text.codePoints().forEach { codePoint ->
            when (codePoint) {
                '"'.code -> append("\\\"")
                '\\'.code -> append("\\\\")
                '\n'.code -> append("\\n")
                '\t'.code -> append("\\t")
                '\r'.code -> append("\\r")
                0x1B -> append("\\e")
                0x07 -> append("\\a")
                0x08 -> append("\\b")
                0x0B -> append("\\v")
                0x0C -> append("\\f")
                0x7F -> append("\\d")
                in 0..0x1F -> append("\\x{" + Integer.toHexString(codePoint).uppercase() + "}")
                else -> appendCodePoint(codePoint)
            }
        }
    }.replace("#{", "\\#{")
