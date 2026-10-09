package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_REGEX_IMPORTS_ON_OTP_28_1
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_REGEX_RECOMPILES_ON_OTP_28
import org.elixir_lang.language_level.ElixirLanguageFeature.REGEX_COMPILED_AT_RUN_TIME_ON_OTP_28
import org.elixir_lang.language_level.ElixirLanguageFeature.REGEX_ESCAPED_IN_FIXED_FIELD_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.REGEX_OPTIONS_AS_LIST
import org.elixir_lang.language_level.ElixirLanguageFeature.REGEX_REFUSED_IN_MATCH_AND_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.RE_VERSION_IN_REGEX
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.fitsAnAtom
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A sigil call's `{:<<>>, Meta, Pieces}` and its modifiers, `null` where it has not that shape and no clause matches. */
internal class Sigil(val interpolation: ElixirAst.Call, val pieces: List<ElixirAst>, val modifiers: ElixirAst) {
    /** The one binary of a sigil with no interpolation. */
    val text: ElixirAst.Literal.Binary? get() = pieces.singleOrNull() as? ElixirAst.Literal.Binary

    /** Whether the modifiers are `[]`, which most clauses require. */
    val hasNoModifiers: Boolean get() = (modifiers as? ElixirAst.ListNode)?.elements?.isEmpty() == true
}

internal fun sigil(node: ElixirAst.Call): Sigil? {
    val (term, modifiers) = node.arguments!!
    val interpolation = term as? ElixirAst.Call ?: return null

    if ((interpolation.callee as? ElixirAst.Literal.Atom)?.name != "<<>>") return null

    return Sigil(interpolation, interpolation.arguments ?: return null, modifiers)
}

/** The `FunctionClauseError` of a sigil macro called with arguments no clause takes. */
internal val SIGIL_NO_CLAUSE = Summary.Output.Raised("sigil_function_clause")

/** `Kernel.sigil_S/2`: the text as written. */
internal val SIGIL_S_UPPER = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val sigil = sigil(node)?.takeIf { it.hasNoModifiers }
        val text = sigil?.text ?: return SIGIL_NO_CLAUSE

        return Summary.Output.Built(ElixirAst.Literal.Binary(Synthetic(node.meta).meta(), text.bytes))
    }
}

/** `Kernel.sigil_s/2`: each piece of text unescaped. */
internal val SIGIL_S = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val sigil = sigil(node)?.takeIf { it.hasNoModifiers } ?: return SIGIL_NO_CLAUSE
        val pieces = unescapedPieces(sigil.pieces, EscapeMap.DEFAULT, run.level) { return it }
        val text = pieces.singleOrNull() as? ElixirAst.Literal.Binary
        val interpolation = sigil.interpolation

        return Summary.Output.Built(text ?: ElixirAst.Call(interpolation.meta, interpolation.callee, pieces))
    }
}

/** `Kernel.sigil_C/2`: the text as written, as a charlist. */
internal val SIGIL_C_UPPER = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val sigil = sigil(node)?.takeIf { it.hasNoModifiers }
        val text = sigil?.text ?: return SIGIL_NO_CLAUSE

        return charlist(Synthetic(node.meta), text.bytes)?.let { Summary.Output.Built(it) } ?: Summary.Output.Unported(node)
    }
}

/** `Kernel.sigil_c/2`: unescaped, as a charlist, or a `List.to_charlist/1` call where it interpolates. */
internal val SIGIL_C = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val sigil = sigil(node)?.takeIf { it.hasNoModifiers } ?: return SIGIL_NO_CLAUSE
        val s = Synthetic(node.meta)

        sigil.text?.let { text ->
            val bytes = unescapedBytes(text, EscapeMap.DEFAULT, run.level) { return it }

            return charlist(s, bytes)?.let { Summary.Output.Built(it) } ?: Summary.Output.Unported(node)
        }

        val items = sigil.pieces.map { piece ->
            when (piece) {
                is ElixirAst.Literal.Binary ->
                    ElixirAst.Literal.Binary(piece.meta, unescapedBytes(piece, EscapeMap.DEFAULT, run.level) { return it })
                is ElixirAst.Call if (piece.callee as? ElixirAst.Literal.Atom)?.name == "::" && piece.arguments?.size == 2 ->
                    piece.arguments[0]
                else -> return Summary.Output.Unported(piece)
            }
        }

        return Summary.Output.Built(s.remoteCall(kernelAlias(s, "List"), "to_charlist", listOf(s.list(items))))
    }
}

/** `Kernel.sigil_w/2`: the words of the unescaped text, as strings, atoms or charlists. */
internal val SIGIL_W = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val sigil = sigil(node) ?: return SIGIL_NO_CLAUSE
        val pieces = unescapedPieces(sigil.pieces, EscapeMap.DEFAULT, run.level) { return it }
        val text = pieces.singleOrNull() as? ElixirAst.Literal.Binary
        val interpolation = sigil.interpolation

        return words(node, sigil.modifiers, text ?: ElixirAst.Call(interpolation.meta, interpolation.callee, pieces), run.level)
    }
}

