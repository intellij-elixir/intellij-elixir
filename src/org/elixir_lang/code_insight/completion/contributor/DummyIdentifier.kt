package org.elixir_lang.code_insight.completion.contributor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.util.text.CharArrayUtil
import org.elixir_lang.code_insight.completion.CaretContext
import org.elixir_lang.code_insight.completion.atomAt
import org.elixir_lang.code_insight.completion.atomNameAt
import org.elixir_lang.code_insight.completion.contentAt
import org.elixir_lang.model.psi.config.Set
import org.elixir_lang.parser.isDecimalDigit
import org.elixir_lang.psi.ElixirBracketArguments
import org.elixir_lang.psi.ElixirKeywordKey
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.ElixirMapArguments
import org.elixir_lang.psi.ElixirMapOperation
import org.elixir_lang.psi.ElixirNoParenthesesArguments
import org.elixir_lang.psi.ElixirParenthesesArguments
import org.elixir_lang.psi.ElixirStructOperation
import org.elixir_lang.psi.ElixirTuple
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.Quote
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.call.name.Function

/**
 * Sets the dummy identifier from the caret's [CaretContext] and the tokens around the caret; the only Elixir
 * contributor that sets it.
 */
internal class DummyIdentifier : CompletionContributor(), DumbAware {
    override fun beforeCompletion(context: CompletionInitializationContext) {
        context.dummyIdentifier = dummy(context.file, context.startOffset)
        tabEnd(context.file, context.startOffset)?.let {
            context.replacementOffset = maxOf(it, context.selectionEndOffset)
        }
    }

    /** Where Elixir reads no context and [dummyAtNone] gives no dummy, nothing is offered, even where a reference covers the caret. */
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        val caret = parameters.offset

        if (CaretContext.at(file, caret) == CaretContext.None && dummyAtNone(CaretContext.leafBefore(file, caret), caret) == null) {
            result.stopHere()
        }
    }
}

/** Capitalised, so the copy reads it as an alias. */
private const val ALIAS = CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED

/** [ALIAS] lowercased, so the copy reads it as a name. */
private val NAME = ALIAS.replaceFirstChar(Char::lowercaseChar)

/** No dummy, where any name would change what the code around the caret parses as. */
private const val NOTHING = ""

/** The calls after which IEx offers only module names. */
private val ALIAS_ONLY_CALLS = setOf(Function.ALIAS, Function.IMPORT, Function.REQUIRE)

private fun dummy(file: PsiFile, caret: Int): String {
    val before = CaretContext.leafBefore(file, caret)
    val context = CaretContext.at(file, caret)

    val dummy = when (context) {
        is CaretContext.BlockKeywordOrBinaryOperator,
        is CaretContext.Sigil,
        -> NOTHING

        CaretContext.None -> dummyAtNone(before, caret) ?: NOTHING

        is CaretContext.Struct -> ALIAS

        // A name alone in a map's braces does not parse; an alias does.
        CaretContext.Expr -> if (aloneInMapBraces(file, caret) || inStructBraces(before)) ALIAS else NAME

        is CaretContext.Dot -> when {
            // An operator after the dot is already the member's name, as in `&Kernel.+/2`.
            context.hint.isEmpty() && file.viewProvider.contents.getOrNull(caret)?.let { it in CaretContext.OPERATOR_CHARACTERS } == true -> NOTHING
            // The alias's reference offers its nested modules beside its functions.
            context.receiver is CaretContext.Alias || context.receiver is CaretContext.InsideAlias -> ALIAS
            else -> NAME
        }

        // The call's reference reads the whole name as the prefix.
        is CaretContext.LocalOrVar -> if (CaretContext.whole(context.hint)) NOTHING else NAME

        is CaretContext.UnquotedAtom -> if (context.hint.isEmpty()) dummyAfterColon(file, before, caret) else NAME

        // Elixir offers only module names after these calls.
        is CaretContext.LocalCall -> if (context.hint in ALIAS_ONLY_CALLS) ALIAS else NAME

        is CaretContext.Alias,
        is CaretContext.InsideAlias,
        is CaretContext.AnonymousCall,
        is CaretContext.CaptureArg,
        is CaretContext.DotArity,
        is CaretContext.DotCall,
        is CaretContext.LocalArity,
        is CaretContext.ModuleAttribute,
        is CaretContext.Operator,
        is CaretContext.OperatorArity,
        is CaretContext.OperatorCall,
        -> NAME
    }

    return if (dummy != NOTHING && spaced(file, context, caret)) "$dummy " else dummy
}

