package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPE_ERRORS_NAME_THE_INVALID_CHARACTER
import org.elixir_lang.language_level.ElixirLanguageFeature.HEXADECIMAL_ESCAPE_NEEDS_TWO_DIGITS
import org.elixir_lang.language_level.ElixirLanguageFeature.REGEX_KEEPS_ESCAPES
import org.elixir_lang.language_level.ElixirLanguageFeature.UNESCAPE_DROPS_ESCAPED_NEWLINE
import org.elixir_lang.language_level.ElixirLanguageLevel
import java.io.ByteArrayOutputStream

/** What `elixir_interpolation:unescape_string/2` gives. */
internal sealed class Unescaped {
    class Text(val bytes: ByteArray) : Unescaped()

    /** The unescape raised, an error of [kind]. */
    class Failed(val kind: String) : Unescaped()
}

/** An `unescape_map` of `elixir_interpolation:unescape_string/2`: which escapes it replaces, and by what. */
internal enum class EscapeMap {
    /** `elixir_interpolation:unescape_map/1`. */
    DEFAULT,

    /** What `~r` unescapes: the line continuation, and until [REGEX_KEEPS_ESCAPES] the control characters. */
    REGEX,
}

/** `elixir_interpolation:unescape_string/2` of [string] with [map], as Elixir at [level] does it. */
internal fun unescapeString(string: ByteArray, map: EscapeMap, level: ElixirLanguageLevel): Unescaped =
    Unescaper(string, map, level).unescape()

private class Unescaper(private val string: ByteArray, private val map: EscapeMap, private val level: ElixirLanguageLevel) {
    private val out = ByteArrayOutputStream(string.size)
    private var at = 0

    fun unescape(): Unescaped {
        while (at < string.size) {
            val byte = string[at++].toInt() and 0xFF

            if (byte != BACKSLASH || at == string.size) {
                out.write(byte)
                continue
            }

            val failed = when (val escaped = string[at++].toInt() and 0xFF) {
                'x'.code if map == EscapeMap.DEFAULT -> hex()
                'u'.code if map == EscapeMap.DEFAULT -> unicode()
                NEWLINE if UNESCAPE_DROPS_ESCAPED_NEWLINE.isSufficient(level) -> null
                '\r'.code if UNESCAPE_DROPS_ESCAPED_NEWLINE.isSufficient(level) && string.getOrNull(at) == NEWLINE.toByte() -> {
                    at++
                    null
                }
                else -> {
                    replace(escaped)
                    null
                }
            }

            if (failed != null) return failed
        }

        return Unescaped.Text(out.toByteArray())
    }

    /** `\` and [escaped] as [map] replaces them, or as written. */
    private fun replace(escaped: Int) {
        val replacement = when (map) {
            EscapeMap.DEFAULT -> DEFAULT_REPLACEMENTS[escaped.toChar()] ?: escaped
            EscapeMap.REGEX -> if (REGEX_KEEPS_ESCAPES.isSufficient(level)) null else REGEX_REPLACEMENTS[escaped.toChar()]
        }

        if (replacement == null) {
            out.write(BACKSLASH)
            out.write(escaped)
        } else {
            out.write(replacement)
        }
    }

    /** `unescape_hex/3`, after `\x`: `\xHH`, and until [HEXADECIMAL_ESCAPE_NEEDS_TWO_DIGITS] `\xH` and `\x{H*}`. */
    private fun hex(): Unescaped.Failed? {
        if (isHex(at) && isHex(at + 1)) {
            out.write(String(string, at, 2, Charsets.ISO_8859_1).toInt(16))
            at += 2

            return null
        }

        if (!HEXADECIMAL_ESCAPE_NEEDS_TWO_DIGITS.isSufficient(level)) {
            if (isHex(at)) return appendCodePoint(at, at + 1, at + 1)

            braced()?.let { (digits, next) -> return appendCodePoint(digits.first, digits.last + 1, next) }
        }

        return Unescaped.Failed(if (namesTheCharacter()) "hex_escape_invalid" else "hex_escape_missing")
    }

    /** `unescape_unicode/3`, after `\u`: `\uHHHH` and `\u{H*}`. */
    private fun unicode(): Unescaped.Failed? {
        if ((0 until 4).all { isHex(at + it) }) return appendCodePoint(at, at + 4, at + 4)

        braced()?.let { (digits, next) -> return appendCodePoint(digits.first, digits.last + 1, next) }

        return Unescaped.Failed(if (namesTheCharacter()) "unicode_escape_invalid" else "unicode_escape_missing")
    }

    /** The one to six hexadecimal digits of a `{...}` at [at], and where the text goes on after its `}`. */
    private fun braced(): Pair<IntRange, Int>? {
        if (string.getOrNull(at) != '{'.code.toByte()) return null

        val digits = (at + 1 until string.size).takeWhile { isHex(it) }

        return digits.takeIf { it.size in 1..6 && string.getOrNull(it.last() + 1) == '}'.code.toByte() }
            ?.let { (it.first()..it.last()) to it.last() + 2 }
    }

    /** The code point written as hexadecimal digits from [from] to [to], as UTF-8, and the text going on from [next]. */
    private fun appendCodePoint(from: Int, to: Int, next: Int): Unescaped.Failed? {
        val codePoint = String(string, from, to - from, Charsets.ISO_8859_1).toInt(16)

        if (!Character.isValidCodePoint(codePoint) || codePoint in SURROGATES) {
            return Unescaped.Failed("unicode_code_point_reserved")
        }

        out.write(String(Character.toChars(codePoint)).toByteArray())
        at = next

        return null
    }

    private fun namesTheCharacter() = ESCAPE_ERRORS_NAME_THE_INVALID_CHARACTER.isSufficient(level)

    private fun isHex(index: Int): Boolean =
        string.getOrNull(index)?.toInt()?.toChar()?.let { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } == true

    private companion object {
        const val BACKSLASH = '\\'.code
        const val NEWLINE = '\n'.code

        val SURROGATES = 0xD800..0xDFFF

        val DEFAULT_REPLACEMENTS = mapOf('0' to 0, 'a' to 7, 'b' to 8, 'd' to 127, 'e' to 27, 'f' to 12, 'n' to 10, 'r' to 13, 's' to 32, 't' to 9, 'v' to 11)

        /** `Regex.unescape_map/1`: the control characters. */
        val REGEX_REPLACEMENTS = mapOf('f' to 12, 'n' to 10, 'r' to 13, 't' to 9, 'v' to 11, 'a' to 7)
    }
}
