package org.elixir_lang.model.psi.module_attribute

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.GtduTarget
import org.elixir_lang.code_insight.completionStringsAtCaret
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.code_insight.nonDeclarationUsageCountAtCaret
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

/**
 * An attribute is the atom its name quotes to: from Elixir 1.14 `@µ` and `@μ`, and two normal forms of `@café`, are one
 * attribute, and `@ foo` is `@foo`. Before 1.14 each spelling is an attribute of its own.
 */
class ModuleAttributeNormalFormsTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    // A read finds its declaration

    fun testReadResolves() = assertResolves("@foo 7\ndef f, do: @foo<caret>")
    fun testReadWithSpaceAfterAtResolves() = assertResolves("@ foo 7\ndef f, do: @foo<caret>")
    fun testSpacedReadResolves() = assertResolves("@foo 7\ndef f, do: @ foo<caret>")
    fun testMicroSignReadResolves() = assertResolves("@µ 7\ndef f, do: @µ<caret>")
    fun testMicroSignReadResolvesBefore1_14() = assertResolves("@µ 7\ndef f, do: @µ<caret>", elixir("1.13.0"))
    fun testGreekMuReadResolvesToMicroSign() = assertResolves("@µ 7\ndef f, do: @μ<caret>")

    /** Before 1.14 `µ` and `μ` are two attributes. */
    fun testGreekMuReadDoesNotResolveToMicroSignBefore1_14() =
        assertEmpty(declarations("@µ 7\ndef f, do: @μ<caret>", elixir("1.13.0")))

    fun testDecomposedReadResolves() = assertResolves("@$DECOMPOSED 7\ndef f, do: @$DECOMPOSED<caret>")
    fun testPrecomposedReadResolvesToDecomposed() = assertResolves("@$DECOMPOSED 7\ndef f, do: @$PRECOMPOSED<caret>")
    fun testDecomposedReadResolvesToPrecomposed() = assertResolves("@$PRECOMPOSED 7\ndef f, do: @$DECOMPOSED<caret>")

    // Find Usages from the declaration

    fun testUsages() = assertUsages("@<caret>foo 7\ndef f, do: @foo", 1)
    fun testMicroSignUsages() = assertUsages("@<caret>µ 7\ndef f, do: @µ", 1)
    fun testMicroSignUsagesBefore1_14() = assertUsages("@<caret>µ 7\ndef f, do: @µ", 1, elixir("1.13.0"))
    fun testGreekMuUsagesOfMicroSign() = assertUsages("@<caret>µ 7\ndef f, do: @μ", 1)
    fun testDecomposedUsagesOfPrecomposed() = assertUsages("@<caret>$PRECOMPOSED 7\ndef f, do: @$DECOMPOSED", 1)
    fun testPrecomposedUsagesOfDecomposed() = assertUsages("@<caret>$DECOMPOSED 7\ndef f, do: @$PRECOMPOSED", 1)

    /** A re-declared attribute is one attribute, so the second declaration finds the read as well. */
    fun testMicroSignUsagesFromTheSecondDeclaration() =
        assertUsages("@µ 7\n@<caret>µ 8\ndef f, do: @µ", 2)

    fun testSpacedDeclarationUsages() = assertUsages("@ <caret>foo 7\ndef f, do: @foo", 1)

    // Completion after a typed prefix

    fun testCompletionAfterATypedPrefixOffersTheAttributes() =
        assertEquals(listOf("fob", "foo"), attributeCompletions("@foo 7\n@fob 8\ndef f, do: @fo<caret>"))

    fun testCompletionAfterATypedMicroSignOffersTheAttributes() =
        assertEquals(listOf("µ", "µb"), attributeCompletions("@µ 7\n@µb 8\ndef f, do: @µ<caret>"))

    fun testCompletionAfterATypedMicroSignBefore1_14() =
        assertEquals(listOf("µ", "µb"), attributeCompletions("@µ 7\n@µb 8\ndef f, do: @µ<caret>", elixir("1.13.0")))

    fun testRedeclaredAttributeIsOfferedOnce() =
        assertEquals(listOf("xa", "xb"), attributeCompletions("@xa 1\n@xa 2\n@xb 3\ndef f, do: @x<caret>"))

    fun testRedeclaredMicroSignAttributeIsOfferedOnce() =
        assertEquals(2, attributeCompletions("@xµ 1\n@xμ 2\n@xb 3\ndef f, do: @x<caret>").size)

    fun testRedeclaredMicroSignAttributeIsOfferedTwiceBefore1_14() =
        assertEquals(3, attributeCompletions("@xµ 1\n@xμ 2\n@xb 3\ndef f, do: @x<caret>", elixir("1.13.0")).size)

    /** The typed prefix still matches an attribute whose spelling differs from it. */
    fun testCompletionAfterATypedMicroSignOffersAnAttributeDeclaredWithTheGreekMu() =
        assertEquals(listOf("μb", "μc"), attributeCompletions("@μb 1\n@μc 2\ndef f, do: @µ<caret>"))

    fun testCompletionAfterATypedMicroSignDoesNotOfferTheGreekMuAttributeBefore1_14() =
        assertEquals(
            listOf("µc", "µd"),
            attributeCompletions("@μb 0\n@µc 1\n@µd 2\ndef f, do: @µ<caret>", elixir("1.13.0"))
        )

    fun testCompletionAfterATypedDecomposedPrefixOffersAnAttributeDeclaredPrecomposed() =
        assertEquals(
            listOf("${PRECOMPOSED}b", "${PRECOMPOSED}c"),
            attributeCompletions("@${PRECOMPOSED}b 1\n@${PRECOMPOSED}c 2\ndef f, do: @$DECOMPOSED<caret>")
        )

    fun testCompletionAfterATypedMicroSignOffersTheNearestRedeclaration() =
        assertEquals(2, attributeCompletions("@xµ 0\n@xμ 1\n@xµb 2\ndef f, do: @xµ<caret>").size)

    private fun attributeCompletions(text: String, level: ElixirLanguageLevel = elixir("1.14.0")): List<String> {
        configure(text, level)

        return myFixture.completionStringsAtCaret().orEmpty().sorted()
    }

    private fun assertResolves(text: String, level: ElixirLanguageLevel = elixir("1.14.0")) =
        assertNotEmpty(declarations(text, level))

    /** Where Go To Declaration goes from the caret. */
    private fun declarations(text: String, level: ElixirLanguageLevel): List<GtduTarget> {
        configure(text, level)

        return myFixture.gotoDeclarationTargetsAtCaret().orEmpty()
    }

    private fun assertUsages(text: String, expected: Int, level: ElixirLanguageLevel = elixir("1.14.0")) {
        configure(text, level)

        assertEquals(text, expected, myFixture.nonDeclarationUsageCountAtCaret(project))
    }

    private fun configure(text: String, level: ElixirLanguageLevel) {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, level)
        myFixture.configureByText("attribute.ex", "defmodule A do\n${text.prependIndent("  ")}\nend\n")
    }

    private companion object {
        const val DECOMPOSED = "cafe\u0301"
        const val PRECOMPOSED = "caf\u00e9"
    }
}
