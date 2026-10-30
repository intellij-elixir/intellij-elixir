package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangExternalFun
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.expander.interpreter.Declined
import org.elixir_lang.expander.interpreter.mapOf
import org.elixir_lang.language_level.ElixirLanguageFeature.ADJACENT_STRINGS_NEED_WHITE_SPACE
import org.elixir_lang.language_level.ElixirLanguageFeature.APP_ENV_PARAMETERS_CHECKED
import org.elixir_lang.language_level.ElixirLanguageFeature.CARET_NOTATION_CHECKED
import org.elixir_lang.language_level.ElixirLanguageFeature.MAKE_FUN_ARITY_CHECKED
import org.elixir_lang.language_level.ElixirLanguageFeature.TRIPLE_QUOTED_STRINGS
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.erlScanReservedWord
import java.math.BigInteger

/** An application resource file, `<name>.app`: the application's [name] and its [env], each key's value. */
internal class AppFile(val name: String, val env: Map<OtpErlangObject, OtpErlangObject>) {
    companion object {
        /**
         * [text] as `application:load/1` on [level]'s OTP reads it (`application_controller:make_appl/1`), or `null`
         * where it refuses it: the file is one `{application, Name, Opts}` term, its env the first `{env, Env}` in
         * `Opts`, a list of `{Key, Value}` entries, with [APP_ENV_PARAMETERS_CHECKED]'s keys.
         */
        fun read(text: CharSequence, level: ElixirLanguageLevel): AppFile? {
            val term = try {
                Terms(text, level).only()
            } catch (_: Unreadable) {
                return null
            }
            val (tag, name, opts) = (term as? OtpErlangTuple)?.takeIf { it.arity() == 3 }?.elements() ?: return null
            if (tag != APPLICATION || name !is OtpErlangAtom) return null
            val options = proper(opts)?.toList() ?: return null
            if (!startSpec(option(options, "mod"))) return null
            val entries = when (val env = option(options, "env")) {
                null -> emptyList()
                else -> proper(env)?.toList() ?: return null
            }
            val pairs = entries.map { entry ->
                val (key, value) = (entry as? OtpErlangTuple)?.takeIf { it.arity() == 2 }?.elements() ?: return null

                key to value
            }
            if (APP_ENV_PARAMETERS_CHECKED.isSufficient(level) &&
                (pairs.any { it.first !is OtpErlangAtom } || pairs.map { it.first }.toSet().size != pairs.size)
            ) {
                return null
            }

            return AppFile(name.atomValue(), pairs.toMap())
        }

        /** The value of the first `{key, Value}` in [options] (`lists:keyfind/3`), `null` where the first is no pair. */
        private fun option(options: List<OtpErlangObject>, key: String): OtpErlangObject? =
            options.firstOrNull { it is OtpErlangTuple && it.arity() >= 1 && it.elementAt(0) == OtpErlangAtom(key) }
                ?.let { it as OtpErlangTuple }
                ?.takeIf { it.arity() == 2 }
                ?.elementAt(1)

        /** Whether [mod] is a start spec `make_appl_i` takes: none, `[]`, or `{Module, Args}`. */
        private fun startSpec(mod: OtpErlangObject?): Boolean =
            mod == null ||
                mod is OtpErlangList && mod.arity() == 0 && mod.lastTail == null ||
                mod is OtpErlangTuple && mod.arity() == 2 && mod.elementAt(0) is OtpErlangAtom

        private fun proper(term: OtpErlangObject): Array<OtpErlangObject>? =
            when (term) {
                is OtpErlangList -> term.elements().takeIf { term.lastTail == null }
                is OtpErlangString -> term.stringValue().codePoints().toArray().map { OtpErlangLong(it.toLong()) }.toTypedArray()
                else -> null
            }

        private val APPLICATION = OtpErlangAtom("application")
    }
}

private class Unreadable : RuntimeException(null, null, false, false)

/**
 * The terms of a file `file:consult/1` reads, as `erl_scan` scans them and `erl_parse:parse_term/1` builds them: atoms,
 * numbers, characters, strings, binaries of integer and string segments, tuples, lists, maps and external funs, each
 * term ending in a dot. Anything else is [Unreadable].
 */
private class Terms(private val text: CharSequence, private val level: ElixirLanguageLevel) {
    private var at = 0

