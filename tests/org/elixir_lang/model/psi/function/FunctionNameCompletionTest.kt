package org.elixir_lang.model.psi.function

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.completionStringsAtCaret
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

/**
 * A function is offered under every spelling of the atom it is named by, so the prefix typed to reach it matches
 * whichever spelling is typed.
 */
class FunctionNameCompletionTest : PlatformTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testMicroSignPrefixOffersMicroSignFunctions() = assertOffers(MICRO, prefix = MICRO, offered = GREEK_MU)

    fun testGreekMuPrefixOffersMicroSignFunctions() = assertOffers(MICRO, prefix = GREEK_MU, offered = GREEK_MU)

    fun testDecomposedPrefixOffersComposedFunctions() = assertOffers(COMPOSED, prefix = DECOMPOSED, offered = COMPOSED)

    fun testComposedPrefixOffersDecomposedFunctions() = assertOffers(DECOMPOSED, prefix = COMPOSED, offered = COMPOSED)

    /** Before 1.14 `μb` is not a spelling of `µb`, so a typed `µ` does not offer it. */
    fun testMicroSignPrefixOffersNoGreekMuFunctionBefore1_14() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.13.0"))
        myFixture.configureByText(
            "completion.ex",
            "defmodule M do\n  def ${GREEK_MU}b, do: 1\n  def ${GREEK_MU}c, do: 2\n  def f, do: $MICRO<caret>\nend\n"
        )

        val strings = myFixture.completionStringsAtCaret().orEmpty()

        assertTrue("$strings\n${myFixture.editor.document.text}", strings.none { it.startsWith(GREEK_MU) })
        assertFalse(myFixture.editor.document.text, myFixture.editor.document.text.contains("${GREEK_MU}b()"))
    }

    fun testMicroSignPrefixDoesNotOfferGreekMuFunctionsOfAnotherModuleBefore1_14() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.13.0"))
        myFixture.configureByText(
            "completion.ex",
            "defmodule M do\n  def ${GREEK_MU}b, do: 1\n  def ${MICRO}c, do: 2\n  def ${MICRO}d, do: 3\nend\n" +
                "defmodule N do\n  def f, do: M.$MICRO<caret>\nend\n"
        )

        assertMicroSignFunctionsOnly()
    }

    fun testMicroSignPrefixOffersMicroSignFunctionsOfAnotherModule() =
        assertOffersFromAnotherModule(MICRO, prefix = MICRO, offered = GREEK_MU)

    fun testDecomposedPrefixOffersComposedFunctionsOfAnotherModule() =
        assertOffersFromAnotherModule(COMPOSED, prefix = DECOMPOSED, offered = COMPOSED)

    private fun assertOffersFromAnotherModule(stem: String, prefix: String, offered: String) {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.14.0"))
        myFixture.configureByText(
            "completion.ex",
            "defmodule M do\n  def ${stem}b, do: 1\n  def ${stem}c, do: 2\nend\n" +
                "defmodule N do\n  def f, do: M.$prefix<caret>\nend\n"
        )

        assertEquals(
            myFixture.editor.document.text,
            listOf("${offered}b", "${offered}c"),
            myFixture.completionStringsAtCaret()?.filter { it.startsWith(offered) }?.sorted()
        )
    }

    private fun assertMicroSignFunctionsOnly() {
        val strings = myFixture.completionStringsAtCaret().orEmpty()

        assertTrue("$strings\n${myFixture.editor.document.text}", strings.containsAll(listOf("${MICRO}c", "${MICRO}d")))
        assertFalse("${GREEK_MU}b is another function before 1.14: $strings", strings.contains("${GREEK_MU}b"))
    }

    /** The functions are written with [stem] and offered as the atom, which is spelled with [offered]. */
    private fun assertOffers(stem: String, prefix: String, offered: String) {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.14.0"))
        myFixture.configureByText(
            "completion.ex",
            "defmodule M do\n  def ${stem}b, do: 1\n  def ${stem}c, do: 2\n  def f, do: $prefix<caret>\nend\n"
        )

        val strings = myFixture.completionStringsAtCaret()

        assertEquals(
            myFixture.editor.document.text,
            listOf("${offered}b", "${offered}c"),
            strings?.filter { it.startsWith(offered) }?.sorted()
        )
    }

    private companion object {
        const val MICRO = "\u00b5"
        const val GREEK_MU = "\u03bc"
        const val COMPOSED = "sno\u0107"
        const val DECOMPOSED = "sno\u0063\u0301"
    }
}
