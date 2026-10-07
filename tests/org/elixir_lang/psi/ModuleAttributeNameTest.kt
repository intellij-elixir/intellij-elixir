package org.elixir_lang.psi

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir
import org.elixir_lang.lowering.LoweringCounters
import org.elixir_lang.psi.impl.ElixirPsiImplUtil

/**
 * A module attribute is named by the atom after its `@`, as Elixir reads it: `@ doc` and `@(doc)` are `@doc`, and
 * from Elixir 1.14 two normal forms of one name are one attribute.
 */
class ModuleAttributeNameTest : PlatformTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testDeclaration() = assertNames("@doc \"x\"", "@doc")
    fun testDeclarationWithSpaceAfterAt() = assertNames("@ doc \"x\"", "@doc")
    fun testDeclarationWithSpaceAfterAtAndLongArguments() = assertNames("@ spec f(integer) :: integer", "@spec")
    fun testRead() = assertNames("@foo 1\ndef f, do: @foo", "@foo", "@foo")
    fun testReadWithSpaceAfterAt() = assertNames("@foo 1\ndef f, do: @ foo", "@foo", "@foo")
    fun testReadInParentheses() = assertNames("@foo 1\ndef f, do: @(foo)", "@foo", "@foo")
    fun testReadInParenthesesWithSpaceAfterAt() = assertNames("@foo 1\ndef f, do: @ (foo)", "@foo", "@foo")
    fun testDeclarationInParentheses() = assertNames("@(bar 2)", "@bar")
    fun testDeclarationInParenthesesThenRead() = assertNames("@(bar 2)\ndef h, do: @bar", "@bar", "@bar")
    fun testOperandThatNamesNoAttributeKeepsItsText() = assertNames("@[1]", "@[1]")

    fun testNormalFormsAreOneAttributeFrom1_14() {
        assertNames("@$DECOMPOSED 7\ndef f, do: @$PRECOMPOSED", "@$PRECOMPOSED", "@$PRECOMPOSED", level = elixir("1.14.0"))
    }

    /** Elixir before 1.14 keeps each spelling as it is written, so the attributes differ. */
    fun testNormalFormsAreTwoAttributesBefore1_14() {
        assertNames("@$DECOMPOSED 7\ndef f, do: @$PRECOMPOSED", "@$DECOMPOSED", "@$PRECOMPOSED", level = elixir("1.13.0"))
    }

    fun testMicroSignIsTheGreekMuFrom1_14() {
        assertNames("@µ 7\ndef f, do: @µ", "@μ", "@μ", level = elixir("1.14.0"))
    }

    fun testMicroSignStaysBefore1_14() {
        assertNames("@µ 7\ndef f, do: @µ", "@µ", "@µ", level = elixir("1.13.0"))
    }

    fun testLongestAttributeName() = assertNames("@" + "a".repeat(255) + " 1", "@" + "a".repeat(255))

    fun testNonReferencingWithSpaceAfterAt() {
        configure("@ doc \"x\"\n@ spec f() :: integer\n@ type t :: atom\n@ callback c() :: t\n@ behaviour B\n@ foo 1")

        assertEquals(
            listOf(true, true, true, true, true, false),
            declarations().map { runReadAction { ModuleAttribute.isNonReferencing(it.atIdentifier) } }
        )
    }

    /** An attribute written `@name` in ASCII is named from its text, with no lowering. */
    fun testAsciiAttributeNamesLowerNothing() {
        configure("@foo 1\n@doc \"x\"\ndef f, do: @foo\ndef g, do: @(foo)\ndef h, do: @ foo\n")

        LoweringCounters.reset()
        LoweringCounters.counting = true
        val names = try {
            allNames()
        } finally {
            LoweringCounters.counting = false
        }

        assertEquals(listOf("@foo", "@doc", "@foo", "@foo", "@foo"), names)
        assertEquals("lowering requests", 0L, LoweringCounters.requests.sum())
    }

    private fun assertNames(text: String, vararg expected: String, level: ElixirLanguageLevel? = null) {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)
        configure(text)

        assertEquals(text, expected.toList(), allNames())
    }

    private fun configure(text: String) {
        myFixture.configureByText("attribute.ex", text)
    }

    private fun declarations(): List<AtUnqualifiedNoParenthesesCall<*>> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, AtUnqualifiedNoParenthesesCall::class.java).toList()

    /** The name of every attribute declaration and read, in source order. */
    private fun allNames(): List<String> = runReadAction {
        PsiTreeUtil
            .findChildrenOfAnyType(myFixture.file, AtUnqualifiedNoParenthesesCall::class.java, AtOperation::class.java)
            .sortedBy(PsiElement::getTextOffset)
            .map { name(it) }
    }

    private fun name(attribute: PsiElement): String =
        when (attribute) {
            is AtUnqualifiedNoParenthesesCall<*> -> ElixirPsiImplUtil.moduleAttributeName(attribute)
            else -> (attribute as AtOperation).moduleAttributeName()
        }

    private companion object {
        const val DECOMPOSED = "cafe\u0301"
        const val PRECOMPOSED = "caf\u00e9"
    }
}