    /** The one term [text] holds. */
    fun only(): OtpErlangObject {
        val term = term()
        blank()
        expect('.')
        if (at < text.length && !isWhiteSpace(text[at]) && text[at] != '%') unreadable()
        blank()
        if (at < text.length) unreadable()

        return term
    }

    private fun term(): OtpErlangObject {
        ProgressManager.checkCanceled()
        blank()
        if (at >= text.length) unreadable()

        val c = text[at]

        return when {
            c == '{' -> OtpErlangTuple(elements().toTypedArray())
            c == '[' -> list()
            c == '#' -> map()
            c == '<' -> binary()
            c == '"' -> string().let { if (it.isEmpty()) OtpErlangList() else OtpErlangString(it) }
            c == '\'' -> atom()
            c == '$' -> OtpErlangLong(char().toLong())
            c in '0'..'9' -> number()
            c == '+' || c == '-' -> {
                at++
                blank()
                when (val operand = if (at < text.length && text[at] == '$') OtpErlangLong(char().toLong()) else number()) {
                    is OtpErlangLong -> if (c == '-') OtpErlangLong(operand.bigIntegerValue().negate()) else operand
                    is OtpErlangDouble -> if (c == '-') OtpErlangDouble(-operand.doubleValue()) else operand
                    else -> unreadable()
                }
            }
            isAtomStart(c) -> {
                val start = at
                if (bare() == "fun") {
                    function()
                } else {
                    at = start
                    atom()
                }
            }
            else -> unreadable()
        }
    }

    /** A tuple's terms, between `{` and `}`, separated by commas. */
    private fun elements(): List<OtpErlangObject> {
        expect('{')
        blank()
        if (consume('}')) return emptyList()

        val terms = mutableListOf(term())
        while (true) {
            blank()
            if (consume('}')) return terms
            expect(',')
            terms += term()
        }
    }

    private fun list(): OtpErlangObject {
        expect('[')
        blank()
        if (consume(']')) return OtpErlangList()

        val heads = mutableListOf(term())
        while (true) {
            blank()
            when {
                consume(']') -> return OtpErlangList(heads.toTypedArray())
                consume('|') -> {
                    val tail = term()
                    blank()
                    expect(']')

                    return when (tail) {
                        is OtpErlangList ->
                            if (tail.lastTail == null) {
                                OtpErlangList((heads + tail.elements()).toTypedArray())
                            } else {
                                OtpErlangList((heads + tail.elements()).toTypedArray(), tail.lastTail)
                            }
                        is OtpErlangString ->
                            OtpErlangList((heads + tail.stringValue().codePoints().toArray().map { OtpErlangLong(it.toLong()) }).toTypedArray())
                        else -> OtpErlangList(heads.toTypedArray(), tail)
                    }
                }
                else -> {
                    expect(',')
                    heads += term()
                }
            }
        }
    }

    /**
     * `#{Key => Value, ...}`; a later key replaces an earlier one, as `maps:from_list/1` takes them ([mapOf]). Where the
     * two differ in a zero's sign, which one the map keeps isn't known, and the file is refused.
     */
    private fun map(): OtpErlangObject {
        expect('#')
        blank()
        expect('{')
        blank()
        val pairs = mutableListOf<Pair<OtpErlangObject, OtpErlangObject>>()
        if (!consume('}')) {
            while (true) {
                val key = term()
                blank()
                expect('=')
                expect('>')
                pairs += key to term()
                blank()
                if (consume('}')) break
                expect(',')
            }
        }

        return try {
            mapOf(pairs, level)
        } catch (_: Declined) {
            unreadable()
        }
    }

    /** `<<Segment, ...>>`, each segment an integer, a character or a string, in 8 bits each, as no size or type is given. */
    private fun binary(): OtpErlangObject {
        expect('<')
        expect('<')
        blank()
        val bytes = mutableListOf<Byte>()
        // `>>` is one token: a `>` alone doesn't close an empty binary.
        if (text.startsWith(">>", at)) {
            at += 2
        } else {
            while (true) {
                blank()
                if (at < text.length && text[at] == '"') {
                    string().codePoints().forEach { bytes += it.toByte() }
                } else {
                    val value = term() as? OtpErlangLong ?: unreadable()
                    bytes += value.bigIntegerValue().toByte()
                }
                blank()
                if (consume('>')) {
                    expect('>')
                    break
                }
                expect(',')
            }
        }

        return OtpErlangBinary(bytes.toByteArray())
    }

