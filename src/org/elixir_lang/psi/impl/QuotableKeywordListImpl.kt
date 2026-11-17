package org.elixir_lang.psi.impl

import com.intellij.util.concurrency.annotations.RequiresReadLock
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

/** The value of the last pair that names [keywordKeyText], as `Code.compiler_options/1` reads its pairs. */
@RequiresReadLock
fun QuotableKeywordList.lastKeywordValue(keywordKeyText: String): Quotable? =
    quotableKeywordPairList().lastOrNull { it.hasKeywordKey(keywordKeyText) }?.keywordValue

/** Whether this pair is the one [keywordKeyText] is read from in its list: the first that names it. */
@RequiresReadLock
fun QuotableKeywordPair.givesOption(keywordKeyText: String): Boolean =
    hasKeywordKey(keywordKeyText) && (parent as? QuotableKeywordList)?.firstPair(keywordKeyText) === this
