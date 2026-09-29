package org.elixir_lang.psi.impl

import org.elixir_lang.psi.Quotable
import org.elixir_lang.psi.QuotableKeywordList


/**
 * The value associated with the keyword value.
 *
 * @param this@keywordValue The keyword list to search for `keywordKeyText`.
 * @param keywordKeyText the text of the keyword value.
 * @return the `PsiElement` associated with the first `keywordKeyText`, as `Keyword.get` reads a repeated key.
 */
fun QuotableKeywordList.keywordValue(keywordKeyText: String): Quotable? =
    quotableKeywordPairList().firstOrNull { it.hasKeywordKey(keywordKeyText) }?.keywordValue