    /** `fun Module:Function/Arity` after its `fun`. */
    private fun function(): OtpErlangObject {
        blank()
        val module = atom().atomValue()
        blank()
        expect(':')
        blank()
        val function = atom().atomValue()
        blank()
        expect('/')
        blank()
        val arity = (number() as? OtpErlangLong ?: unreadable()).bigIntegerValue()
        // `erl_parse:normalise/1` makes it with `erlang:make_fun/3`, which takes a small arity: up to 255 from
        // [MAKE_FUN_ARITY_CHECKED], else whole. One past an `Int` is refused, as JInterface can't hold it.
        val made = when {
            arity > MAX_SMALL -> unreadable()
            MAKE_FUN_ARITY_CHECKED.isSufficient(level) -> arity.takeIf { it <= MAX_ARITY } ?: unreadable()
            else -> arity
        }

        return OtpErlangExternalFun(module, function, made.takeIf { it.bitLength() < Int.SIZE_BITS }?.toInt() ?: unreadable())
    }

    /**
     * One or more adjacent strings, joined, as `erl_parse` joins them. From [ADJACENT_STRINGS_NEED_WHITE_SPACE] two with
     * nothing between them are refused.
     */
    private fun string(): String {
        val joined = StringBuilder(quoted('"'))
        while (true) {
            if (ADJACENT_STRINGS_NEED_WHITE_SPACE.isSufficient(level) && at < text.length && text[at] == '"') unreadable()
            val before = at
            blank()
            if (at < text.length && text[at] == '"') joined.append(quoted('"')) else {
                at = before
                return joined.toString()
            }
        }
    }

    /** The text between two [quote]s, its escapes read. From [TRIPLE_QUOTED_STRINGS] a triple-quoted string is refused. */
    private fun quoted(quote: Char): String {
        if (quote == '"' && TRIPLE_QUOTED_STRINGS.isSufficient(level) && text.startsWith("\"\"\"", at)) unreadable()
        expect(quote)
        val builder = StringBuilder()
        while (true) {
            if (at >= text.length) unreadable()

            when (val c = text[at]) {
                quote -> {
                    at++
                    return builder.toString()
                }
                '\\' -> builder.appendCodePoint(escape())
                else -> builder.appendCodePoint(literal())
            }
        }
    }

    /** `$c`, or `$\escape`. */
    private fun char(): Int {
        expect('$')
        if (at >= text.length) unreadable()
        if (text[at] == '\\') return escape()

        return literal()
    }

    /** The character at [at], as written. */
    private fun literal(): Int {
        val codePoint = Character.codePointAt(text, at)
        if (!unicode(codePoint)) unreadable()
        at += Character.charCount(codePoint)

        return codePoint
    }

    /** The character a backslash escape at [at] stands for. */
    private fun escape(): Int {
        expect('\\')
        if (at >= text.length) unreadable()

        return when (val c = text[at++]) {
            in '0'..'7' -> {
                var value = c - '0'
                repeat(2) {
                    if (at < text.length && text[at] in '0'..'7') value = value * 8 + (text[at++] - '0')
                }
                value
            }
            'x' -> if (consume('{')) {
                val start = at
                while (at < text.length && text[at].isHexDigit()) at++
                if (at == start) unreadable()
                val value = BigInteger(text.substring(start, at), 16)
                expect('}')
                if (value > MAX_CODE_POINT) unreadable()
                value.toInt().also { if (!unicode(it)) unreadable() }
            } else {
                if (at + 2 > text.length || !text[at].isHexDigit() || !text[at + 1].isHexDigit()) unreadable()
                text.substring(at, at + 2).toInt(16).also { at += 2 }
            }
            '^' -> {
                if (at >= text.length) unreadable()
                val control = text[at++]
                when {
                    !CARET_NOTATION_CHECKED.isSufficient(level) -> control.code and 31
                    control == '?' -> 127
                    control in '@'..'_' || control in 'a'..'z' -> control.code and 31
                    else -> unreadable()
                }
            }
            else -> ESCAPES[c] ?: run {
                at--
                literal()
            }
        }
    }