/**
 * The dummy at [caret], just after [before], where Elixir has no context: a name after a dot and in a sigil's
 * content, where a reader still answers on one; none in a quote's content, where what is offered stays as it was;
 * `null` elsewhere, where nothing is offered.
 */
private fun dummyAtNone(before: PsiElement?, caret: Int): String? = when {
    before.elementType == ElixirTypes.DOT_OPERATOR || inContent(before, caret, Sigil::class.java) -> NAME
    inContent(before, caret, Quote::class.java) -> NOTHING
    else -> null
}

/**
 * Whether the dummy is followed by a space, so the copy keeps it apart from the text after the caret where
 * Elixir reads that text apart: after a caret that starts a token, and before a keyword key's colon, after the
 * caret or after the rest of the name it is in, where [partsKey] says.
 */
private fun spaced(file: PsiFile, context: CaretContext, caret: Int): Boolean {
    if (file.findElementAt(caret).let { it == null || it is PsiWhiteSpace || it.elementType == ElixirTypes.EOL }) return false
    // The rest of the name the caret is in, or else the name it starts.
    val keyEnd = (if (caret > 0) surroundEnd(file, caret - 1) else null)?.takeIf { it > caret } ?: surroundEnd(file, caret)
    val key = keywordKeyAt(file, keyEnd)

    return if (key != null) partsKey(key) else startsToken(context)
}

/** Whether the caret starts a token, where the value's own text ends before it. */
private fun startsToken(context: CaretContext): Boolean = when (context) {
    is CaretContext.AnonymousCall,
    is CaretContext.DotCall,
    CaretContext.Expr,
    is CaretContext.LocalCall,
    CaretContext.None,
    is CaretContext.Operator,
    is CaretContext.OperatorCall,
    -> true

    is CaretContext.Dot -> context.hint.isEmpty()

    is CaretContext.Alias,
    is CaretContext.InsideAlias,
    is CaretContext.BlockKeywordOrBinaryOperator,
    is CaretContext.CaptureArg,
    is CaretContext.DotArity,
    is CaretContext.LocalArity,
    is CaretContext.LocalOrVar,
    is CaretContext.ModuleAttribute,
    is CaretContext.OperatorArity,
    is CaretContext.Sigil,
    is CaretContext.Struct,
    is CaretContext.UnquotedAtom,
    -> false
}

/** The keyword key whose colon the lexer reads at [at]; `null` where it reads none. */
private fun keywordKeyAt(file: PsiFile, at: Int): ElixirKeywordKey? =
    file.findElementAt(at)?.takeIf { it.elementType == ElixirTypes.KEYWORD_PAIR_COLON }?.prevSibling as? ElixirKeywordKey

/**
 * Whether the dummy parts [key] from its colon. Elixir reads the key's name
 * as a name, and the spaced dummy reads it so as a call taking the pairs, which parses only as the first pair
 * of a call's or a list's arguments: not after another pair, nor in a map's braces. A key `config` reads keeps
 * the glue too, since its reference reads the key.
 */
private fun partsKey(key: ElixirKeywordKey): Boolean {
    val pair = key.parent

    return PsiTreeUtil.getPrevSiblingOfType(pair, pair.javaClass) == null &&
        PsiTreeUtil.getParentOfType(pair, ElixirMapArguments::class.java, true, *ARGUMENTS) == null &&
        Set.slotAt(key) == null
}

/**
 * Whether the nearest bracket open before the caret, [before] or a leaf before it, is a struct's `{`. A line is
 * read past only where it ends in a `,` or a bracket's open, as a struct's lines do; a string or sigil before
 * [before] is read past whole, its lines its own.
 */
