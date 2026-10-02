package org.elixir_lang.code

import org.elixir_lang.annotator.unicode_security.IdentifierTable
import org.elixir_lang.annotator.unicode_security.UnicodeSecurityCheck
import org.elixir_lang.language_level.ElixirLanguageLevel
import java.text.Normalizer

/**
 * Writes an atom as Elixir source the way Elixir 1.20.4's `Macro.inspect_atom/3` does, in each of its modes, plus the
 * plugin's own rule for a definition head or local call, for which Elixir has no mode.
 *
 * It follows 1.20.4 whatever the project's Elixir, so a few names it writes bare do not parse on older versions: an
 * identifier mixing scripts across `_` on 1.14–1.17, `:**` before 1.13 and `:..//` before 1.12.
 */
object InspectAtom {
    /** `Macro.inner_classify/1`. */
    enum class Class {
        ALIAS,
        IDENTIFIER,
        UNQUOTED_OPERATOR,
        QUOTED_OPERATOR,
        NOT_CALLABLE,
        OTHER
    }

    fun classify(atom: String): Class =
        when (atom) {
            in NOT_CALLABLE -> Class.NOT_CALLABLE
            in QUOTED_OPERATORS -> Class.QUOTED_OPERATOR
            in UNARY_OPERATORS, in BINARY_OPERATORS -> Class.UNQUOTED_OPERATOR
            else -> if (ALIAS.matches(atom)) Class.ALIAS else tokenize(atom)
        }

    /** `Macro.inspect_atom(:literal, atom)`, as `inspect/1` writes an atom. */
    fun literal(atom: String): String =
        if (atom == "nil" || atom == "true" || atom == "false") {
            atom
        } else {
            when (classify(atom)) {
                Class.ALIAS ->
                    if (atom == "Elixir" || atom == "Elixir.Elixir" || atom.startsWith("Elixir.Elixir.")) {
                        atom
                    } else {
                        atom.removePrefix("Elixir.")
                    }

                Class.QUOTED_OPERATOR, Class.OTHER -> ":\"${escape(atom)}\""
                else -> ":$atom"
            }
        }

    /** `Macro.inspect_atom(:key, atom)`, a keyword key with its colon. */
    fun key(atom: String): String =
        when (classify(atom)) {
            Class.ALIAS -> "\"$atom\":"
            Class.QUOTED_OPERATOR, Class.OTHER -> "\"${escape(atom)}\":"
            else -> "$atom:"
        }

    /** `Macro.inspect_atom(:remote_call, atom)`, the name after `Module.`. */
    fun remoteCall(atom: String): String =
        when (classify(atom)) {
            Class.IDENTIFIER, Class.UNQUOTED_OPERATOR, Class.QUOTED_OPERATOR -> atom
            Class.ALIAS, Class.NOT_CALLABLE -> "\"$atom\""
            Class.OTHER -> "\"${escape(atom)}\""
        }

    /**
     * The name of a definition head or a local call: a non-reserved identifier is bare, a reserved word is
     * `unquote(:word)`, and anything else is `unquote(` its literal `)`.
     */
    fun localCall(atom: String): String =
        when {
            atom in RESERVED -> "unquote(:$atom)"
            classify(atom) == Class.IDENTIFIER -> atom
            else -> "unquote(${literal(atom)})"
        }

    /** `Code.Identifier.escape/2`: [string] as the inside of a [delimiter]-quoted literal. */
    fun escape(string: String, delimiter: Char = '"'): String =
        buildString {
            var offset = 0

            while (offset < string.length) {
                val codePoint = string.codePointAt(offset)

                when (codePoint) {
                    delimiter.code -> append('\\').append(delimiter)
                    '#'.code if string.startsWith("{", offset + 1) -> {
                        append("\\#{")
                        offset++
                    }

                    else -> append(ESCAPES[codePoint] ?: escapeCodePoint(codePoint))
                }

                offset += Character.charCount(codePoint)
            }
        }

    private fun escapeCodePoint(codePoint: Int): String =
        when {
            codePoint == 0 -> "\\0"
            codePoint in UNICODE_ESCAPED -> "\\u%04X".format(codePoint)
            codePoint in 0x20..0x7E || codePoint in 0xA0..0xD7FF || codePoint in 0xE000..0xFFFD || codePoint >= 0x10000 ->
                String(Character.toChars(codePoint))

            codePoint < 0x100 -> "\\x%02X".format(codePoint)
            else -> "\\x{%04X}".format(codePoint)
        }

