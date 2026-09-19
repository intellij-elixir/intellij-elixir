package org.elixir_lang.parser_definition

import com.intellij.psi.PsiFile
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.ElixirFileType
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.language_level.ElixirLanguageFeature
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir
import org.elixir_lang.psi.ElixirTypes

/**
 * A word directly after a number, as in `1and 2`, which Elixir reads as its own token after a based number on every
 * release and after any number before 1.12. Cases were run through `Code.string_to_quoted/1` on 1.11.4, 1.12.3 and
 * 1.20.4.
 */
class WordAfterNumberTest : BasePlatformTestCase() {
    private var files = 0

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testWordAfterADecimalNumberBefore1_12() {
        for ((source, word) in DECIMAL) {
            assertWord(elixir("1.11.0"), source, word)
        }
    }

    fun testWordAfterADecimalNumberFrom1_12IsPartOfTheNumber() {
        for (languageLevel in listOf(elixir("1.12.0"), elixir("1.20.0"))) {
            for ((source, word) in DECIMAL) {
                assertInvalidDigits(languageLevel, source, word)
            }
        }
    }

    fun testWordAfterABasedNumber() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.12.0"), elixir("1.20.0"))) {
            for ((source, word) in BASED) {
                assertWord(languageLevel, source, word)
            }
        }
    }

    fun testWordsElixirRejectsAfterANumber() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for ((source, word) in REJECTED) {
                assertInvalidDigits(languageLevel, source, word)
            }
        }
    }

    /** Before 1.12 Elixir reads a reserved word followed by `::` as a keyword key, so `in::` makes no `not in`. */
    fun testNotInBeforeATypeOperatorFrom1_12() {
        assertInvalidDigits(elixir("1.11.0"), "0b1not in::x", "not")

        // Still invalid from 1.12, as Elixir says: `syntax error before: '::'`.
        for (languageLevel in listOf(elixir("1.12.0"), elixir("1.20.0"))) {
            val source = "0b1not in::x"
            val type = parse(languageLevel, source).findElementAt(offset(source, "not"))?.node?.elementType

            assertEquals("type of not in $source on $languageLevel", WORDS.getValue("not"), type)
        }
    }

    fun testQuotedAsThisLegsElixir() {
        val languageLevel = ElixirLanguageLevel.of(System.getenv("ELIXIR_VERSION"))
        val cases = if (ElixirLanguageFeature.DECIMAL_NUMBER_ENDS_BEFORE_WORD.isSufficient(languageLevel)) {
            DECIMAL + BASED
        } else {
            BASED
        }

        for ((source, _) in cases) {
            try {
                Quoter.assertQuotedCorrectly(parse(languageLevel, source))
            } catch (e: Throwable) {
                throw AssertionError("quoting $source on $languageLevel", e)
            }
        }
    }

    private fun assertWord(languageLevel: ElixirLanguageLevel, source: String, word: String) {
        val file = parse(languageLevel, source)
        val offset = offset(source, word)

        assertEquals(
            "type of $word in $source on $languageLevel",
            WORDS.getValue(word),
            file.findElementAt(offset)?.node?.elementType
        )
        assertFalse("error in $source on $languageLevel", PsiTreeUtil.hasErrorElements(file))

        // The lexer folds a newline after `do` into the keyword, which it cannot do for a remapped one.
        if ('\n' in source) return

        assertEquals(
            "tree of $source on $languageLevel",
            DebugUtil.psiToString(
                parse(languageLevel, source.substring(0, offset) + " " + source.substring(offset)),
                false
            ),
            DebugUtil.psiToString(file, false)
        )
    }

    private fun assertInvalidDigits(languageLevel: ElixirLanguageLevel, source: String, word: String) {
        val type = parse(languageLevel, source).findElementAt(offset(source, word))?.node?.elementType

        assertTrue("type of $word in $source on $languageLevel: $type", type in INVALID_DIGITS)
    }

    /**
     * A new file name each time, or a file with the same text keeps the tree parsed under the previous language level.
     */
    private fun parse(languageLevel: ElixirLanguageLevel, source: String): PsiFile {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, languageLevel)

        return myFixture.configureByText("word_after_number_${files++}.${ElixirFileType.INSTANCE.defaultExtension}", source)
    }

    private fun offset(source: String, word: String): Int =
        Regex("[0-9A-Fa-f]$word").find(source)!!.range.first + 1

    private companion object {
        val WORDS: Map<String, IElementType> = mapOf(
            "after" to ElixirTypes.AFTER,
            "and" to ElixirTypes.AND_WORD_OPERATOR,
            "catch" to ElixirTypes.CATCH,
            "do" to ElixirTypes.DO,
            "else" to ElixirTypes.ELSE,
            "end" to ElixirTypes.END,
            "in" to ElixirTypes.IN_OPERATOR,
            "not" to ElixirTypes.NOT_OPERATOR,
            "or" to ElixirTypes.OR_WORD_OPERATOR,
            "rescue" to ElixirTypes.RESCUE,
            "when" to ElixirTypes.WHEN_OPERATOR,
        )

        val INVALID_DIGITS = setOf(
            ElixirTypes.INVALID_BINARY_DIGITS,
            ElixirTypes.INVALID_DECIMAL_DIGITS,
            ElixirTypes.INVALID_HEXADECIMAL_DIGITS,
            ElixirTypes.INVALID_OCTAL_DIGITS,
        )

        val DECIMAL = listOf(
            "1and 2" to "and",
            "1or 2" to "or",
            "1in x" to "in",
            "1when 2" to "when",
            "x = 1when 2" to "when",
            "1not in x" to "not",
            "1not  in x" to "not",
            "foo 1do\n:ok\nend" to "do",
            "if true do 1end" to "end",
            "if true do 1else 2 end" to "else",
            "try do 1rescue _ -> 2 end" to "rescue",
            "try do 1catch _ -> 2 end" to "catch",
            "try do 1after 2 end" to "after",
            "1.5and 2" to "and",
            "1.0e10and 2" to "and",
            "1_000and 2" to "and",
        )

        val BASED = listOf(
            "0b1and 2" to "and",
            "0b1or 2" to "or",
            "0b1in x" to "in",
            "0b1not in x" to "not",
            "0o7and 2" to "and",
            "0o7or 2" to "or",
            "0x1or 2" to "or",
            "0x1in x" to "in",
            "0x1not in x" to "not",
            "0xFin x" to "in",
            "0b1not\tin x" to "not",
            "0b1not\\\nin x" to "not",
            "foo 0b1do\n:ok\nend" to "do",
        )

        /** Elixir 1.11.4 and 1.20.4 both reject these. */
        val REJECTED = listOf(
            "1andx" to "andx",
            "1and2" to "and",
            "1and? 2" to "and",
            "1And 2" to "And",
            "1and: 2" to "and",
            "1do: 2" to "do",
            "1true" to "true",
            "1not x" to "not",
            "0b1and2" to "and2",
            "0b1nil" to "nil",
            "0b1and@x" to "and",
            "0b1do: 1" to "do",
            "1and\u00E9 2" to "and",
            "1and\u0301 2" to "and",
            "0b1and\u0301 2" to "and",
        )
    }
}