private fun inStructBraces(before: PsiElement?): Boolean {
    if (before == null) return false
    var depth = 0
    var leaf: PsiElement? = before

    while (leaf != null) {
        val literal = PsiTreeUtil.getParentOfType(leaf, Quote::class.java, Sigil::class.java)

        if (literal != null && !PsiTreeUtil.isAncestor(literal, before, false)) {
            leaf = PsiTreeUtil.prevLeaf(literal)
            continue
        }

        val type = leaf.elementType
        if (type in CLOSING) {
            depth++
        } else if (type in OPENING) {
            if (depth == 0) return type == ElixirTypes.OPENING_CURLY && leaf.parent?.parent is ElixirStructOperation
            depth--
        } else if (depth == 0 && leaf.textContains('\n') && !continuesLine(leaf)) {
            return false
        }

        leaf = PsiTreeUtil.prevLeaf(leaf)
    }

    return false
}

/** Whether the line before [newline] ends in a `,` or a bracket's open, past what the lexer reads as white space and comments. */
private fun continuesLine(newline: PsiElement): Boolean =
    PsiTreeUtil.prevCodeLeaf(newline).elementType.let { it == ElixirTypes.COMMA || it in OPENING }

/**
 * The dummy at [caret], just after a colon, [before]: a name where the colon starts an atom with nothing after it, so the
 * copy reads an atom; none where the atom goes on past the caret, as in `:+` or `:"quoted"`, or the colon is a keyword
 * key's, `::` or a character's, where a name would change what the copy reads.
 */
private fun dummyAfterColon(file: PsiFile, before: PsiElement?, caret: Int): String {
    if (before.elementType != ElixirTypes.COLON) return NOTHING

    val atom = atomAt(file.findElementAt(caret))

    return if (atom == null || atom.textRange.startOffset >= caret) NAME else NOTHING
}

/** Whether the caret is all a map's braces hold, but for what the lexer reads as white space and comments. */
private fun aloneInMapBraces(file: PsiFile, caret: Int): Boolean {
    val opening = file.findElementAt(caret)?.let(PsiTreeUtil::prevCodeLeaf)
        ?.takeIf { it.elementType == ElixirTypes.OPENING_CURLY } ?: return false

    return opening.parent?.parent is ElixirMapOperation &&
        PsiTreeUtil.nextCodeLeaf(opening).elementType == ElixirTypes.CLOSING_CURLY
}

/** Whether [caret] is in a quote's or a sigil's content and not in an interpolation's code. */
internal fun inLiteralContent(file: PsiFile, caret: Int): Boolean =
    inContent(CaretContext.leafBefore(file, caret), caret, Quote::class.java, Sigil::class.java)

/** Whether the caret, just after [before], is in the nearest of [kinds]'s content, not an interpolation's code. */
private fun inContent(before: PsiElement?, caret: Int, vararg kinds: Class<out PsiElement>): Boolean =
    PsiTreeUtil.getParentOfType(before, *kinds)?.let { contentAt(it, caret) } != null

/**
 * Where Tab's replacement ends when the caret is inside a name or operator: in an atom's name, where [atomNameAt] says
 * it ends; else the end of the name or operator read from the character before the caret, up to the dot after it. Else
 * `null`, so the platform's end, the reference's after the caret, stands.
 */
private fun tabEnd(file: PsiFile, caret: Int): Int? {
    if (caret == 0) return null
    (atomNameEnd(file.findElementAt(caret - 1), caret) ?: atomNameEnd(file.findElementAt(caret), caret))?.let { return it }

    val text = file.viewProvider.contents
    val start = surroundStart(text, caret - 1)
    val name = surroundEnd(file, start)
    val end = if (name > start) name else operatorEnd(file, text, start)

    return end?.takeIf { it > caret }
}

/**
 * Where the name read forward from [from] ends: an atom's, as [atomNameAt] says, or else a name's, at the end of the
 * token holding [from] at the latest, so a comment or an interpolation after it is not read as part of it.
 */