    /** `String.Tokenizer.tokenize/1`'s answer, as `inner_classify` reads it. */
    private fun tokenize(atom: String): Class {
        if (atom.isEmpty()) return Class.OTHER

        val table = IdentifierTable.forLanguageLevel(LANGUAGE_LEVEL)!!
        val first = atom.codePointAt(0)
        var ascii = true
        var normalized = false
        val identifier = when (first) {
            in 'A'.code..'Z'.code -> false
            in 'a'.code..'z'.code, '_'.code -> true
            else -> {
                ascii = false
                val classes = table.classesOf(first)

                when {
                    classes and IdentifierTable.UPPER != 0 -> false
                    classes and IdentifierTable.START != 0 -> true
                    first == MICRO_SIGN -> true.also { normalized = true }
                    else -> return Class.OTHER
                }
            }
        }
        var at = false
        var offset = Character.charCount(first)

        while (offset < atom.length) {
            val codePoint = atom.codePointAt(offset)

            if (codePoint == '!'.code || codePoint == '?'.code) {
                offset++
                break
            }

            when {
                codePoint == '@'.code -> at = true
                codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code || codePoint in '0'.code..'9'.code ||
                    codePoint == '_'.code -> Unit

                codePoint <= 127 -> break
                else -> {
                    ascii = false

                    if (table.classesOf(codePoint) and IDENTIFIER_CLASSES == 0) {
                        if (codePoint == MICRO_SIGN) normalized = true else return Class.OTHER
                    }
                }
            }

            offset += Character.charCount(codePoint)
        }

        if (!ascii) {
            val token = atom.substring(0, offset)

            if (UnicodeSecurityCheck.inIdentifier(token, LANGUAGE_LEVEL) != null) return Class.OTHER
            if (!Normalizer.isNormalized(token, Normalizer.Form.NFC)) normalized = true
        }

        return when {
            offset < atom.length -> Class.OTHER
            !identifier || at -> Class.NOT_CALLABLE
            normalized -> Class.OTHER
            else -> Class.IDENTIFIER
        }
    }

    private val LANGUAGE_LEVEL = ElixirLanguageLevel.of("1.20.4")
    private const val MICRO_SIGN = 0x00B5
    private const val IDENTIFIER_CLASSES = IdentifierTable.UPPER or IdentifierTable.START or IdentifierTable.CONTINUE

    private val ALIAS = Regex("Elixir(?:\\.[A-Z][A-Za-z0-9_]*)*")
    private val NOT_CALLABLE = setOf("%", "%{}", "{}", "<<>>", "...", "..", ".", "..//", "->")
    private val QUOTED_OPERATORS = setOf("::", "^^^", "~~~", "<|>")
    private val UNARY_OPERATORS = setOf("&", "...", "!", "^", "not", "+", "-", "~~~", "@")
    private val BINARY_OPERATORS = setOf(
        "<-", "\\\\", "when", "::", "|", "=", "||", "|||", "or", "&&", "&&&", "and", "==", "!=", "=~", "===", "!==", "<",
        "<=", ">=", ">", "|>", "<<<", ">>>", "<~", "~>", "<<~", "~>>", "<~>", "<|>", "in", "^^^", "++", "--", "..",
        "<>", "+++", "---", "+", "-", "*", "/", "**", "."
    )

    /** Elixir's reserved words, and the special forms a definition cannot name bare. */
    private val RESERVED = setOf(
        "true", "false", "nil", "when", "and", "or", "not", "in", "fn", "do", "end", "catch", "rescue", "after", "else",
        "__aliases__", "__block__", "unquote", "unquote_splicing"
    )

    private val ESCAPES = mapOf(
        0x07 to "\\a", 0x08 to "\\b", 0x7F to "\\d", 0x1B to "\\e", 0x0C to "\\f", 0x0A to "\\n", 0x0D to "\\r",
        0x09 to "\\t", 0x0B to "\\v", '\\'.code to "\\\\"
    )

    private val UNICODE_ESCAPED: Set<Int> = buildSet {
        add(0xFEFF)
        addAll(0x2061..0x2064)
        addAll(listOf(0x061C, 0x200E, 0x200F))
        addAll(0x202A..0x202E)
        addAll(0x2066..0x2069)
        addAll(0xFFF9..0xFFFC)
        addAll(listOf(0x200C, 0x200D, 0x034F))
        addAll(listOf(0x00A0, 0x200B, 0x2060))
        addAll(listOf(0x2028, 0x2029))
        addAll(0x2000..0x200A)
        add(0x205F)
    }
}
