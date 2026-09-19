package org.elixir_lang.annotator

import com.intellij.lang.ASTNode
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.ElixirAccessExpression
import org.elixir_lang.psi.ElixirAnonymousFunction
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirAtomKeyword
import org.elixir_lang.psi.ElixirCharToken
import org.elixir_lang.psi.ElixirDotInfixOperator
import org.elixir_lang.psi.ElixirEscapedCharacter
import org.elixir_lang.psi.ElixirHeredoc
import org.elixir_lang.psi.ElixirInterpolatedSigilHeredoc
import org.elixir_lang.psi.ElixirInterpolatedSigilLine
import org.elixir_lang.psi.ElixirInterpolation
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.ElixirLiteralSigilHeredoc
import org.elixir_lang.psi.ElixirLiteralSigilLine
import org.elixir_lang.psi.ElixirMapOperation
import org.elixir_lang.psi.ElixirMatchedMultiplicationOperation
import org.elixir_lang.psi.ElixirMatchedQualifiedAlias
import org.elixir_lang.psi.ElixirMultiplicationInfixOperator
import org.elixir_lang.psi.ElixirParentheticalStab
import org.elixir_lang.psi.ElixirQuoteHexadecimalEscapeSequence
import org.elixir_lang.psi.ElixirRelativeIdentifier
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.ElixirUnmatchedMultiplicationOperation
import org.elixir_lang.psi.ElixirUnmatchedQualifiedAlias
import org.elixir_lang.psi.Quote
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.UnqualifiedNoArgumentsCall
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPE_ERRORS_NAME_THE_INVALID_CHARACTER
import org.elixir_lang.language_level.ElixirLanguageFeature.HEREDOC_OPENING_ERROR_SAYS_OPENING
import org.elixir_lang.language_level.ElixirLanguageFeature.HEREDOC_TERMINATOR_AFTER_CONTENT_IS_CONTENT
import org.elixir_lang.language_level.ElixirLanguageFeature.HEXADECIMAL_ESCAPE_NEEDS_TWO_DIGITS
import org.elixir_lang.language_level.ElixirLanguageFeature.UNESCAPED_QUOTED_REMOTE_CALL_NAME
import org.elixir_lang.language_level.ElixirLanguageLevelResolver

/**
 * Reports, as errors, constructs that Elixir's parser or string unescaping rejects and the plugin's parser accepts.
 */
