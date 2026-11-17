package org.elixir_lang.psi

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.impl.toBigInteger

/**
 * Digit groups apart by whitespace are two tokens, as Elixir reads them (`syntax error before: "2"` for `1 2`), not
 * one number. Digit groups apart by `_` or nothing are one.
 */
class DigitGroupsApartTest : PlatformTestCase() {
    fun testTwoDigitGroupsApartByWhitespaceAreNotOneNumber() {
        for (code in listOf("1 2", "1_0 2", "1.0 2", "1.0e1 2", "1 2.0", "1.0e+1 2", "1 \t 2")) {
            myFixture.configureByText("apart.ex", "$code\n")

            assertTrue("$code has an error", PsiTreeUtil.hasErrorElements(myFixture.file))
            assertEquals(code, emptyList<String>(), numbersWithWhitespace())
        }
    }

    /** What Elixir reads `&f/1 2` as: the arity is `1`, and the `2` is the error. */
    fun testTheArityOfACaptureIsTheFirstDigitGroup() {
        myFixture.configureByText("capture.ex", "&f/1 2\n")

        val arities = PsiTreeUtil.findChildrenOfType(myFixture.file, ElixirDecimalWholeNumber::class.java).first()

        assertEquals("1", arities.text)
        assertEquals(1.toBigInteger(), arities.toBigInteger())
    }

    fun testDigitGroupsJoinedBySeparatorOrNothingAreOneNumber() {
        for (code in listOf("1_000", "1_0_0", "1.0_1", "1.0e1_0", "1_0.0_1")) {
            myFixture.configureByText("joined.ex", "$code\n")

            assertFalse("$code has no error", PsiTreeUtil.hasErrorElements(myFixture.file))
            assertEquals(code, listOf(code), numbers())
        }
    }

    /** The number elements that are not inside another number: `1.0e1_0` is one float. */
    private fun numbers(): List<String> =
        PsiTreeUtil
            .findChildrenOfAnyType(myFixture.file, ElixirDecimalFloat::class.java, ElixirDecimalWholeNumber::class.java)
            .filter { PsiTreeUtil.getParentOfType(it, ElixirDecimalFloat::class.java) == null }
            .map { it.text }

    private fun numbersWithWhitespace(): List<String> = numbers().filter { number -> number.any { it.isWhitespace() } }
}