private fun surroundEnd(file: PsiFile, from: Int): Int {
    val leaf = file.findElementAt(from)
    atomNameEnd(leaf, from)?.let { return it }
    val name = CaretContext.nameEnd(file.viewProvider.contents, from)

    return leaf?.textRange?.endOffset?.let { minOf(name, it) } ?: name
}

/** Where the name of the atom [leaf] is in ends, from [offset] in it; `null` where [offset] is in no atom's name. */
private fun atomNameEnd(leaf: PsiElement?, offset: Int): Int? =
    atomAt(leaf)?.let { atomNameAt(it, offset) }?.endOffset

/**
 * Where the operator characters from [start] end, where the code through them reads as an operator or a dot call's
 * member; a lone `&` takes a capture's argument number with it, and a lone `~` a sigil's letter. `null` where it reads
 * as anything else, as `+/` in `&Kernel.+/2`.
 */
private fun operatorEnd(file: PsiFile, text: CharSequence, start: Int): Int? {
    val operator = CharArrayUtil.shiftForward(text, start, OPERATOR_OR_DOT)
    if (operator == start) return null

    return when (val context = CaretContext.at(file, operator)) {
        CaretContext.Operator("&") -> (operator until text.length).firstOrNull { !isDecimalDigit(text[it]) } ?: text.length
        is CaretContext.Operator -> operator
        is CaretContext.Dot -> operator.takeIf { context.hint.isNotEmpty() }
        CaretContext.Sigil("") -> (operator + 1).takeIf { CaretContext.sigilLetter(text.getOrNull(operator)) }
        else -> null
    }
}

/** Where `surround_context` starts reading at [at]: past an atom's `:`, a struct's `%`, or a dot call's dot. */
private fun surroundStart(text: CharSequence, at: Int): Int {
    val start = CharArrayUtil.shiftForward(text, at, "%")

    if (text.getOrNull(start) == ':' && text.getOrNull(start + 1).let { it != null && it != ':' } && text.getOrNull(start - 1) != ':') {
        return start + 1
    }

    val afterSpaces = spaces(text, start)

    // A dot that is the character before the caret, not one after it.
    if (afterSpaces == at &&
        text.getOrNull(afterSpaces) == '.' &&
        text.getOrNull(afterSpaces + 1).let { it != null && it != '.' } &&
        text.getOrNull(afterSpaces - 1).let { it != null && it != '.' }
    ) {
        return spaces(text, afterSpaces + 1)
    }

    val before = CharArrayUtil.shiftBackward(text, start - 1, CaretContext.SPACES)

    return when (text.getOrNull(before)) {
        '.' -> if (text.getOrNull(before - 1).let { it == null || it !in ".:" }) afterSpaces else start
        '%' -> if (text.getOrNull(afterSpaces)?.let { it in 'A'..'Z' } == true) afterSpaces else start
        else -> start
    }
}

/** Where the spaces from [at] end. */
private fun spaces(text: CharSequence, at: Int): Int = CharArrayUtil.shiftForward(text, at, CaretContext.SPACES)

private const val OPERATOR_OR_DOT = "${CaretContext.OPERATOR_CHARACTERS}."

private val OPENING = TokenSet.create(
    ElixirTypes.OPENING_PARENTHESIS,
    ElixirTypes.OPENING_BRACKET,
    ElixirTypes.OPENING_CURLY,
    ElixirTypes.OPENING_BIT,
    ElixirTypes.INTERPOLATION_START,
)

private val CLOSING = TokenSet.create(
    ElixirTypes.CLOSING_PARENTHESIS,
    ElixirTypes.CLOSING_BRACKET,
    ElixirTypes.CLOSING_CURLY,
    ElixirTypes.CLOSING_BIT,
    ElixirTypes.INTERPOLATION_END,
)

/** What holds a pair nearer than a map's braces would. */
private val ARGUMENTS = arrayOf(
    ElixirBracketArguments::class.java,
    ElixirList::class.java,
    ElixirNoParenthesesArguments::class.java,
    ElixirParenthesesArguments::class.java,
    ElixirTuple::class.java,
)

