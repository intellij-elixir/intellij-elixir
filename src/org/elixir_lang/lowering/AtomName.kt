package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpExternal
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.language_level.ElixirLanguageFeature.EMPTY_LEADING_HEREDOC_SEGMENT
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER
import org.elixir_lang.language_level.ElixirLanguageFeature.UNESCAPED_QUOTED_REMOTE_CALL_NAME
import org.elixir_lang.language_level.ElixirLanguageFeature.UNESCAPED_SIGIL_HEREDOC_TERMINATOR
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.psi.ElixirAtIdentifier
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirAtomKeyword
import org.elixir_lang.psi.ElixirEscapedCharacter
import org.elixir_lang.psi.ElixirInterpolation
import org.elixir_lang.psi.ElixirKeywordKey
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.ElixirQuoteHexadecimalEscapeSequence
import org.elixir_lang.psi.ElixirRelativeIdentifier
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.EscapeSequence
import org.elixir_lang.psi.HeredocLiteral
import org.elixir_lang.psi.Interpolated
import org.elixir_lang.psi.Operator
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.SigilHeredocLiteral
import org.elixir_lang.psi.SigilLine
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.impl.operatorTokenNode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * The atom a name written in source is, from its PSI and the language level alone. The lowering builds each name's atom
 * from it, so a caller that needs only the name gets the lowering's answer without lowering.
 */
object AtomName {
    /** [element]'s atom name at its own language level. */
    @RequiresReadLock
    @JvmStatic
    fun of(element: PsiElement): String? = of(element, ElixirLanguageLevelResolver.languageLevelFor(element))

    /**
     * [element]'s atom name: an atom, keyword key, identifier, remote call name, variable, attribute name, operator or
     * `true`/`false`/`nil`. `null` for any other element, an interpolated or broken quoted name, and a name longer than
     * an atom may be.
     */
    @RequiresReadLock
    @JvmStatic
    fun of(element: PsiElement, languageLevel: ElixirLanguageLevel): String? = occurrence(element, languageLevel)?.name

    /** [of], with the element whose text spells the name. */
    @RequiresReadLock
    @JvmStatic
    fun occurrence(element: PsiElement, languageLevel: ElixirLanguageLevel): Occurrence? =
        NAMES[PsiUtilCore.getElementType(element)]?.invoke(element, languageLevel)?.let { Occurrence(element, it) }

    /** A name, and the [element] that spells it. */
    data class Occurrence(val element: PsiElement, val name: String)

    private val OPERATOR_NAME: (PsiElement, ElixirLanguageLevel) -> String? =
        { element, _ -> (element as Operator).operatorTokenNode().text }

    private val NAMES: Map<IElementType, (PsiElement, ElixirLanguageLevel) -> String?> = mapOf(
        ElixirTypes.ATOM to { element, languageLevel ->
            element as ElixirAtom

            when (val line = element.line) {
                null -> unquoted(element.node.lastChildNode.text, languageLevel)
                else -> quoted(line, languageLevel)
            }
        },
        ElixirTypes.KEYWORD_KEY to { element, languageLevel ->
            element as ElixirKeywordKey

            when (val line = element.line) {
                null -> unquoted(element.text, languageLevel)
                else -> quoted(line, languageLevel)
            }
        },
        ElixirTypes.IDENTIFIER to { element, languageLevel -> unquoted(element.text, languageLevel) },
        ElixirTypes.VARIABLE to { element, languageLevel -> unquoted(element.text, languageLevel) },
        ElixirTypes.AT_IDENTIFIER to { element, languageLevel ->
            unquoted((element as ElixirAtIdentifier).identifierName(), languageLevel)
        },
        ElixirTypes.RELATIVE_IDENTIFIER to { element, languageLevel ->
            (remoteCallName(element as ElixirRelativeIdentifier, languageLevel) as? RemoteCallName.Named)?.atom
        },
        ElixirTypes.ATOM_KEYWORD to { element, _ -> element.text },
        ElixirTypes.ADDITION_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.AND_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.ARROW_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.AT_PREFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.CAPTURE_PREFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.COMPARISON_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.DOT_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.IN_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.IN_MATCH_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.MAP_PREFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.MATCH_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.MULTIPLICATION_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.NOT_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.OR_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.PIPE_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.POWER_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.RELATIONAL_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.STAB_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.TERNARY_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.THREE_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.TWO_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.TYPE_INFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.UNARY_PREFIX_OPERATOR to OPERATOR_NAME,
        ElixirTypes.WHEN_INFIX_OPERATOR to OPERATOR_NAME,
    )

    /** The element types whose PSI [of] can name: it answers `null` for every other. */
    @JvmField
    val NAMED: TokenSet = TokenSet.create(*NAMES.keys.toTypedArray())

    /** An identifier written as [text], normalized as [identifierAtomName] gives it, if an atom may be that long. */
    internal fun unquoted(text: String, languageLevel: ElixirLanguageLevel): String? =
        identifierAtomName(text) { languageLevel }.takeIf(::fitsAnAtom)

