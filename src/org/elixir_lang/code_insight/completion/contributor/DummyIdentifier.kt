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
import org.elixir_lang.code_insight.completion.CaretContext
import org.elixir_lang.model.psi.config.Set
import org.elixir_lang.psi.ElixirBracketArguments
import org.elixir_lang.psi.ElixirInterpolation
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

/** Where the dummy starts in [text], whichever of [ALIAS] or [NAME] it is; `-1` where [text] holds none. */
internal fun dummyStart(text: String): Int = text.indexOf(ALIAS, ignoreCase = true)

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

        // The alias's reference offers its nested modules beside its functions.
        is CaretContext.Dot -> if (context.receiver is CaretContext.Alias || context.receiver is CaretContext.InsideAlias) ALIAS else NAME

        // The call's reference reads the whole name as the prefix.
        is CaretContext.LocalOrVar -> if (CaretContext.whole(context.hint)) NOTHING else NAME

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
        is CaretContext.UnquotedAtom,
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
    val text = file.viewProvider.contents
    if (file.findElementAt(caret).let { it == null || it is PsiWhiteSpace || it.elementType == ElixirTypes.EOL }) return false
    // The rest of the name the caret is in, or else the name it starts.
    val keyEnd = (if (caret > 0) CaretContext.nameEnd(text, caret - 1) else null)?.takeIf { it > caret } ?: CaretContext.nameEnd(text, caret)
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

/** Whether the caret is all a map's braces hold, but for what the lexer reads as white space and comments. */
private fun aloneInMapBraces(file: PsiFile, caret: Int): Boolean {
    val opening = file.findElementAt(caret)?.let(PsiTreeUtil::prevCodeLeaf)
        ?.takeIf { it.elementType == ElixirTypes.OPENING_CURLY } ?: return false

    return opening.parent?.parent is ElixirMapOperation &&
        PsiTreeUtil.nextCodeLeaf(opening).elementType == ElixirTypes.CLOSING_CURLY
}

/** Whether the caret, just after [before], is in the content of a [kind] and not in an interpolation's code. */
private fun inContent(before: PsiElement?, caret: Int, kind: Class<out PsiElement>): Boolean {
    val quoted = PsiTreeUtil.getParentOfType(before, kind) ?: return false
    val interpolation = PsiTreeUtil.getParentOfType(before, ElixirInterpolation::class.java, true, kind)
    val promoter = quoted.node.findChildByType(PROMOTERS) ?: return false
    val terminator = quoted.node.findChildByType(TERMINATORS)

    return caret >= promoter.textRange.endOffset &&
        (terminator == null || caret <= terminator.startOffset) &&
        (interpolation == null || interpolation.textRange.endOffset == caret)
}

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

private val PROMOTERS = TokenSet.create(ElixirTypes.LINE_PROMOTER, ElixirTypes.HEREDOC_PROMOTER)
private val TERMINATORS = TokenSet.create(ElixirTypes.LINE_TERMINATOR, ElixirTypes.HEREDOC_TERMINATOR)
