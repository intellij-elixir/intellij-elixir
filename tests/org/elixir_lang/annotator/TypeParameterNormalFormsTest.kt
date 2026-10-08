package org.elixir_lang.annotator

import com.intellij.openapi.editor.colors.EditorColorsManager
import org.elixir_lang.ElixirSyntaxHighlighter
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

/**
 * The annotator takes a name for a type parameter by the atom it quotes to: from Elixir 1.14 `µ` and `μ`, and two normal
 * forms of `café`, are one parameter, and a quoted `"a":` key of a `when` declares `a`.
 */
class TypeParameterNormalFormsTest : PlatformTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testHeadParameterUse() = assertTypeParameters("@type t(a) :: a", "a", "a")
    fun testWhenKey() = assertTypeParameters("@spec f(a) :: a when a: term()", "a", "a", "a")
    fun testQuotedWhenKey() = assertTypeParameters("@spec f(a) :: a when \"a\": term()", "\"a\"", "a", "a")
    fun testMicroSignHeadParameterUse() = assertTypeParameters("@type t(µ) :: µ", "µ", "µ")
    fun testMicroSignWhenKey() = assertTypeParameters("@spec f(µ) :: µ when µ: term()", "µ", "µ", "µ")
    fun testGreekMuUseOfMicroSignParameter() = assertTypeParameters("@type t(µ) :: μ", "µ", "μ")

    fun testMicroSignParametersBefore1_14() =
        assertTypeParameters("@spec f(µ) :: µ when µ: term()", "µ", "µ", "µ", level = elixir("1.13.0"))

    /** Before 1.14 `μ` is not the parameter `µ`. */
    fun testGreekMuIsNotTheMicroSignParameterBefore1_14() =
        assertTypeParameters("@type t(µ) :: μ", "µ", level = elixir("1.13.0"))

    fun testDecomposedUseOfPrecomposedParameter() =
        assertTypeParameters("@type t($PRECOMPOSED) :: $DECOMPOSED", PRECOMPOSED, DECOMPOSED)

    fun testPrecomposedUseOfDecomposedParameter() =
        assertTypeParameters("@type t($DECOMPOSED) :: $PRECOMPOSED", DECOMPOSED, PRECOMPOSED)

    fun testDecomposedWhenKeyOfPrecomposedParameters() =
        assertTypeParameters("@spec f($PRECOMPOSED) :: $PRECOMPOSED when $DECOMPOSED: term()", PRECOMPOSED, PRECOMPOSED, DECOMPOSED)

    /**
     * Asserts that each of [expected], in source order, is the text of a type parameter, and that no other `a`, `µ`,
     * `μ` or `café` in the attribute is.
     */
    private fun assertTypeParameters(
        attribute: String,
        vararg expected: String,
        level: ElixirLanguageLevel = elixir("1.14.0")
    ) {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)
        val text = "defmodule M do\n  $attribute\nend\n"
        myFixture.configureByText("type.ex", text)

        val typeParameter = EditorColorsManager.getInstance().globalScheme.getAttributes(ElixirSyntaxHighlighter.TYPE_PARAMETER)
        val highlighted = myFixture.doHighlighting()
            .filter { typeParameter == it.forcedTextAttributes }
            .map { text.substring(it.startOffset, it.endOffset) }
            .sortedBy { text.indexOf(it) }

        assertEquals(attribute, expected.toList().sorted(), highlighted.sorted())
    }

    private companion object {
        const val DECOMPOSED = "cafe\u0301"
        const val PRECOMPOSED = "caf\u00e9"
    }
}
