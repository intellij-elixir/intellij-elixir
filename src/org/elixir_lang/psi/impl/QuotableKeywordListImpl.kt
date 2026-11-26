package org.elixir_lang.psi.impl

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirAtomKeyword
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.ElixirTuple
import org.elixir_lang.psi.Quotable
import org.elixir_lang.psi.QuotableKeywordList
import org.elixir_lang.psi.QuotableKeywordPair

/**
 * The value of the first pair that names [keywordKeyText], as `Keyword.get/2` reads it.
 *
 * @receiver the keyword list to search.
 * @param keywordKeyText the text of the key, such as `"as"`.
 */
@RequiresReadLock
fun QuotableKeywordList.keywordValue(keywordKeyText: String): Quotable? = firstPair(keywordKeyText)?.keywordValue

private fun QuotableKeywordList.firstPair(keywordKeyText: String): QuotableKeywordPair? =
    quotableKeywordPairList().firstOrNull { it.hasKeywordKey(keywordKeyText) }

/** Whether this pair is the one [keywordKeyText] is read from in its list: the first that names it. */
@RequiresReadLock
fun QuotableKeywordPair.givesOption(keywordKeyText: String): Boolean =
    hasKeywordKey(keywordKeyText) && (parent as? QuotableKeywordList)?.firstPair(keywordKeyText) === this

/**
 * The pairs of this list, read through parentheses, in order, as `Keyword.get/2` reads them: each keyword pair, and each
 * 2-tuple whose first element is a literal atom, as its key and its second element, read through parentheses too.
 * `null` where an element is neither, or a key isn't literal, as `Keyword.drop/2` and `Keyword.merge/2` raise on such a
 * list, and where this is no list.
 */
@RequiresReadLock
fun PsiElement.keywordPairs(): List<Pair<String, PsiElement>>? =
    when (val list = stripAccessExpressionAndParentheses()) {
        is QuotableKeywordList -> list.pairs()
        is ElixirList ->
            list.elements().flatMap { element ->
                when (element) {
                    is QuotableKeywordList -> element.pairs() ?: return null
                    else -> listOf(tuplePair(element) ?: return null)
                }
            }
        else -> null
    }

/** The elements of this list, each read through parentheses. */
@RequiresReadLock
fun ElixirList.elements(): List<PsiElement> = children.map { it.stripAccessExpressionAndParentheses() }

/** The elements of this tuple, each read through parentheses. */
@RequiresReadLock
fun ElixirTuple.elements(): List<PsiElement> = children.map { it.stripAccessExpressionAndParentheses() }

@RequiresReadLock
private fun QuotableKeywordList.pairs(): List<Pair<String, PsiElement>>? =
    quotableKeywordPairList().map { pair -> (pair.keywordAtom() ?: return null) to pair.keywordValue.stripAccessExpressionAndParentheses() }

@RequiresReadLock
private fun tuplePair(element: PsiElement): Pair<String, PsiElement>? {
    val (key, value) = (element as? ElixirTuple)?.elements()?.takeIf { it.size == 2 } ?: return null

    return (key.takeIf { it is ElixirAtom || it is ElixirAtomKeyword }?.let(AtomName::of) ?: return null) to value
}