/** `Kernel.sigil_W/2`: the words of the text as written. */
internal val SIGIL_W_UPPER = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val text = sigil(node)?.text ?: return SIGIL_NO_CLAUSE

        return words(node, node.arguments!![1], text, run.level)
    }
}

/** `split_words/3`: [string] is the binary, or the `<<>>` call that builds it. */
private fun words(node: ElixirAst.Call, modifiers: ElixirAst, string: ElixirAst, level: ElixirLanguageLevel): Summary.Output {
    val mode = (modifiers as? ElixirAst.ListNode)?.elements?.let { elements ->
        when (elements.size) {
            0 -> 's'
            1 -> (elements[0] as? ElixirAst.Literal.Integer)?.value?.takeIf { it.bitLength() <= 8 }?.toInt()?.toChar()?.takeIf { it in "sac" }
            else -> null
        }
    } ?: return Summary.Output.Raised("sigil_w_modifier")
    val s = Synthetic(node.meta)

    if (string is ElixirAst.Literal.Binary) {
        val text = decodeUtf8(string.bytes) ?: return Summary.Output.Unported(node)
        val parts = splitOnWhitespace(text)
        val words = when (mode) {
            's' -> parts.map { ElixirAst.Literal.Binary(s.meta(), it.toByteArray()) }
            'a' -> parts.map { if (fitsAnAtom(it)) s.atom(it) else return Summary.Output.Unported(node) }
            else -> parts.map { s.list(it.codePoints().toArray().map { code -> s.integer(BigInteger.valueOf(code.toLong())) }) }
        }

        return Summary.Output.Built(s.list(words))
    }

    val split = s.remoteCall(kernelAlias(s, "String"), "split", listOf(string))

    if (mode == 's') return Summary.Output.Built(split)

    val convert = if (mode == 'a') "to_atom" else "to_charlist"
    val function = ElixirAst.Call(
        s.meta(listOf(entry("no_parens", "true"))),
        ElixirAst.Call(s.meta(), s.atom("."), listOf(kernelAlias(s, "String"), s.atom(convert))),
        emptyList(),
    )
    val capture = s.call("&", listOf(s.call("/", listOf(function, s.integer(BigInteger.ONE)), kernelImportKeys(level, 2))))

    return Summary.Output.Built(s.remoteCall("lists", "map", listOf(capture, split)))
}

/** `~r`'s modifiers, an unrecognised one raising: the translation `Regex.compile!/2` makes of a modifier. */
private fun translateOptions(s: Synthetic, letters: ByteArray): List<ElixirAst>? {
    var options = emptyList<ElixirAst>()

    for (letter in letters) {
        val translated: List<ElixirAst> = when (letter.toInt().toChar()) {
            's' -> listOf(s.atom("dotall"), s.tuple(s.atom("newline"), s.atom("anycrlf")))
            'u' -> listOf(s.atom("unicode"), s.atom("ucp"))
            'i' -> listOf(s.atom("caseless"))
            'x' -> listOf(s.atom("extended"))
            'f' -> listOf(s.atom("firstline"))
            'U', 'r' -> listOf(s.atom("ungreedy"))
            'm' -> listOf(s.atom("multiline"))
            else -> return null
        }

        options = translated + options
    }

    return options
}

/** `Kernel.sigil_r/2`: unescaped as `Regex.unescape_map/1` does, and in neither a pattern nor a guard from 1.20. */
internal val SIGIL_R = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val sigil = sigil(node) ?: return SIGIL_NO_CLAUSE

        if (REGEX_REFUSED_IN_MATCH_AND_GUARD.isSufficient(run.level)) assertNoMatchOrGuardScope(env)?.let { return it }

        val pieces = unescapedPieces(sigil.pieces, EscapeMap.REGEX, run.level) { return it }
        val interpolation = sigil.interpolation
        val pattern = pieces.singleOrNull() as? ElixirAst.Literal.Binary
            ?: ElixirAst.Call(interpolation.meta, interpolation.callee, pieces)

        return compileRegex(node, pattern, sigil.modifiers, run.level)
    }
}

/** `Kernel.sigil_R/2`: the text as written. */
internal val SIGIL_R_UPPER = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val sigil = sigil(node)
        val text = sigil?.text ?: return SIGIL_NO_CLAUSE

        return compileRegex(node, text, sigil.modifiers, run.level)
    }
}

/**
 * `compile_regex/2`: [pattern] is the binary, or the `<<>>` call that builds it. A pattern Elixir would find invalid is
 * taken to compile.
 */
