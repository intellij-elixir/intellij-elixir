package org.elixir_lang.code

import org.elixir_lang.annotator.unicode_security.IdentifierTable
import org.elixir_lang.annotator.unicode_security.UnicodeSecurityCheck
import org.elixir_lang.language_level.ElixirLanguageFeature
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.utils.ElixirModulesUtil
import java.text.Normalizer

/**
 * Writes an atom as Elixir source the way Elixir 1.20.4's `Macro.inspect_atom/3` does, in each of its modes, plus the
 * plugin's own rule for a definition head or local call, for which Elixir has no mode. [spelling] writes it as a given
 * release parses it.
 *
 * Before 1.14 a name's characters are classed by Unicode 14.0, which 1.13 tokenizes by. 1.11 tokenizes by Unicode 12.1
 * and 1.12 by 13.0, so a name using a character added or reclassed after that version is written bare there, where they
 * reject it.
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

    /**
     * `Macro.inner_classify/1` as [level]'s Elixir parses [atom]: `:..//` from 1.12, `:**` from 1.13, and identifiers
     * by its table.
     */
    fun classify(atom: String, level: ElixirLanguageLevel = LANGUAGE_LEVEL): Class =
        when {
            !parsedAt(atom, level) -> Class.OTHER
            atom in NOT_CALLABLE -> Class.NOT_CALLABLE
            atom in QUOTED_OPERATORS -> Class.QUOTED_OPERATOR
            isOperator(atom) -> Class.UNQUOTED_OPERATOR
            ALIAS.matches(atom) -> Class.ALIAS
            else -> tokenize(atom, level)
        }

    /** The ways an atom is written: as a literal, a keyword key's name before its colon, or a name after `Module.`. */
    enum class Form {
        LITERAL,
        KEY,
        REMOTE_CALL
    }

    /** [atom] written as [form] so that [level]'s Elixir parses it. */
    fun spelling(atom: String, form: Form, level: ElixirLanguageLevel): String =
        classify(atom, level).let { atomClass ->
            when (form) {
                Form.LITERAL -> literal(atom, atomClass)
                Form.KEY -> keyName(atom, if (keyParsedAt(atom) { level }) atomClass else Class.OTHER)
                Form.REMOTE_CALL -> remoteCall(atom, atomClass)
            }
        }

    /** Whether `Code.Identifier.unary_op/1` or `binary_op/1` holds for [name]. */
    internal fun isOperator(name: String): Boolean = name in UNARY_OPERATORS || name in BINARY_OPERATORS

    /** `Macro.inspect_atom(:literal, atom)`, as `inspect/1` writes an atom. */
    fun literal(atom: String): String = literal(atom, classify(atom))

    private fun literal(atom: String, atomClass: Class): String =
        if (atom == "nil" || atom == "true" || atom == "false") {
            atom
        } else {
            when (atomClass) {
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

    /**
     * [literal] as [level]'s `inspect/1` writes [atom]: `null` where that release writes it otherwise, as before a change
     * to how it writes non-ASCII atoms or an operator set.
     */
    fun literal(atom: String, level: ElixirLanguageLevel): String? {
        val changed = when {
            atom.any { it.code > 127 } -> ElixirLanguageFeature.UNICODE_17_ATOMS_INSPECTED
            atom in DEPRECATED_OPERATORS -> ElixirLanguageFeature.DEPRECATED_OPERATORS_INSPECTED_QUOTED
            else -> null
        }

        return if (changed?.isSufficient(level) == false) null else spelling(atom, Form.LITERAL, level)
    }

    /** `Macro.inspect_atom(:key, atom)`, a keyword key with its colon. */
    fun key(atom: String): String = keyName(atom, classify(atom)) + ":"

    /** [key] without its colon. */
    private fun keyName(atom: String, atomClass: Class): String =
        when (atomClass) {
            Class.ALIAS -> "\"$atom\""
            Class.QUOTED_OPERATOR, Class.OTHER -> "\"${escape(atom)}\""
            else -> atom
        }

    /** `Macro.inspect_atom(:remote_call, atom)`, the name after `Module.`. */
    fun remoteCall(atom: String): String = remoteCall(atom, classify(atom))

    private fun remoteCall(atom: String, atomClass: Class): String =
        when (atomClass) {
            Class.IDENTIFIER, Class.UNQUOTED_OPERATOR, Class.QUOTED_OPERATOR -> atom
            Class.ALIAS, Class.NOT_CALLABLE -> "\"$atom\""
            Class.OTHER -> "\"${escape(atom)}\""
        }

    /**
     * [remoteCall] as [level]'s undefined-function hint writes [atom]. Before the hint read `Macro.inspect_atom/2`,
     * `Code.Identifier.inspect_as_function/1` quoted `**` until it was an operator and `//` until it was
     * `not_atomable`; non-ASCII names are written by Elixir 1.20.4's tables.
     */
    fun remoteCall(atom: String, level: ElixirLanguageLevel): String {
        if (ElixirLanguageFeature.UNDEFINED_FUNCTION_HINT_INSPECTS_REMOTE_CALL.isSufficient(level)) return remoteCall(atom)

        return when (atom) {
            in PARSED_FROM -> spelling(atom, Form.REMOTE_CALL, level)
            "//" -> if (ElixirLanguageFeature.STEP_OPERATOR.isSufficient(level)) atom else "\"$atom\""
            else -> remoteCall(atom)
        }
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

    /**
     * `String.Tokenizer.tokenize/1`'s answer, as `inner_classify` reads it, by [level]'s identifier table. Before the
     * first release with one, which checks no identifier's scripts, by the oldest table's classes unrestricted. Before
     * [ElixirLanguageFeature.NORMALIZED_IDENTIFIERS] a name not in NFC is rejected.
     */
    internal fun tokenize(atom: String, level: ElixirLanguageLevel = LANGUAGE_LEVEL): Class {
        if (atom.isEmpty()) return Class.OTHER

        val table = IdentifierTable.forLanguageLevel(level) ?: IdentifierTable.unrestricted
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

            if (UnicodeSecurityCheck.inIdentifier(token, level) != null) return Class.OTHER
            if (!Normalizer.isNormalized(token, Normalizer.Form.NFC)) {
                if (!ElixirLanguageFeature.NORMALIZED_IDENTIFIERS.isSufficient(level)) return Class.OTHER
                normalized = true
            }
        }

        return when {
            offset < atom.length -> Class.OTHER
            !identifier || at -> Class.NOT_CALLABLE
            normalized -> Class.OTHER
            else -> Class.IDENTIFIER
        }
    }

    /** The release whose `inspect/1` and tokenizer this ports. */
    internal val LANGUAGE_LEVEL = ElixirLanguageLevel.of("1.20.4")
    private const val MICRO_SIGN = 0x00B5
    private const val IDENTIFIER_CLASSES = IdentifierTable.UPPER or IdentifierTable.START or IdentifierTable.CONTINUE

    private val ALIAS = Regex("Elixir(?:\\.${ElixirModulesUtil.elixirAliasSegmentsRegex.pattern})?")
    private val NOT_CALLABLE = setOf("%", "%{}", "{}", "<<>>", "...", "..", ".", "..//", "->")

    /** The atoms an earlier release's tokenizer reads only quoted, by the feature that reads them bare. */
    private val PARSED_FROM =
        mapOf("..//" to ElixirLanguageFeature.STEP_ATOM, "**" to ElixirLanguageFeature.POWER_OPERATOR)

    /** Whether [level]'s tokenizer reads [atom] bare, as far as [PARSED_FROM] says. */
    private fun parsedAt(atom: String, level: ElixirLanguageLevel): Boolean = PARSED_FROM[atom]?.isSufficient(level) != false

    /** The keyword keys an earlier release's tokenizer reads only quoted, by the feature that reads them bare. */
    private val KEY_PARSED_FROM = mapOf("." to ElixirLanguageFeature.DOT_KEYWORD_KEY)

    /**
     * Whether [level]'s tokenizer reads [atom] bare as a keyword key, as far as [KEY_PARSED_FROM] says. [level] is read
     * only for a key it names.
     */
    internal fun keyParsedAt(atom: String, level: () -> ElixirLanguageLevel): Boolean =
        KEY_PARSED_FROM[atom]?.isSufficient(level()) != false

    /** The operators `inspect/1` quotes from [ElixirLanguageFeature.DEPRECATED_OPERATORS_INSPECTED_QUOTED]. */
    private val DEPRECATED_OPERATORS = setOf("^^^", "~~~", "<|>")
    private val QUOTED_OPERATORS = DEPRECATED_OPERATORS + "::"
    private val UNARY_OPERATORS = setOf("&", "...", "!", "^", "not", "+", "-", "~~~", "@")
    private val BINARY_OPERATORS = setOf(
        "<-", "\\\\", "when", "::", "|", "=", "||", "|||", "or", "&&", "&&&", "and", "==", "!=", "=~", "===", "!==", "<",
        "<=", ">=", ">", "|>", "<<<", ">>>", "<~", "~>", "<<~", "~>>", "<~>", "<|>", "in", "^^^", "++", "--", "..",
        "<>", "+++", "---", "+", "-", "*", "/", "**", "."
    )

    /**
     * The operators IEx offers between two expressions (`IEx.Autocomplete`'s `binary_operators`): the binary operators
     * but `.` and the deprecated ones, and `not in` and `=>`, which `Code.Identifier.binary_op/1` does not name.
     */
    private val INFIX_OPERATORS: Set<String> = buildSet {
        addAll(BINARY_OPERATORS)
        removeAll(DEPRECATED_OPERATORS)
        remove(".")
        add("not in")
        add("=>")
    }

    /** [INFIX_OPERATORS] that [level]'s Elixir parses, in their order. */
    fun infixOperators(level: ElixirLanguageLevel): Set<String> =
        INFIX_OPERATORS.filterTo(LinkedHashSet()) { parsedAt(it, level) }

    /** The operators spelt as words. */
    internal val TEXTUAL_OPERATORS: Set<String> = (UNARY_OPERATORS + BINARY_OPERATORS).filterTo(mutableSetOf()) { it.all(Char::isLetter) }

    /** Elixir's reserved words, and the special forms a definition cannot name bare. */
    private val RESERVED = RESERVED_WORDS + setOf("__aliases__", "__block__", "unquote", "unquote_splicing")

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
