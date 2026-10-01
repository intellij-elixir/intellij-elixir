package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpExternal
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import org.elixir_lang.language_level.ElixirLanguageFeature.ASSOC_ON_MAP_KEY
import org.elixir_lang.language_level.ElixirLanguageFeature.DELIMITER_OF_SINGLE_QUOTED_ATOM
import org.elixir_lang.language_level.ElixirLanguageFeature.DELIMITER_ON_QUOTED_ATOM
import org.elixir_lang.language_level.ElixirLanguageFeature.DELIMITER_ON_QUOTED_KEYWORD_KEY
import org.elixir_lang.language_level.ElixirLanguageFeature.EMPTY_LEADING_HEREDOC_SEGMENT
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER
import org.elixir_lang.language_level.ElixirLanguageFeature.FROM_INTERPOLATION
import org.elixir_lang.language_level.ElixirLanguageFeature.INDENTATION_ON_HEREDOC
import org.elixir_lang.language_level.ElixirLanguageFeature.LAST_ON_ALIAS
import org.elixir_lang.language_level.ElixirLanguageFeature.MAP_COLUMN_AT_PERCENT
import org.elixir_lang.language_level.ElixirLanguageFeature.UNESCAPED_SIGIL_HEREDOC_TERMINATOR
import org.elixir_lang.psi.Digits
import org.elixir_lang.psi.ElixirAccessExpression
import org.elixir_lang.psi.ElixirAlias
import org.elixir_lang.psi.ElixirAssociations
import org.elixir_lang.psi.ElixirAssociationsBase
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirAtomKeyword
import org.elixir_lang.psi.ElixirBitString
import org.elixir_lang.psi.ElixirCharToken
import org.elixir_lang.psi.ElixirContainerAssociationOperation
import org.elixir_lang.psi.ElixirDecimalFloat
import org.elixir_lang.psi.ElixirEmptyParentheses
import org.elixir_lang.psi.ElixirEscapedCharacter
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ElixirHeredoc
import org.elixir_lang.psi.ElixirInterpolation
import org.elixir_lang.psi.ElixirKeywordKey
import org.elixir_lang.psi.ElixirKeywordPair
import org.elixir_lang.psi.ElixirKeywords
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.ElixirMapArguments
import org.elixir_lang.psi.ElixirMapOperation
import org.elixir_lang.psi.ElixirMapUpdateArguments
import org.elixir_lang.psi.ElixirQuoteHexadecimalEscapeSequence
import org.elixir_lang.psi.ElixirSigilModifiers
import org.elixir_lang.psi.ElixirStructOperation
import org.elixir_lang.psi.ElixirTuple
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.EscapeSequence
import org.elixir_lang.psi.HeredocLiteral
import org.elixir_lang.psi.Interpolated
import org.elixir_lang.psi.Operator
import org.elixir_lang.psi.QualifiedAlias
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.SigilHeredocLiteral
import org.elixir_lang.psi.SigilLine
import org.elixir_lang.psi.WholeNumber
import org.elixir_lang.psi.impl.inBase
import org.elixir_lang.psi.impl.operatorTokenNode
import org.elixir_lang.psi.impl.textToString
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** Literals and containers: numbers, atoms, aliases, strings, charlists, sigils, heredocs and the containers. */
internal fun Lowering.literal(element: PsiElement): ElixirAst =
    when (element) {
        is ElixirFile -> file(element)
        is ElixirAccessExpression -> element.children.singleOrNull()?.let { lower(it) } ?: broken(element)
        is ElixirAlias -> alias(element)
        is QualifiedAlias -> qualifiedAlias(element)
        is ElixirAtom -> atom(element)
        is ElixirAtomKeyword -> ElixirAst.Literal.Atom(meta(element), element.text)
        is ElixirCharToken -> charToken(element)
        is ElixirDecimalFloat -> decimalFloat(element)
        is WholeNumber -> wholeNumber(element)
        is Digits -> digits(element)
        is ElixirLine -> quote(element, element.isCharList, "\"", element.lineBody?.node?.getChildren(null)?.toList().orEmpty())
        is ElixirHeredoc -> quote(element, element.isCharList, "\"\"\"", heredocPieces(element))
        is Sigil -> sigil(element)
        is ElixirSigilModifiers -> modifiers(element)
        is ElixirList -> list(element)
        is ElixirKeywords -> ElixirAst.ListNode(meta(element), element.keywordPairList.map { keywordPair(it) })
        is ElixirKeywordPair -> keywordPair(element)
        is ElixirKeywordKey -> keywordKey(element)
        is ElixirTuple -> tuple(element)
        is ElixirBitString -> bitString(element)
        is ElixirMapOperation -> map(element)
        is ElixirStructOperation -> struct(element)
        is ElixirMapArguments -> mapArguments(element, element.parent as? ElixirMapOperation)
        is ElixirMapUpdateArguments -> mapUpdate(element)
        is ElixirAssociations -> associations(element.associationsBase)
        is ElixirAssociationsBase -> associations(element)
        is ElixirContainerAssociationOperation -> association(element)
        is ElixirEmptyParentheses -> emptyParentheses(element)
        else -> unlowered(element)
    }