internal class InvalidConstruct : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val problem = when (element) {
            is ElixirMatchedQualifiedAlias, is ElixirUnmatchedQualifiedAlias -> atomFollowedByAlias(element)
            is ElixirAnonymousFunction -> anonymousFunctionWithoutClause(element)
            is ElixirMapOperation -> spaceBeforeBrace(element)
            is ElixirQuoteHexadecimalEscapeSequence -> invalidCodePoint(element)
            is ElixirEscapedCharacter -> invalidEscape(element)
            is ElixirHeredoc, is ElixirInterpolatedSigilHeredoc, is ElixirLiteralSigilHeredoc -> unterminatedHeredoc(element)
            is ElixirInterpolation -> unclosedInterpolation(element)
            is ElixirLine -> if (element.parent is ElixirAtom) null else cutOffQuote(element)
            is ElixirInterpolatedSigilLine, is ElixirLiteralSigilLine -> cutOffQuote(element)
            is ElixirAtom -> divisionAtom(element) ?: cutOffQuote(element)
            is ElixirMatchedMultiplicationOperation, is ElixirUnmatchedMultiplicationOperation -> operatorReference(element)
            else -> null
        } ?: return

        if (Injection.of(element) == Injection.UNCOMPILED) return

        holder.newAnnotation(HighlightSeverity.ERROR, problem.second).range(problem.first).create()
    }

    private fun atomFollowedByAlias(qualifiedAlias: PsiElement): Pair<TextRange, String>? {
        val qualifier = qualifiedAlias.firstChild ?: return null
        if (!isAtom(qualifier)) return null

        val dot = qualifiedAlias.children.firstOrNull { it is ElixirDotInfixOperator } ?: return null

        return dot.textRange to
            "atom cannot be followed by an alias. If the '.' was meant to be part of the atom's name, the atom name " +
            "must be quoted. Syntax error before: '.'"
    }

    /** An interpolated atom is a call to `:erlang.binary_to_atom/2`, which may be followed by an alias. */
    private fun isAtom(element: PsiElement): Boolean =
        when (element) {
            is ElixirAccessExpression -> element.children.singleOrNull()?.let(::isAtom) == true
            is ElixirAtom -> PsiTreeUtil.findChildOfType(element, ElixirInterpolation::class.java) == null
            is ElixirAtomKeyword -> true
            is ElixirParentheticalStab ->
                element.stab?.let { stab -> stab.stabOperationList.isEmpty() && stab.stabBody?.children?.singleOrNull()?.let(::isAtom) == true } == true
            else -> false
        }

    /** Elixir's tokenizer has no `//` atom: `:// 1` is `:/ / 1`, and before a closing token or the end `://` is an error. */
    private fun divisionAtom(atom: ElixirAtom): Pair<TextRange, String>? {
        val fragment = atom.node.findChildByType(ElixirTypes.ATOM_FRAGMENT)?.takeIf { it.text == "//" } ?: return null
        val next = generateSequence(PsiTreeUtil.nextVisibleLeaf(atom)) { PsiTreeUtil.nextVisibleLeaf(it) }
            .firstOrNull { it !is PsiWhiteSpace && it !is PsiComment }
        if (next == null || isFinalBackslash(next)) {
            return fragment.textRange to if (endsWithBackslash(atom)) INVALID_ESCAPE_AT_END else syntaxErrorBefore("")
        }

        val token = when {
            next.parent is ElixirInterpolation -> ""
            next.text in CLOSING_TOKENS -> "'${next.text}'"
            else -> return null
        }

        return fragment.textRange to syntaxErrorBefore(token)
    }

    /**
     * Elixir rejects `=>` before `/` on every release, and `//` when a newline or line continuation comes before the `/`.
     * Elsewhere, `=>` in a map is the association and `//` differs by release.
     */
    private fun operatorReference(operation: PsiElement): Pair<TextRange, String>? {
        val operator = operation.children.firstOrNull { it is ElixirMultiplicationInfixOperator && it.text == "/" } ?: return null
        val operand = operation.firstChild as? UnqualifiedNoArgumentsCall<*> ?: return null
        val previous = generateSequence(PsiTreeUtil.prevLeaf(operand)) { PsiTreeUtil.prevLeaf(it) }
            .firstOrNull { it !is PsiWhiteSpace && it !is PsiComment && it.text.isNotBlank() }
            ?.text

        return when (operand.text) {
            "=>" -> if (previous in setOf(null, "&", "(", "=")) operand.textRange to syntaxErrorBefore("'=>'") else null
            "//" -> {
                val between = operation.containingFile.viewProvider.contents.subSequence(operand.textRange.endOffset, operator.textRange.startOffset)

                if ('\n' in between && (previous == null || previous == "&")) {
                    operator.textRange to syntaxErrorBefore("'/'")
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun anonymousFunctionWithoutClause(anonymousFunction: ElixirAnonymousFunction): Pair<TextRange, String>? {
        if (anonymousFunction.stab.stabOperationList.isNotEmpty()) return null

        val fn = anonymousFunction.node.findChildByType(ElixirTypes.FN) ?: return null

        return fn.textRange to "expected anonymous functions to be defined with -> inside: 'fn'"
    }

    /** Before 1.15 Elixir reported only a syntax error whose position depends on what follows; the later message is used. */
    private fun spaceBeforeBrace(mapOperation: ElixirMapOperation): Pair<TextRange, String>? {
        val prefix = mapOperation.mapPrefixOperator.textRange
        val arguments = mapOperation.mapArguments.textRange
        val between = mapOperation.containingFile.viewProvider.contents
            .subSequence(prefix.endOffset, arguments.startOffset)
            .toString()
            .replace("\\\r\n", " ")
            .replace("\\\n", " ")

        if (between.isEmpty() || between.any { it != ' ' && it != '\t' }) return null

        return TextRange(prefix.startOffset, arguments.startOffset + 1) to "unexpected space between % and {"
    }

    /**
     * Elixir unescapes a string, charlist, quoted atom or key as it parses, and `sigil_s`, `sigil_c` and `sigil_w` when the
     * code is compiled; other sigils receive an escape as written. A quoted call name is unescaped only from 1.18, which
     * [VersionedSyntax] reports. On 1.11 the hexadecimal message ends with the quote's opening delimiter, Elixir's token,
     * and a sigil's message never has the tokenizer's "Syntax error after".
     */
    private fun invalidEscape(escaped: ElixirEscapedCharacter): Pair<TextRange, String>? {
        val letter = escaped.lastChild?.text?.takeIf { it == "x" || it == "u" } ?: return null

        val holder = PsiTreeUtil.getParentOfType(escaped, Sigil::class.java, Quote::class.java, ElixirCharToken::class.java)
        val delimiter = when (holder) {
            is Sigil -> if (holder.sigilName() in UNESCAPING_SIGILS) "" else return null
            is Quote -> when (holder.parent) {
                is ElixirRelativeIdentifier -> return null
                is ElixirAtom -> ":" + holder.firstChild.text
                else -> holder.firstChild.text
            }
            else -> return null
        }

        val hexadecimal = letter == "x"

        return escaped.textRange to when {
            !ElixirLanguageLevelResolver.isAvailable(ESCAPE_ERRORS_NAME_THE_INVALID_CHARACTER, escaped) ->
                if (hexadecimal) "missing hex sequence after \\x, expected \\xHH$delimiter"
                else "invalid Unicode sequence after \\u, expected \\uHHHH or \\u{H*}"
            holder is Sigil -> if (hexadecimal) INVALID_HEX_ESCAPE_WHEN_COMPILED else INVALID_UNICODE_ESCAPE_WHEN_COMPILED
            hexadecimal -> INVALID_HEX_ESCAPE
            else -> INVALID_UNICODE_ESCAPE
        }
    }

    /**
     * Elixir rejects content after a heredoc's opening first, then a missing terminator. Before 1.12 it finds a heredoc's
     * terminator line by line before it reads interpolations, so a terminator after content is rejected where it stands,
     * which [VersionedSyntax] reports, and an enclosing heredoc can cut this one off. From 1.12 an interpolation without its
     * `}` fails before the heredoc's terminator is looked for, as does a heredoc the grammar stopped early.
     */
    private fun unterminatedHeredoc(heredoc: PsiElement): Pair<TextRange, String>? {
        val promoter = heredoc.node.findChildByType(ElixirTypes.HEREDOC_PROMOTER) ?: return null
        val languageLevel = ElixirLanguageLevelResolver.languageLevelFor(heredoc)
        val cutOff = if (HEREDOC_TERMINATOR_AFTER_CONTENT_IS_CONTENT.isSufficient(languageLevel)) {
            CutOff.NONE
        } else {
            cutOff(heredoc)
        }

        if (cutOff == CutOff.SUPPRESSED) return null

        if (hasContentAfterOpening(heredoc)) {
            val message = if (!HEREDOC_OPENING_ERROR_SAYS_OPENING.isSufficient(languageLevel)) {
                "heredoc allows only zero or more whitespace characters followed by a new line after "
            } else {
                "heredoc allows only whitespace characters followed by a new line after opening "
            }

            return promoter.textRange to message + promoter.text
        }

        if (cutOff == CutOff.NONE) {
            if (heredoc.node.findChildByType(ElixirTypes.HEREDOC_TERMINATOR) != null) return null

            if (!HEREDOC_TERMINATOR_AFTER_CONTENT_IS_CONTENT.isSufficient(languageLevel)) {
                val scan = scanHeredocLines(promoter)

                if (scan.terminatorAt != null || scan.misplaced != null) return null
            } else if (heredoc.textRange.endOffset < heredoc.containingFile.textLength ||
                PsiTreeUtil.findChildrenOfType(heredoc, ElixirInterpolation::class.java).any(::isUnclosed)
            ) {
                return null
            }
        }

        return TextRange(promoter.startOffset, heredoc.textRange.endOffset) to
            "missing terminator: ${promoter.text} (for heredoc starting at line ${lineAt(heredoc, promoter.startOffset)})"
    }

    /**
     * Elixir reads an interpolation's content before it looks for the `}`, so only the innermost one without it fails, and only
     * when nothing inside fails first.
     */
    private fun unclosedInterpolation(interpolation: ElixirInterpolation): Pair<TextRange, String>? {
        if (!isUnclosed(interpolation)) return null
        if (PsiTreeUtil.findChildrenOfType(interpolation, ElixirInterpolation::class.java).any(::isUnclosed)) return null
        if (hasInnerError(interpolation)) return null
        if (
            !ElixirLanguageLevelResolver.isAvailable(HEREDOC_TERMINATOR_AFTER_CONTENT_IS_CONTENT, interpolation) &&
            cutOff(interpolation) == CutOff.SUPPRESSED
        ) {
            return null
        }

        val owner = PsiTreeUtil.getParentOfType(
            interpolation,
            ElixirLine::class.java,
            ElixirInterpolatedSigilLine::class.java,
            ElixirHeredoc::class.java,
            ElixirInterpolatedSigilHeredoc::class.java
        ) ?: return null
        val (name, start) = when {
            isHeredoc(owner) -> "heredoc" to owner.node.findChildByType(ElixirTypes.HEREDOC_PROMOTER)!!.startOffset
            owner.parent is ElixirAtom -> "atom" to owner.parent.textRange.startOffset
            owner is Sigil -> "sigil ~${owner.sigilName()}${owner.node.findChildByType(ElixirTypes.LINE_PROMOTER)?.text.orEmpty()}" to
                owner.textRange.startOffset
            else -> "string" to owner.textRange.startOffset
        }

        return interpolation.node.firstChildNode.textRange to
            "missing interpolation terminator: \"}\" (for $name starting at line ${lineAt(interpolation, start)})"
    }

    /** A heredoc, or an escape in a string, charlist, atom or heredoc, that fails while Elixir reads [interpolation]. */
    private fun hasInnerError(interpolation: ElixirInterpolation): Boolean =
        PsiTreeUtil.findChildrenOfAnyType(
            interpolation,
            ElixirHeredoc::class.java,
            ElixirInterpolatedSigilHeredoc::class.java,
            ElixirLiteralSigilHeredoc::class.java
        ).any {
            unterminatedHeredoc(it) != null ||
                !ElixirLanguageLevelResolver.isAvailable(HEREDOC_TERMINATOR_AFTER_CONTENT_IS_CONTENT, it) &&
                misplacedHeredocTerminator(it) != null
        } ||
            // A sigil's escape fails only when the sigil is compiled.
            PsiTreeUtil.findChildrenOfType(interpolation, ElixirEscapedCharacter::class.java).any { escape ->
                PsiTreeUtil.getParentOfType(escape, *QUOTE_TYPES) !is Sigil && invalidEscape(escape) != null
            }

    /** Before 1.12, a string, charlist, sigil or quoted atom that an enclosing heredoc's terminator line cuts off first. */
    private fun cutOffQuote(quote: PsiElement): Pair<TextRange, String>? {
        if (
            ElixirLanguageLevelResolver.isAvailable(HEREDOC_TERMINATOR_AFTER_CONTENT_IS_CONTENT, quote) ||
            cutOff(quote) != CutOff.FIRST
        ) {
            return null
        }

        val line = if (quote is ElixirAtom) quote.children.firstOrNull { it is ElixirLine } ?: return null else quote
        val promoter = line.node.findChildByType(ElixirTypes.LINE_PROMOTER) ?: return null
        val owner = when (quote) {
            is ElixirAtom -> "atom"
            is Sigil -> "sigil ~${quote.sigilName()}${promoter.text}"
            else -> "string"
        }
        val terminator = CLOSING_DELIMITERS[promoter.text] ?: promoter.text

        return quote.textRange to
            "missing terminator: $terminator (for $owner starting at line ${lineAt(quote, quote.textRange.startOffset)})"
    }

    private fun invalidCodePoint(escape: ElixirQuoteHexadecimalEscapeSequence): Pair<TextRange, String>? {
        // Elixir unescapes a quoted remote call name only from 1.18. In `?\u{...}` it reads `?\u` and then reports a syntax
        // error before `{`.
        when (PsiTreeUtil.getParentOfType(escape, ElixirRelativeIdentifier::class.java, ElixirCharToken::class.java)) {
            null -> Unit
            is ElixirRelativeIdentifier -> if (!ElixirLanguageLevelResolver.isAvailable(UNESCAPED_QUOTED_REMOTE_CALL_NAME, escape)) return null
            else -> return null
        }

        val digits = PsiTreeUtil.collectElements(escape) { it.node.elementType == ElixirTypes.VALID_HEXADECIMAL_DIGITS }
            .singleOrNull()
            ?.text
            ?: return null
        val codePoint = digits.toLongOrNull(16) ?: return null

        if (codePoint in 0xD800..0xDFFF || codePoint > 0x10FFFF) {
            val languageLevel = ElixirLanguageLevelResolver.languageLevelFor(escape)
            val hexadecimal = escape.hexadecimalEscapePrefix.text.endsWith("x")

            return escape.textRange to when {
                !ESCAPE_ERRORS_NAME_THE_INVALID_CHARACTER.isSufficient(languageLevel) ->
                    "invalid or reserved Unicode code point $codePoint"
                hexadecimal && HEXADECIMAL_ESCAPE_NEEDS_TWO_DIGITS.isSufficient(languageLevel) -> INVALID_HEX_ESCAPE
                else -> "invalid or reserved Unicode code point \\u{$digits}. Syntax error after: \\u"
            }
        }

        return null
    }
}

private val UNESCAPING_SIGILS = setOf("s", "c", "w")

private val CLOSING_TOKENS = setOf(",", ")", ">>", "]", "}")

private val CLOSING_DELIMITERS = mapOf("(" to ")", "[" to "]", "{" to "}", "<" to ">")

private val QUOTE_TYPES = arrayOf(
    ElixirLine::class.java,
    ElixirHeredoc::class.java,
    ElixirInterpolatedSigilLine::class.java,
    ElixirInterpolatedSigilHeredoc::class.java,
    ElixirLiteralSigilLine::class.java,
    ElixirLiteralSigilHeredoc::class.java
)

internal const val INVALID_HEX_ESCAPE_WHEN_COMPILED = "invalid hex escape character, expected \\xHH where H is a hexadecimal digit"
internal const val INVALID_HEX_ESCAPE = "$INVALID_HEX_ESCAPE_WHEN_COMPILED. Syntax error after: \\x"

internal const val INVALID_UNICODE_ESCAPE_WHEN_COMPILED =
    "invalid Unicode escape character, expected \\uHHHH or \\u{H*} where H is a hexadecimal digit"
internal const val INVALID_UNICODE_ESCAPE = "$INVALID_UNICODE_ESCAPE_WHEN_COMPILED. Syntax error after: \\u"

internal const val INVALID_ESCAPE_AT_END = "invalid escape \\ at end of file"

/** Elixir's tokenizer stops first at a backslash, with or without a newline, that ends the file. */
internal fun endsWithBackslash(element: PsiElement): Boolean {
    val file = element.containingFile
    val text = file.viewProvider.contents
    val backslash = when {
        text.endsWith("\\\r\n") -> text.length - 3
        text.endsWith("\\\n") -> text.length - 2
        text.endsWith("\\") -> text.length - 1
        else -> return false
    }
    val leaf = file.findElementAt(backslash)

    return leaf is PsiWhiteSpace || isFinalBackslash(leaf)
}

/** Whether something other than spaces or tabs follows the heredoc's opening on its line. */
internal fun hasContentAfterOpening(heredoc: PsiElement): Boolean {
    val promoter = heredoc.node.findChildByType(ElixirTypes.HEREDOC_PROMOTER) ?: return false
    val next = generateSequence(promoter.treeNext) { it.treeNext }.firstOrNull { it.elementType != TokenType.WHITE_SPACE }

    return next?.elementType != ElixirTypes.EOL
}

private enum class CutOff { NONE, SUPPRESSED, FIRST }

/**
 * Before 1.12 Elixir ends a heredoc at its terminator line, after checking its opening line, and only then reads its
 * interpolations, so the first quote or interpolation there that runs past that line fails and nothing after it is read.
 * Enclosing heredocs are read outermost first. [CutOff.FIRST] means [element] is what fails; [CutOff.SUPPRESSED], that
 * something enclosing it fails first.
 */
private fun cutOff(element: PsiElement): CutOff {
    val heredocs = generateSequence(element.parent) { if (it is PsiFile) null else it.parent }.filter(::isHeredoc).toList()

    for (heredoc in heredocs.asReversed()) {
        ProgressManager.checkCanceled()

        if (hasContentAfterOpening(heredoc)) return CutOff.SUPPRESSED

        val scan = scanHeredocLines(heredoc.node.findChildByType(ElixirTypes.HEREDOC_PROMOTER) ?: continue)
        val terminatorAt = scan.terminatorAt

        if (terminatorAt == null || scan.misplaced != null || terminatorAt <= element.textRange.startOffset) {
            return CutOff.SUPPRESSED
        }

        firstCutOff(heredoc, terminatorAt)?.let { return if (it == element) CutOff.FIRST else CutOff.SUPPRESSED }
    }

    return CutOff.NONE
}

/**
 * In reading order, the first heredoc, quote or interpolation under [parent] that runs past [terminatorAt]. A heredoc fails
 * as a whole; a quote or interpolation reads its content first.
 */
private fun firstCutOff(parent: PsiElement, terminatorAt: Int): PsiElement? {
    for (child in parent.children) {
        ProgressManager.checkCanceled()

        if (child.textRange.startOffset >= terminatorAt) return null

        if (isHeredoc(child)) {
            if (closingAt(child, ElixirTypes.HEREDOC_TERMINATOR) >= terminatorAt) return child
            continue
        }

        // A quoted atom's content is its line, which must not stand in for the atom.
        val content = if (child is ElixirAtom) child.children.firstOrNull { it is ElixirLine } else child
        val closing = when {
            child is ElixirInterpolation -> closingAt(child, ElixirTypes.INTERPOLATION_END)
            content is ElixirLine || content is Sigil -> closingAt(content, ElixirTypes.LINE_TERMINATOR)
            else -> null
        }

        if (content != null && closing != null && closing >= terminatorAt) return firstCutOff(content, terminatorAt) ?: child

        firstCutOff(child, terminatorAt)?.let { return it }
    }

    return null
}

private fun closingAt(element: PsiElement, type: IElementType): Int =
    element.node.findChildByType(type)?.startOffset ?: Int.MAX_VALUE

private fun isHeredoc(element: PsiElement): Boolean =
    element is ElixirHeredoc || element is ElixirInterpolatedSigilHeredoc || element is ElixirLiteralSigilHeredoc

private fun isUnclosed(interpolation: ElixirInterpolation): Boolean =
    interpolation.node.findChildByType(ElixirTypes.INTERPOLATION_END) == null

private fun lineAt(element: PsiElement, offset: Int): Int =
    StringUtil.offsetToLineNumber(element.containingFile.viewProvider.contents, offset) + 1

/** Where the terminator line's terminator starts, or the first misplaced terminator; neither when the file ends first. */
private class HeredocScan(val terminatorAt: Int?, val misplaced: TextRange?)

/**
 * Elixir's reading before 1.12: line by line after the opening, a backslash taking a backslash or quote after it, until a line
 * starting with the terminator; a terminator anywhere else on a line is misplaced.
 */
private fun scanHeredocLines(promoter: ASTNode): HeredocScan {
    val terminator = promoter.text
    val text = promoter.psi.containingFile.viewProvider.contents
    var index = StringUtil.indexOf(text, '\n', promoter.textRange.endOffset)

    while (index >= 0 && index < text.length) {
        ProgressManager.checkCanceled()
        index++

        while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
        if (StringUtil.startsWith(text, index, terminator)) return HeredocScan(index, null)

        while (index < text.length && text[index] != '\n') {
            when {
                text[index] == '\\' && index + 1 < text.length && (text[index + 1] == '\\' || text[index + 1] == terminator[0]) ->
                    index += 2
                StringUtil.startsWith(text, index, terminator) -> return HeredocScan(null, TextRange.from(index, terminator.length))
                else -> index++
            }
        }
    }

    return HeredocScan(null, null)
}

/** The first terminator after content in the heredoc, as Elixir reads it before 1.12. */
internal fun misplacedHeredocTerminator(heredoc: PsiElement): TextRange? =
    heredoc.node.findChildByType(ElixirTypes.HEREDOC_PROMOTER)
        ?.let { scanHeredocLines(it).misplaced }
        ?.takeIf { heredoc.textRange.contains(it) }

internal fun isFinalBackslash(leaf: PsiElement?): Boolean =
    leaf != null && leaf.text == "\\" && leaf.textRange.endOffset == leaf.containingFile.textLength
