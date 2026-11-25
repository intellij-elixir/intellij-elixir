package org.elixir_lang.code_insight.completion.contributor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Macro
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.code_insight.completion.CaretContext
import org.elixir_lang.code_insight.completion.callableWith
import org.elixir_lang.code_insight.completion.unqualifiedLookupElements
import org.elixir_lang.declaration.CompletionPosition
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Visible
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.psi.call.Call
import org.elixir_lang.reference.Callable

/** Offers what IEx offers where the caret's [CaretContext] alone says what is being typed, and no reference answers. */
@Suppress("CanBeDumbAware") // It reads indexes.
internal class ContextItems : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        val caret = parameters.offset
        // Elixir's context reads a literal's content as code, which it is not.
        if (inLiteralContent(file, caret)) return

        when (val context = CaretContext.at(file, caret)) {
            is CaretContext.BlockKeywordOrBinaryOperator -> {
                val prefixed = result.withPrefixMatcher(context.hint)

                BLOCK_KEYWORDS.forEach { prefixed.addElement(LookupElementBuilder.create(it).bold()) }
                leftOperandEnd(file, caret - context.hint.length)
                    ?.let { prefixed.addAllElements(infixOperators(it, prefixed.prefixMatcher)) }
            }

            CaretContext.Expr ->
                if (typingAModifier(parameters.position)) bitstringModifiers(parameters, result, "")

            is CaretContext.LocalOrVar ->
                if (typingAModifier(parameters.position)) bitstringModifiers(parameters, result, context.hint)

            is CaretContext.Operator ->
                if (context.operator in MODIFIER_OPERATORS && typingAModifier(parameters.position)) {
                    bitstringModifiers(parameters, result, "")
                } else {
                    result.withPrefixMatcher(context.operator)
                        .addAllElements(operatorsStartingWith(file, caret, context.operator))
                }

            // Elixir lists the operator written before the `/` and inserts nothing.
            is CaretContext.OperatorArity -> {
                val operator = context.operator
                val unchanged = Unchanged(parameters.editor.document.immutableCharSequence)

                result.withPrefixMatcher(operator).addAllElements(
                    operatorsTypedAt(file, caret - operator.length - 1) { it.lookupName == operator }
                        .map { LookupElementDecorator.withInsertHandler(it, unchanged) }
                )
            }

            is CaretContext.Sigil,
            CaretContext.None,
            is CaretContext.Struct,
            is CaretContext.Dot,
            is CaretContext.UnquotedAtom,
            is CaretContext.LocalCall,
            is CaretContext.Alias,
            is CaretContext.InsideAlias,
            is CaretContext.AnonymousCall,
            is CaretContext.CaptureArg,
            is CaretContext.DotArity,
            is CaretContext.DotCall,
            is CaretContext.LocalArity,
            is CaretContext.ModuleAttribute,
            is CaretContext.OperatorCall,
            -> {}
        }
    }
}

/** IEx's block keywords: those a `do` block's keyword list takes, and `end`. */
private val BLOCK_KEYWORDS = Macro.KEYWORD_BLOCK_KEYWORDS.toList() + "end"

/** The operators after which Elixir offers a bitstring segment's modifiers. */
private val MODIFIER_OPERATORS = setOf("::", "-")

/** IEx's bitstring modifiers. */
private val BITSTRING_MODIFIERS = listOf(
    "big", "binary", "bitstring", "integer", "float", "little", "native", "signed", "size", "unit", "unsigned", "utf8",
    "utf16", "utf32",
)

/**
 * The last code leaf before [start], where what is typed starts: the end of the expression an operator there takes as
 * its left.
 */
private fun leftOperandEnd(file: PsiFile, start: Int): PsiElement? =
    generateSequence(file.findElementAt(start - 1), PsiTreeUtil::prevLeaf)
        .firstOrNull { it.textRange.endOffset <= start && it !is PsiComment && it.text.isNotBlank() }

/**
 * The operators [InspectAtom.infixOperators] names for the file's Elixir that [matcher] takes, each with the definition
 * visible at [left], the last leaf of the operator's left operand, that takes two arguments, where there is one.
 */
@RequiresReadLock
private fun infixOperators(left: PsiElement, matcher: PrefixMatcher): List<LookupElement> {
    val names = InspectAtom.infixOperators(ElixirLanguageLevelResolver.languageLevelFor(left))
        .filterTo(LinkedHashSet(), matcher::prefixMatches)
    if (names.isEmpty()) return emptyList()
    val defined = visibleOperators(left) { it.lookupName in names && it.callableWith(2) }
        .associateBy(LookupElement::getLookupString)

    return names.map { defined[it] ?: LookupElementBuilder.create(it) }
}

/** [operatorsTypedAt] that start with [operator], which is typed just before [caret]. */
@RequiresReadLock
private fun operatorsStartingWith(file: PsiFile, caret: Int, operator: String): List<LookupElement> =
    operatorsTypedAt(file, caret - operator.length) { it.lookupName.startsWith(operator) }

/** [visibleOperators] at the left operand of an operator typed from [start]; none where there is no left operand. */
@RequiresReadLock
private fun operatorsTypedAt(file: PsiFile, start: Int, take: (Visible) -> Boolean): List<LookupElement> =
    leftOperandEnd(file, start)?.let { left -> visibleOperators(left, take) }.orEmpty()

/** The definitions visible at [left], as [infixOperators] says, that [take], as items that insert their name alone. */
@RequiresReadLock
private fun visibleOperators(left: PsiElement, take: (Visible) -> Boolean): List<LookupElement> =
    unqualifiedLookupElements(
        sourceFor(Feature.COMPLETION).visible(CompletionPosition.At(left)).filter(take),
        appendParentheses = false,
    )

/** Whether the name holding the dummy at [position], in completion's copy, is a bitstring segment's modifier. */
private fun typingAModifier(position: PsiElement): Boolean =
    PsiTreeUtil.getParentOfType(position, Call::class.java)?.let { Callable.isBitStreamSegmentOption(it) } == true

/**
 * Offers the bitstring modifiers starting with [hint] that the segment's type does not already hold, in place of the
 * names a type takes, as Elixir does.
 */
private fun bitstringModifiers(parameters: CompletionParameters, result: CompletionResultSet, hint: String) {
    val written = written(parameters.originalFile.viewProvider.contents, parameters.offset)

    result.withPrefixMatcher(hint).addAllElements(
        BITSTRING_MODIFIERS.filter { it.startsWith(hint) && it !in written }.map(LookupElementBuilder::create)
    )
    result.stopHere()
}

/**
 * The modifiers written in the segment's type in [text] before [caret], as IEx reads them: the text after the last
 * `::`, split at each `-`.
 */
private fun written(text: CharSequence, caret: Int): List<String> {
    val start = text.lastIndexOf("::", caret - 2).let { if (it < 0) 0 else it + 2 }

    return text.subSequence(start, caret).split("-")
}

/**
 * Puts back the text [before] completion that the platform replaced, and each caret after the item's name, where it
 * was, so choosing an item changes nothing.
 */
private class Unchanged(private val before: CharSequence) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val document = context.document
        val start = context.startOffset
        val replacedEnd = before.length - (document.textLength - context.tailOffset)

        document.replaceString(start, context.tailOffset, before.subSequence(start, replacedEnd))
        context.editor.caretModel.moveToOffset(start + item.lookupString.length)
    }
}