// Numbers

private fun Lowering.digits(digits: Digits): ElixirAst =
    ElixirAst.Literal.Integer(meta(digits), BigInteger(digits.text, digits.base()))

private fun Lowering.wholeNumber(wholeNumber: WholeNumber): ElixirAst {
    val digitsList = wholeNumber.digitsList()
    val text = digitsList.textToString()
    val base = wholeNumber.base()

    return if (digitsList.inBase()) {
        ElixirAst.Literal.Integer(meta(wholeNumber), BigInteger(text, base))
    } else {
        // Elixir rejects this at parse time; the conversion raises in its place.
        remoteCall(
            wholeNumber,
            "Elixir.String",
            "to_integer",
            binary(wholeNumber, text),
            ElixirAst.Literal.Integer(meta(wholeNumber), base.toBigInteger())
        )
    }
}

private fun Lowering.decimalFloat(decimalFloat: ElixirDecimalFloat): ElixirAst {
    val integral = decimalFloat.decimalFloatIntegral.decimalWholeNumber.digitsList()
    val fractional = decimalFloat.decimalFloatFractional.decimalWholeNumber.digitsList()
    val exponent = decimalFloat.decimalFloatExponent
    val exponentDigits = exponent?.decimalWholeNumber?.digitsList()
    val text = "${integral.textToString()}.${fractional.textToString()}" +
        if (exponent != null) "e${exponent.decimalFloatExponentSign?.text.orEmpty()}${exponentDigits.orEmpty().textToString()}" else ""

    return if (integral.inBase() && fractional.inBase() && exponentDigits?.inBase() != false) {
        ElixirAst.Literal.Float(meta(decimalFloat), text.toDouble())
    } else {
        remoteCall(decimalFloat, "Elixir.String", "to_float", binary(decimalFloat, text))
    }
}

private fun Lowering.charToken(charToken: ElixirCharToken): ElixirAst {
    val tokenized = charToken.node.getChildren(null).getOrNull(1) ?: return broken(charToken)
    val codePoint =
        if (tokenized.elementType == ElixirTypes.FRAGMENT) {
            tokenized.text.takeIf { it.codePointCount(0, it.length) == 1 }?.codePointAt(0)
        } else {
            (tokenized.psi as? EscapeSequence)?.codePoint()
        }

    return codePoint?.let { ElixirAst.Literal.Integer(meta(charToken), it.toBigInteger()) } ?: broken(charToken)
}

// Atoms and aliases

private fun Lowering.atom(atom: ElixirAtom): ElixirAst {
    val line = atom.line ?: return writtenAtom(atom, identifier(atom.node.lastChildNode.text))
    val delimiter =
        if (line.isCharList && isAvailable(DELIMITER_OF_SINGLE_QUOTED_ATOM)) "'" else "\""

    return quotedAtom(
        atom,
        line,
        listOfNotNull(tokenMetadata("delimiter", delimiter).takeIf { isAvailable(DELIMITER_ON_QUOTED_ATOM) })
    )
}

private fun Lowering.alias(alias: ElixirAlias): ElixirAst =
    ElixirAst.Alias(
        meta(alias, last(alias.textRange.startOffset), location(alias)),
        listOf(writtenAtom(alias, alias.text))
    )

