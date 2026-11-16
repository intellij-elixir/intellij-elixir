package org.elixir_lang.expander

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.expander.interpreter.Tokenizer
import org.elixir_lang.language_level.ElixirLanguageLevel

/** `elixir_tokenizer:tokenize/3` of a long line is cancelled as it lexes the text and as it names the tokens. */
class TokenizerCancelTest : BasePlatformTestCase() {
    fun testLexingIsCancelled() = assertCancelledIn(Tokenizer::class.java.name, "lexed", TOKENS / 2, block = ::tokenize)

    fun testNamingIsCancelled() = assertCancelledIn(Tokenizer::class.java.name, "tokenize", TOKENS / 2, block = ::tokenize)

    private fun tokenize() {
        Tokenizer(ElixirLanguageLevel.of("1.20.4")).tokenize(TEXT, 1)
    }

    private companion object {
        const val TOKENS = 1_000

        /** `a,a,...,a`: an identifier and a comma for each two tokens. */
        val TEXT = List(TOKENS / 2) { "a" }.joinToString(",")
    }
}
