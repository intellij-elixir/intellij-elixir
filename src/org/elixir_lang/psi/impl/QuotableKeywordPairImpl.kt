package org.elixir_lang.psi.impl

import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Macro.KEYWORD_BLOCK_KEYWORDS
import org.elixir_lang.lowering.ElementLowering
import org.elixir_lang.psi.ElixirKeywordPair
import org.elixir_lang.psi.ElixirNoParenthesesKeywordPair
import org.elixir_lang.psi.Quotable
import org.elixir_lang.psi.QuotableKeywordList
import org.elixir_lang.psi.QuotableKeywordPair

@RequiresReadLock
fun QuotableKeywordPair.hasKeywordKey(keywordKeyText: String): Boolean {
    ThreadingAssertions.assertReadAccess()

    return keywordKey.let { it.text == keywordKeyText || ElementLowering.atomName(it) == keywordKeyText }
}

/**
 * This pair's key when it is a key of [KEYWORD_BLOCK_KEYWORDS] in a keyword list that also has `do`, as `else` in
 * `if c, do: a, else: b`, but not in `Keyword.keys(else: b)`.
 */
@RequiresReadLock
fun QuotableKeywordPair.blockKeyword(): String? =
    keywordAtom()?.takeIf { keyword ->
        keyword in KEYWORD_BLOCK_KEYWORDS &&
            (keyword == "do" ||
                (parent as? QuotableKeywordList)?.quotableKeywordPairList().orEmpty().any { it.keywordAtom() == "do" })
    }

private fun QuotableKeywordPair.keywordAtom(): String? {
    val text = keywordKey.text

    return if (text in KEYWORD_BLOCK_KEYWORDS) text else ElementLowering.atomName(keywordKey)
}

object QuotableKeywordPairImpl {
    @JvmStatic
    fun getKeywordValue(keywordPair: ElixirKeywordPair): Quotable {
        val children = keywordPair.children

        assert(children.size >= 2)

        return children[1].stripAccessExpression() as Quotable
    }

    @JvmStatic
    fun getKeywordValue(noParenthesesKeywordPair: ElixirNoParenthesesKeywordPair): Quotable {
        val children = noParenthesesKeywordPair.children

        assert(children.size >= 2)

        return children[1].stripAccessExpression() as Quotable
    }
}