/** `Left.Right`: one alias of both, at `Left`, when `Left` is one; otherwise at the `.`. */
private fun Lowering.qualifiedAlias(qualifiedAlias: QualifiedAlias): ElixirAst {
    val children = qualifiedAlias.children
    val left = children.firstOrNull()?.let { lower(it) } ?: return broken(qualifiedAlias)
    val right = children.lastOrNull() as? ElixirAlias ?: return broken(qualifiedAlias)
    val segment = writtenAtom(right, right.text)
    val last = last(right.textRange.startOffset)

    return if (left is ElixirAst.Alias) {
        // Elixir keeps the left alias's metadata, replacing its `last` in place.
        val keys = left.meta.keys.toMutableList()
        val lastIndex = keys.indexOfFirst { it is Meta.Key.Entry && it.name == "last" }

        if (last != null) {
            if (lastIndex >= 0) keys[lastIndex] = last else keys.add(last)
        }

        ElixirAst.Alias(meta(qualifiedAlias, *keys.toTypedArray()), left.segments + segment)
    } else {
        val dot = children.getOrNull(1) as? Operator ?: return broken(qualifiedAlias)
        ElixirAst.Alias(meta(qualifiedAlias, last, location(dot.operatorTokenNode())), listOf(left, segment))
    }
}

private fun Lowering.last(offset: Int): Meta.Key? =
    Meta.Key.Entry("last", Meta.Value.Keywords(listOf(location(offset))), tokenMetadata = true)
        .takeIf { isAvailable(LAST_ON_ALIAS) }

// Quoted text

/** A string, charlist or quoted atom's text: the parts between its interpolations, and the interpolations. */
private sealed class Part {
    class Text(val codePoints: List<Int>) : Part()
    class Interpolation(val interpolation: ElixirInterpolation) : Part()
}

/** What quoted text holds: nothing, only text, or interpolations. */
private sealed class Content {
    object Empty : Content()
    class Literal(val codePoints: List<Int>) : Content()
    class Interpolated(val parts: List<Part>) : Content()
}

/** A node of quoted text, or text standing for one, as a heredoc's lines give it. */
private class Piece(val elementType: IElementType, val text: String, val node: ASTNode?)

/**
 * Added to a byte to carry it through a code point list, as `\xHH` escapes a byte rather than a code point. Past what
 * six hex digits can escape, so not even an invalid `\u{110000}` collides with it.
 */
private const val RAW_BYTE_OFFSET = 0x1000000