private fun compileRegex(
    node: ElixirAst.Call,
    pattern: ElixirAst,
    modifiers: ElixirAst,
    level: ElixirLanguageLevel,
): Summary.Output {
    val letters = (modifiers as? ElixirAst.ListNode)?.elements?.map { element ->
        (element as? ElixirAst.Literal.Integer)?.value?.takeIf { it.signum() >= 0 && it.bitLength() <= 8 }?.toInt()?.toByte()
    }?.takeIf { null !in it }?.filterNotNull()?.toByteArray() ?: return Summary.Output.Unported(node)
    val s = Synthetic(node.meta)
    val options = ElixirAst.Literal.Binary(s.meta(), letters)
    val compile = { source: ElixirAst -> Summary.Output.Built(s.remoteCall(kernelAlias(s, "Regex"), "compile!", listOf(source, options))) }

    if (pattern !is ElixirAst.Literal.Binary) return compile(pattern)
    if (REGEX_COMPILED_AT_RUN_TIME_ON_OTP_28.isSufficient(level)) return compile(ElixirAst.Literal.Binary(s.meta(), pattern.bytes))

    if ('E'.code.toByte() in letters) return Summary.Output.Unported(node)

    val translated = translateOptions(s, letters) ?: return Summary.Output.Raised("regex_invalid_option")
    val source = ElixirAst.Literal.Binary(s.meta(), pattern.bytes)
    val compiled = { ElixirAst.Placeholder(s.meta(), ElixirAst.Placeholder.Reason.Compiled) }
    val rePattern = when {
        ESCAPED_REGEX_IMPORTS_ON_OTP_28_1.isSufficient(level) -> {
            val exported = s.tuple(
                listOf(s.atom("re_exported_pattern"), compiled(), source, s.list(listOf(s.atom("export")) + translated), compiled()),
                emptyList(),
            )

            ElixirAst.Call(
                s.meta(listOf(entry("required", "true"))),
                ElixirAst.Call(s.meta(), s.atom("."), listOf(kernelAlias(s, "Regex"), s.atom("__import_pattern__"))),
                listOf(exported),
            )
        }
        ESCAPED_REGEX_RECOMPILES_ON_OTP_28.isSufficient(level) -> return Summary.Output.Unported(node)
        else -> s.tuple(listOf(s.atom("re_pattern"), compiled(), compiled(), compiled(), compiled()), emptyList())
    }
    val fields = buildMap {
        put("__struct__", s.atom("Elixir.Regex"))
        put("opts", if (REGEX_OPTIONS_AS_LIST.isSufficient(level)) s.list(translated) else options)
        put("re_pattern", rePattern)
        if (RE_VERSION_IN_REGEX.isSufficient(level)) put("re_version", compiled())
        put("source", source)
    }

    if (!REGEX_ESCAPED_IN_FIXED_FIELD_ORDER.isSufficient(level)) return Summary.Output.Built(s.escapedMap(fields.map { it.key to it.value }))

    val keys = listOf("__struct__", "re_pattern", "source", "opts")

    return Summary.Output.Built(s.call("%{}", keys.map { key -> s.tuple(s.atom(key), fields.getValue(key)) }))
}

/** [pieces] with each binary unescaped by [map], and anything else as it is; [failed] gets the raise of one that fails. */
private inline fun unescapedPieces(
    pieces: List<ElixirAst>,
    map: EscapeMap,
    level: ElixirLanguageLevel,
    failed: (Summary.Output) -> Nothing,
): List<ElixirAst> =
    pieces.map { piece ->
        if (piece is ElixirAst.Literal.Binary) {
            ElixirAst.Literal.Binary(piece.meta, unescapedBytes(piece, map, level, failed))
        } else {
            piece
        }
    }

private inline fun unescapedBytes(
    piece: ElixirAst.Literal.Binary,
    map: EscapeMap,
    level: ElixirLanguageLevel,
    failed: (Summary.Output) -> Nothing,
): ByteArray =
    when (val unescaped = unescapeString(piece.bytes, map, level)) {
        is Unescaped.Text -> unescaped.bytes
        is Unescaped.Failed -> failed(Summary.Output.Raised(unescaped.kind))
    }

/** [bytes] as a charlist, `String.to_charlist/1`, or `null` where they are not UTF-8 and it raises. */
private fun charlist(s: Synthetic, bytes: ByteArray): ElixirAst.ListNode? =
    decodeUtf8(bytes)?.let { text -> s.list(text.codePoints().toArray().map { s.integer(BigInteger.valueOf(it.toLong())) }) }

/** [bytes] as text, or `null` where they are not UTF-8. */
internal fun decodeUtf8(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

/** `String.split/1`: at runs of Unicode whitespace, without the no-break spaces, leaving no empty part. */
private fun splitOnWhitespace(text: String): List<String> {
    val parts = mutableListOf<String>()
    val part = StringBuilder()

    text.codePoints().forEach { codePoint ->
        if (isWhitespace(codePoint)) {
            if (part.isNotEmpty()) parts.add(part.toString())

            part.setLength(0)
        } else {
            part.appendCodePoint(codePoint)
        }
    }

    if (part.isNotEmpty()) parts.add(part.toString())

    return parts
}

private fun isWhitespace(codePoint: Int): Boolean =
    codePoint in 0x09..0x0D || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0x1680 ||
        codePoint in 0x2000..0x2006 || codePoint in 0x2008..0x200A || codePoint == 0x2028 || codePoint == 0x2029 ||
        codePoint == 0x205F || codePoint == 0x3000