    /** The name quoted [content] spells, if it has no interpolation and an atom may be that long. */
    internal fun literal(content: Content): String? =
        when (content) {
            is Content.Empty -> ""
            is Content.Literal -> text(utf8(content.codePoints))?.takeIf(::fitsAnAtom)
            is Content.Interpolated -> null
        }

    /**
     * The text [bytes] spell, or `null` when they are not UTF-8, as Elixir names no atom for them. [startsInside] says
     * an interpolation ends just before the first byte, so the continuation bytes it opens with finish that
     * interpolation's character and are not read here. [endsInside] says an interpolation follows, so a sequence the
     * bytes leave incomplete is finished by it.
     */
    internal fun text(bytes: ByteArray, startsInside: Boolean = false, endsInside: Boolean = false): String? {
        val first = if (startsInside) bytes.asSequence().take(3).takeWhile(::isContinuation).count() else 0
        val input = ByteBuffer.wrap(bytes, first, bytes.size - first)
        val output = CharBuffer.allocate(bytes.size)
        val result = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(input, output, !endsInside)

        return if (result.isError) null else output.flip().toString()
    }

    private fun isContinuation(byte: Byte): Boolean = byte.toInt() and 0xC0 == 0x80

    private fun quoted(line: ElixirLine, languageLevel: ElixirLanguageLevel): String? =
        content(line, line.pieces(), languageLevel)?.let(::literal)

    /** What a remote call's name [relativeIdentifier] is at [languageLevel]. */
    @RequiresReadLock
    internal fun remoteCallName(
        relativeIdentifier: ElixirRelativeIdentifier,
        languageLevel: ElixirLanguageLevel
    ): RemoteCallName =
        when (val child = relativeIdentifier.children.singleOrNull()) {
            null ->
                unquoted(relativeIdentifier.node.firstChildNode.text, languageLevel)
                    ?.let(RemoteCallName::Named) ?: RemoteCallName.Broken
            is ElixirLine ->
                when {
                    child.lineBody?.interpolationList.orEmpty().isNotEmpty() -> RemoteCallName.NoCall
                    UNESCAPED_QUOTED_REMOTE_CALL_NAME.isSufficient(languageLevel) ->
                        quoted(child, languageLevel)?.let(RemoteCallName::Named) ?: RemoteCallName.NoCall
                    else ->
                        literalQuotedRemoteCallName(child, ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER.isSufficient(languageLevel))
                            .takeIf(::fitsAnAtom)?.let(RemoteCallName::Named) ?: RemoteCallName.Broken
                }
            is ElixirAtomKeyword -> RemoteCallName.Named(child.text)
            else -> RemoteCallName.NoCall
        }
}

/**
 * A remote call's name: its atom, or why it has none. A name Elixir's tokenizer rejects is [Broken]; an interpolated
 * or otherwise unreadable one is [NoCall], no remote call at all.
 */
internal sealed interface RemoteCallName {
    data class Named(val atom: String) : RemoteCallName

    data object NoCall : RemoteCallName

    data object Broken : RemoteCallName
}

internal fun fitsAnAtom(name: String): Boolean = name.codePointCount(0, name.length) <= OtpExternal.maxAtomLength

/** A quoted atom, keyword key or remote call name's pieces. */
internal fun ElixirLine.pieces(): List<Piece> = lineBody?.node?.getChildren(null)?.toList().orEmpty().pieces()

internal fun List<ASTNode>.pieces(): List<Piece> = map { Piece(it.elementType, it.text, it) }

/**
 * A quoted remote call's name as Elixir 1.17 and earlier kept it: `extract` without unescaping, which still drops the
 * `\` before the terminator, and before 1.12 a `\` ending a line with its newline, but keeps every other escape as
 * written.
 */
private fun literalQuotedRemoteCallName(line: ElixirLine, keepsLineContinuation: Boolean): String {
    val text = line.lineBody?.text.orEmpty()
    val terminator = if (line.isCharList) '\'' else '"'
    val name = StringBuilder()
    var index = 0

    while (index < text.length) {
        if (text[index] == '\\' && index + 1 < text.length) {
            if (keepsLineContinuation || text[index + 1] != '\n') {
                if (text[index + 1] != terminator) name.append('\\')
                name.append(text[index + 1])
            }
            index += 2
        } else {
            name.append(text[index])
            index += 1
        }
    }

    return name.toString()
}

// Quoted text

/** A string, charlist or quoted atom's text: the parts between its interpolations, and the interpolations. */
internal sealed class Part {
    class Text(val codePoints: List<Int>) : Part()
    class Interpolation(val interpolation: ElixirInterpolation) : Part()
}

/** What quoted text holds: nothing, only text, or interpolations. */
internal sealed class Content {
    object Empty : Content()
    class Literal(val codePoints: List<Int>) : Content()
    class Interpolated(val parts: List<Part>) : Content()
}

/** A node of quoted text, or text standing for one, as a heredoc's lines give it. */
internal class Piece(val elementType: IElementType, val text: String, val node: ASTNode?)

/**
 * Added to a byte to carry it through a code point list, as `\xHH` escapes a byte rather than a code point. Past what
 * six hex digits can escape, so not even an invalid `\u{110000}` collides with it.
 */