/** [parent]'s text as Elixir's tokenizer extracts it, or `null` when a node is one it cannot extract. */
private fun Lowering.content(parent: PsiElement, pieces: List<Piece>): Content? {
    if (pieces.isEmpty()) return Content.Empty

    val parts = mutableListOf<Part>()
    var buffer: MutableList<Int>? = null
    val keepsEmptyBuffer = isAvailable(ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER)

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
                        if (parent is SigilHeredocLiteral && !isAvailable(UNESCAPED_SIGIL_HEREDOC_TERMINATOR)) {
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

                if (parent is HeredocLiteral && parts.isEmpty() && isAvailable(EMPTY_LEADING_HEREDOC_SEGMENT)) {
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

private fun codePoints(text: String): List<Int> = text.codePoints().toArray().toList()

private fun utf8(codePoints: List<Int>): ByteArray {
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

/** A charlist's code points: Elixir decodes its escaped bytes as UTF-8. */
private fun charListCodePoints(codePoints: List<Int>): List<Int> =
    if (codePoints.any { it >= RAW_BYTE_OFFSET }) {
        String(utf8(codePoints), Charsets.UTF_8).codePoints().toArray().toList()
    } else {
        codePoints
    }

private fun Lowering.charList(element: PsiElement, codePoints: List<Int>): ElixirAst =
    ElixirAst.ListNode(meta(element), charListCodePoints(codePoints).map { ElixirAst.Literal.Integer(meta(element), it.toBigInteger()) })

private fun Lowering.binary(element: PsiElement, codePoints: List<Int>): ElixirAst =
    ElixirAst.Literal.Binary(meta(element), utf8(codePoints))

private fun Lowering.binary(element: PsiElement, text: String): ElixirAst =
    ElixirAst.Literal.Binary(meta(element), text.toByteArray(Charsets.UTF_8))

/** The pieces of each of [heredoc]'s lines after its indentation, with neighbouring fragments merged. */
private fun heredocPieces(heredoc: HeredocLiteral): List<Piece> {
    val indentation = heredoc.heredocPrefix.textLength
    val pieces = mutableListOf<Piece>()

    for (line in heredoc.heredocLineList) {
        line.heredocLinePrefix.text.takeIf { it.length > indentation }?.let {
            pieces.add(Piece(ElixirTypes.FRAGMENT, it.substring(indentation), null))
        }
        line.body.node.getChildren(null).mapTo(pieces) { Piece(it.elementType, it.text, it) }
        line.lastChild.node.let { pieces.add(Piece(it.elementType, it.text, it)) }
    }

    return pieces.fold(mutableListOf()) { merged, piece ->
        val previous = merged.lastOrNull()

        if (piece.elementType == ElixirTypes.FRAGMENT && previous?.elementType == ElixirTypes.FRAGMENT) {
            merged[merged.lastIndex] = Piece(ElixirTypes.FRAGMENT, previous.text + piece.text, null)
        } else {
            merged.add(piece)
        }

        merged
    }
}

private fun List<ASTNode>.pieces(): List<Piece> = map { Piece(it.elementType, it.text, it) }

@JvmName("quoteNodes")
private fun Lowering.quote(element: PsiElement, isCharList: Boolean, delimiter: String, nodes: List<ASTNode>): ElixirAst =
    quote(element, isCharList, delimiter, nodes.pieces())

/** A string or charlist, line or heredoc. */
private fun Lowering.quote(element: PsiElement, isCharList: Boolean, doubleQuote: String, pieces: List<Piece>): ElixirAst {
    val content = content(element, pieces) ?: return broken(element)
    val delimiter = if (isCharList) doubleQuote.replace('"', '\'') else doubleQuote
    val indentation = (element as? HeredocLiteral)
        ?.takeIf { isAvailable(INDENTATION_ON_HEREDOC) }
        ?.let { tokenMetadata("indentation", it.heredocPrefix.textLength) }

    return when (content) {
        is Content.Empty -> if (isCharList) charList(element, emptyList()) else binary(element, "")
        is Content.Literal -> if (isCharList) charList(element, content.codePoints) else binary(element, content.codePoints)
        is Content.Interpolated -> {
            val keys = arrayOf(tokenMetadata("delimiter", delimiter), indentation, location(element))

            if (isCharList) {
                remoteCall(
                    element,
                    "Elixir.List",
                    "to_charlist",
                    ElixirAst.ListNode(meta(element), content.parts.map { part(element, it, typed = false) }),
                    callKeys = keys
                )
            } else {
                ElixirAst.Call(meta(element, *keys), atom(element, "<<>>"), content.parts.map { part(element, it, typed = true) })
            }
        }
    }
}

/** A quoted atom or keyword key, whose text becomes the atom's name, `:erlang.binary_to_atom` when interpolated. */
internal fun Lowering.quotedAtom(element: PsiElement, line: ElixirLine, keys: List<Meta.Key?>): ElixirAst {
    val content = content(line, line.lineBody?.node?.getChildren(null)?.toList().orEmpty().pieces())
        ?: return broken(element)

    return when (content) {
        is Content.Empty -> ElixirAst.Literal.Atom(meta(element), "")
        is Content.Literal -> writtenAtom(element, String(utf8(content.codePoints), Charsets.UTF_8))
        is Content.Interpolated ->
            remoteCall(
                element,
                "erlang",
                "binary_to_atom",
                ElixirAst.Call(
                    meta(element, location(element)),
                    atom(element, "<<>>"),
                    content.parts.map { part(element, it, typed = true) }
                ),
                atom(element, "utf8"),
                callKeys = (keys + location(element)).toTypedArray()
            )
    }
}

/**
 * A part of interpolated text: the text, or the interpolation as `Kernel.to_string`, `typed` as `::`/`binary` in a
 * binary.
 */
private fun Lowering.part(element: PsiElement, part: Part, typed: Boolean): ElixirAst =
    when (part) {
        is Part.Text -> binary(element, part.codePoints)
        is Part.Interpolation -> {
            val interpolation = part.interpolation
            val at = location(interpolation)
            val toString = ElixirAst.Call(
                meta(
                    interpolation,
                    Meta.Key.Entry("from_interpolation", Meta.Value.Atom("true")).takeIf { isAvailable(FROM_INTERPOLATION) },
                    closing(interpolation.node.lastChildNode.startOffset),
                    at
                ),
                dot(interpolation, "Elixir.Kernel", "to_string"),
                listOf(interpolation(interpolation))
            )

            if (typed) {
                ElixirAst.Call(
                    meta(interpolation, at),
                    atom(interpolation, "::"),
                    listOf(toString, ElixirAst.Call(meta(interpolation, at), atom(interpolation, "binary"), null))
                )
            } else {
                toString
            }
        }
    }

// Sigils

private fun Lowering.sigil(sigil: Sigil): ElixirAst {
    val pieces = when (sigil) {
        is SigilHeredocLiteral -> heredocPieces(sigil)
        is SigilLine -> sigil.body.node.getChildren(null).toList().pieces()
        else -> return broken(sigil)
    }
    val content = content(sigil, pieces) ?: return broken(sigil)
    val parts = when (content) {
        is Content.Empty -> listOf(binary(sigil, ""))
        is Content.Literal -> listOf(binary(sigil, content.codePoints))
        is Content.Interpolated -> content.parts.map { part(sigil, it, typed = true) }
    }
    val indentation = sigil.indentation()?.let { Meta.Key.Entry("indentation", Meta.Value.Integer(it.toLong())) }

    return ElixirAst.Call(
        meta(sigil, Meta.Key.Entry("delimiter", Meta.Value.Binary(sigil.sigilDelimiter())), location(sigil)),
        writtenAtom(sigil, "sigil_${sigil.sigilName()}"),
        listOf(
            ElixirAst.Call(meta(sigil, indentation, location(sigil)), atom(sigil, "<<>>"), parts),
            modifiers(sigil.sigilModifiers)
        )
    )
}

private fun Lowering.modifiers(modifiers: ElixirSigilModifiers): ElixirAst =
    ElixirAst.ListNode(
        meta(modifiers),
        modifiers.text.codePoints().toArray().map { ElixirAst.Literal.Integer(meta(modifiers), it.toBigInteger()) }
    )

// Containers

private fun Lowering.list(list: ElixirList): ElixirAst =
    ElixirAst.ListNode(
        meta(list),
        list.children.flatMap { child ->
            if (child is ElixirKeywords) child.keywordPairList.map { keywordPair(it) } else listOf(lower(child))
        }
    )

private fun Lowering.keywordPair(keywordPair: ElixirKeywordPair): ElixirAst =
    ElixirAst.Tuple(meta(keywordPair), listOf(keywordKey(keywordPair.keywordKey), lower(keywordPair.keywordValue)))

private fun Lowering.keywordKey(keywordKey: ElixirKeywordKey): ElixirAst {
    val line = keywordKey.line ?: return writtenAtom(keywordKey, identifier(keywordKey.text))

    return quotedAtom(
        keywordKey,
        line,
        listOf(
            tokenMetadata("delimiter", if (line.isCharList) "'" else "\"").takeIf { isAvailable(DELIMITER_ON_QUOTED_KEYWORD_KEY) },
            Meta.Key.Entry("format", Meta.Value.Atom("keyword"), tokenMetadata = true)
        )
    )
}

private fun Lowering.tuple(tuple: ElixirTuple): ElixirAst {
    val node = tuple.node
    val opening = node.findChildByType(ElixirTypes.OPENING_CURLY) ?: return broken(tuple)
    val closing = node.findChildByType(ElixirTypes.CLOSING_CURLY) ?: return broken(tuple)

    return ElixirAst.Tuple(
        meta(tuple, newlines(opening.textRange.endOffset), closing(closing.startOffset), location(opening)),
        tuple.children.map { lower(it) }
    )
}

private fun Lowering.bitString(bitString: ElixirBitString): ElixirAst {
    val node = bitString.node
    val opening = node.findChildByType(ElixirTypes.OPENING_BIT) ?: return broken(bitString)
    val closing = node.findChildByType(ElixirTypes.CLOSING_BIT) ?: return broken(bitString)

    return ElixirAst.Call(
        meta(bitString, newlines(opening.textRange.endOffset), closing(closing.startOffset), location(opening)),
        atom(bitString, "<<>>"),
        bitString.children.map { lower(it) }
    )
}

private fun Lowering.map(map: ElixirMapOperation): ElixirAst = mapArguments(map.mapArguments, map)

/** `%{...}`, at its `%` from 1.17 unless a struct's, and at its `{` before. */
private fun Lowering.mapArguments(mapArguments: ElixirMapArguments, map: ElixirMapOperation?): ElixirAst {
    val node = mapArguments.node
    val opening = node.findChildByType(ElixirTypes.OPENING_CURLY) ?: return broken(mapArguments)
    val closing = node.findChildByType(ElixirTypes.CLOSING_CURLY) ?: return broken(mapArguments)
    val at = if (map != null && isAvailable(MAP_COLUMN_AT_PERCENT)) location(map) else location(opening)
    val update = mapArguments.mapUpdateArguments
    val arguments =
        if (update != null) {
            listOf(mapUpdate(update))
        } else {
            mapArguments.mapConstructionArguments?.children.orEmpty().flatMap { elements(lower(it)) }
        }

    return ElixirAst.Call(
        meta(map ?: mapArguments, newlines(opening.textRange.endOffset), closing(closing.startOffset), at),
        atom(mapArguments, "%{}"),
        arguments
    )
}

private fun Lowering.struct(struct: ElixirStructOperation): ElixirAst {
    val children = struct.children
    val operator = children.getOrNull(0) as? Operator ?: return broken(struct)
    val name = children.getOrNull(1) ?: return broken(struct)
    val mapArguments = children.getOrNull(2) as? ElixirMapArguments ?: return broken(struct)

    return ElixirAst.Call(
        meta(struct, location(operator.operatorTokenNode())),
        atom(struct, "%"),
        listOf(lower(name), mapArguments(mapArguments, null))
    )
}

/** `%{map | updates}`'s one argument, `{:|, meta, [map, updates]}`. */
private fun Lowering.mapUpdate(mapUpdate: ElixirMapUpdateArguments): ElixirAst {
    val children = mapUpdate.children
    val pipe = children.getOrNull(1) as? Operator ?: return broken(mapUpdate)
    val pipeToken = pipe.operatorTokenNode()
    val newlines = operatorNewlines(children[0].node, pipeToken)

    return ElixirAst.Call(
        meta(mapUpdate, newlines, location(pipeToken)),
        atom(mapUpdate, "|"),
        listOf(
            lower(children[0]),
            ElixirAst.ListNode(meta(mapUpdate), children.drop(2).flatMap { elements(lower(it)) })
        )
    )
}

private fun Lowering.associations(associationsBase: ElixirAssociationsBase): ElixirAst =
    ElixirAst.ListNode(meta(associationsBase), associationsBase.children.map { lower(it) })

/** `key => value`, whose key carries where its `=>` is from 1.18. */
private fun Lowering.association(association: ElixirContainerAssociationOperation): ElixirAst {
    val children = association.children
    if (children.size != 2) return broken(association)
    val operator = association.node.findChildByType(ElixirTypes.ASSOCIATION_OPERATOR) ?: return broken(association)
    val key = lower(children[0]).let { key ->
        if (isAvailable(ASSOC_ON_MAP_KEY)) {
            decorate(key, Meta.Key.Entry("assoc", Meta.Value.Keywords(listOf(location(operator))), tokenMetadata = true))
        } else {
            key
        }
    }

    return ElixirAst.Tuple(meta(association), listOf(key, lower(children[1])))
}

/** A list's elements, as a container's arguments splice them; anything else is one argument. */
private fun elements(node: ElixirAst): List<ElixirAst> = (node as? ElixirAst.ListNode)?.elements ?: listOf(node)

// Building nodes

private fun Lowering.atom(element: PsiElement, name: String): ElixirAst = ElixirAst.Literal.Atom(meta(element), name)

/** The atom [element] writes as [name], broken when [name] is longer than an atom may be, as Elixir refuses it. */
internal fun Lowering.writtenAtom(element: PsiElement, name: String): ElixirAst =
    if (name.codePointCount(0, name.length) <= OtpExternal.maxAtomLength) {
        ElixirAst.Literal.Atom(meta(element), name)
    } else {
        broken(element)
    }

/** Code points Elixir refuses in an escape, as UTF-8 cannot encode them. */
private val SURROGATES = Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code

private fun Lowering.dot(element: PsiElement, module: String, function: String): ElixirAst =
    ElixirAst.Call(
        meta(element, location(element)),
        atom(element, "."),
        listOf(atom(element, module), atom(element, function))
    )

/** `module.function(arguments)` with [element]'s location on both the call and its `.`, and [callKeys] on the call. */
private fun Lowering.remoteCall(
    element: PsiElement,
    module: String,
    function: String,
    vararg arguments: ElixirAst,
    callKeys: Array<Meta.Key?> = arrayOf(location(element)),
): ElixirAst = ElixirAst.Call(meta(element, *callKeys), dot(element, module, function), arguments.toList())