    /** An integer, `Base#Digits`, or a float, its digits perhaps separated by `_`. */
    private fun number(): OtpErlangObject {
        val digits = digits(10)
        if (consume('#')) {
            val base = BigInteger(digits)
            if (base !in MIN_BASE..MAX_BASE) unreadable()

            return OtpErlangLong(BigInteger(digits(base.toInt()), base.toInt()))
        }
        if (at + 1 < text.length && text[at] == '.' && text[at + 1] in '0'..'9') {
            at++
            val fraction = digits(10)
            val exponent = if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
                at++
                val sign = if (at < text.length && (text[at] == '+' || text[at] == '-')) text[at++].toString() else ""
                "e$sign${digits(10)}"
            } else {
                ""
            }

            return OtpErlangDouble("$digits.$fraction$exponent".toDouble().takeIf(Double::isFinite) ?: unreadable())
        }

        return OtpErlangLong(BigInteger(digits))
    }

    /** Digits of [base] at [at], perhaps separated by single `_`s. */
    private fun digits(base: Int): String {
        val builder = StringBuilder()
        while (at < text.length) {
            val c = text[at]
            when {
                digit(c) in 0 until base -> builder.append(c)
                c == '_' && builder.isNotEmpty() && at + 1 < text.length && digit(text[at + 1]) in 0 until base -> {}
                else -> break
            }
            at++
        }
        if (builder.isEmpty()) unreadable()

        return builder.toString()
    }

    private fun bare(): String {
        val start = at
        while (at < text.length && isAtomPart(text[at])) at++

        return text.substring(start, at)
    }

    /** Skips white space and `%` comments, each character of which is one `erl_scan` takes. */
    private fun blank() {
        while (at < text.length) {
            when {
                isWhiteSpace(text[at]) -> at++
                text[at] == '%' -> while (at < text.length && text[at] != '\n') literal()
                else -> return
            }
        }
    }

    private fun consume(c: Char): Boolean = (at < text.length && text[at] == c).also { if (it) at++ }

    private fun expect(c: Char) {
        if (!consume(c)) unreadable()
    }

    /** A quoted atom, or a bare one that isn't a reserved word, of up to 255 characters, as `list_to_atom/1` takes. */
    private fun atom(): OtpErlangAtom {
        if (at >= text.length) unreadable()

        val name = when {
            text[at] == '\'' -> quoted('\'')
            isAtomStart(text[at]) -> bare().takeUnless { erlScanReservedWord(it, level) } ?: unreadable()
            else -> unreadable()
        }

        return if (name.codePointCount(0, name.length) > MAX_ATOM_CHARACTERS) unreadable() else OtpErlangAtom(name)
    }

    /** Whether [codePoint] is a character `erl_scan` takes: Unicode's, but the surrogates, `\uFFFE` and `\uFFFF`. */
    private fun unicode(codePoint: Int) =
        codePoint in 0 until 0xD800 || codePoint in 0xE000 until 0xFFFE || codePoint in 0x10000..MAX_CODE_POINT.toInt()

    /** Whether [c] is white space to `erl_scan`: codes 0 to 32 and 128 to 160. */
    private fun isWhiteSpace(c: Char) = c.code in 0..32 || c.code in 128..160

    private fun unreadable(): Nothing = throw Unreadable()

    private companion object {
        val MAX_ARITY: BigInteger = BigInteger.valueOf(255)

        /** The largest small integer on a 64-bit VM. */
        val MAX_SMALL: BigInteger = BigInteger.ONE.shiftLeft(59) - BigInteger.ONE
        const val MAX_ATOM_CHARACTERS = 255
        val MIN_BASE: BigInteger = BigInteger.TWO
        val MAX_BASE: BigInteger = BigInteger.valueOf(36)
        val MAX_CODE_POINT: BigInteger = BigInteger.valueOf(0x10FFFF)

        val ESCAPES = mapOf('b' to 8, 'd' to 127, 'e' to 27, 'f' to 12, 'n' to 10, 'r' to 13, 's' to 32, 't' to 9, 'v' to 11)

        fun isAtomStart(c: Char) = c in 'a'..'z' || c.code in 223..255 && c.code != 247

        fun isAtomPart(c: Char) =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '@' || c.code in 192..255 && c.code != 215 && c.code != 247

        fun Char.isHexDigit() = digit(this) in 0 until 16

        /** [c]'s value as an ASCII digit or letter of a base up to 36, or -1. */
        fun digit(c: Char): Int =
            when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'z' -> c - 'a' + 10
                in 'A'..'Z' -> c - 'A' + 10
                else -> -1
            }
    }
}
