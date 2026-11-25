package org.elixir_lang.code_insight.completion.contributor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Macro
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.code_insight.completion.CaretContext
import org.elixir_lang.code_insight.completion.callableWith
import org.elixir_lang.code_insight.completion.unqualifiedLookupElements
import org.elixir_lang.declaration.CompletionPosition
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.language_level.ElixirLanguageLevelResolver

/** Offers what IEx offers where the caret's [CaretContext] alone says what is being typed, and no reference answers. */
@Suppress("CanBeDumbAware") // It reads indexes.
internal class ContextItems : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        // Elixir's context reads a literal's content as code, which it is not.
        if (inLiteralContent(parameters.originalFile, parameters.offset)) return

        when (val context = CaretContext.at(parameters.originalFile, parameters.offset)) {
            is CaretContext.BlockKeywordOrBinaryOperator -> {
                val prefixed = result.withPrefixMatcher(context.hint)

                BLOCK_KEYWORDS.forEach { prefixed.addElement(LookupElementBuilder.create(it).bold()) }
                // The space before what is typed follows the expression the operator would take as its left.
                parameters.originalFile.findElementAt(parameters.offset - context.hint.length - 1)
                    ?.let(PsiTreeUtil::prevCodeLeaf)
                    ?.let { prefixed.addAllElements(infixOperators(it, prefixed.prefixMatcher)) }
            }

            CaretContext.Expr,
            is CaretContext.LocalOrVar,
            is CaretContext.Operator,
            is CaretContext.OperatorArity,
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

/**
 * The operators [InspectAtom.infixOperators] names for the file's Elixir that [matcher] takes, each with the definition
 * visible at [left], the last leaf of the operator's left operand, that takes two arguments, where there is one.
 */
@RequiresReadLock
private fun infixOperators(left: PsiElement, matcher: PrefixMatcher): List<LookupElement> {
    val names = InspectAtom.infixOperators(ElixirLanguageLevelResolver.languageLevelFor(left))
        .filterTo(LinkedHashSet(), matcher::prefixMatches)
    if (names.isEmpty()) return emptyList()
    val defined = sourceFor(Feature.COMPLETION).visible(CompletionPosition.At(left))
        .filter { it.lookupName in names && it.callableWith(2) }
        .let { unqualifiedLookupElements(it, appendParentheses = false) }
        .associateBy(LookupElement::getLookupString)

    return names.map { defined[it] ?: LookupElementBuilder.create(it) }
}