internal const val RAW_BYTE_OFFSET = 0x1000000

/** [parent]'s text as Elixir's tokenizer extracts it, or `null` when a node is one it cannot extract. */
internal fun content(parent: PsiElement, pieces: List<Piece>, languageLevel: ElixirLanguageLevel): Content? {
    if (pieces.isEmpty()) return Content.Empty

    val parts = mutableListOf<Part>()
    var buffer: MutableList<Int>? = null
    val keepsEmptyBuffer = ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER.isSufficient(languageLevel)

    fun buffer(): MutableList<Int> = buffer ?: mutableListOf<Int>().also { buffer = it }

    for (piece in pieces) {
        when (piece.elementType) {
            ElixirTypes.FRAGMENT, ElixirTypes.EOL -> buffer().addAll(codePoints(piece.text))
            ElixirTypes.ESCAPED_CHARACTER ->
                if (parent is Sigil) {
                    val text = piece.text
                    val terminator = (parent as? SigilLine)?.terminator()

                    buffer().addAll(codePoints(if (terminator != null && text == "\\$terminator") "$terminator" else text))
                } else {
                    buffer().add((piece.node?.psi as? ElixirEscapedCharacter ?: return null).codePoint())
                }
            ElixirTypes.ESCAPED_EOL ->
                buffer().apply {
                    if (parent is Sigil && (parent !is Interpolated || keepsEmptyBuffer)) addAll(listOf('\\'.code, '\n'.code))
                }
            ElixirTypes.ESCAPED_HEREDOC_TERMINATOR, ElixirTypes.ESCAPED_LINE_TERMINATOR ->
                buffer().addAll(
                    codePoints(
                        if (parent is SigilHeredocLiteral && !UNESCAPED_SIGIL_HEREDOC_TERMINATOR.isSufficient(languageLevel)) {
                            piece.text
                        } else {
                            piece.node?.psi?.lastChild?.text ?: return null
                        }
                    )
                )
            ElixirTypes.HEXADECIMAL_ESCAPE_PREFIX -> buffer().addAll(codePoints(piece.text))
            ElixirTypes.INTERPOLATION -> {
                buffer?.let { if (it.isNotEmpty() || keepsEmptyBuffer) parts.add(Part.Text(it)) }
                buffer = null

                if (parent is HeredocLiteral && parts.isEmpty() && EMPTY_LEADING_HEREDOC_SEGMENT.isSufficient(languageLevel)) {
                    parts.add(Part.Text(emptyList()))
                }

                parts.add(Part.Interpolation(piece.node?.psi as? ElixirInterpolation ?: return null))
            }
            ElixirTypes.QUOTE_HEXADECIMAL_ESCAPE_SEQUENCE, ElixirTypes.SIGIL_HEXADECIMAL_ESCAPE_SEQUENCE -> {
                val sequence = piece.node?.psi
                val byte = (sequence as? ElixirQuoteHexadecimalEscapeSequence)?.let { escapedByte(it) }

                when {
                    byte != null -> buffer().add(RAW_BYTE_OFFSET + byte)
                    parent is Sigil -> buffer().addAll(codePoints(piece.text))
                    else ->
                        buffer().add(
                            (sequence as? EscapeSequence ?: return null).codePoint()
                                .takeIf { it <= Character.MAX_CODE_POINT && it !in SURROGATES } ?: return null
                        )
                }
            }
            else -> return null
        }
    }

    val text = buffer

    return if (text != null && parts.isEmpty()) {
        Content.Literal(text)
    } else {
        if (text != null && (text.isNotEmpty() || keepsEmptyBuffer)) parts.add(Part.Text(text))

        Content.Interpolated(parts)
    }
}

/**
 * `unescape_hex` appends one byte, so `"\xC3\xA9"` is `"é"`; Elixir 1.11's deprecated `\xH` and `\x{H*}` are code
 * points.
 */
private fun escapedByte(sequence: ElixirQuoteHexadecimalEscapeSequence): Int? {
    if (sequence.hexadecimalEscapePrefix.text != "\\x") return null
    val digits = sequence.openHexadecimalEscapeSequence?.text?.takeIf { it.length == 2 } ?: return null

    return digits.toInt(16).takeIf { it >= 0x80 }
}

internal fun codePoints(text: String): List<Int> = text.codePoints().toArray().toList()

internal fun utf8(codePoints: List<Int>): ByteArray {
    val bytes = ByteArrayOutputStream()
    val pending = StringBuilder()

    for (codePoint in codePoints) {
        if (codePoint >= RAW_BYTE_OFFSET) {
            bytes.write(pending.toString().toByteArray(Charsets.UTF_8))
            pending.setLength(0)
            bytes.write(codePoint - RAW_BYTE_OFFSET)
        } else {
            pending.appendCodePoint(codePoint)
        }
    }

    bytes.write(pending.toString().toByteArray(Charsets.UTF_8))

    return bytes.toByteArray()
}

/** Code points Elixir refuses in an escape, as UTF-8 cannot encode them. */
private val SURROGATES = Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
