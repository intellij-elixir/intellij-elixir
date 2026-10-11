package org.elixir_lang.annotator

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveState
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.reference.Callable

/**
 * The `def`, `defp`, `defmacro` and `defmacrop` of a definition clause are macro calls to `Kernel`, so they are
 * annotated like one, as `defmodule` is. A clause has no `PsiReference` of its own, because its name is a
 * declaration, so the keyword is resolved apart from it.
 */
class DefinitionKeywordTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/callback"

    override fun setUp() {
        super.setUp()
        myFixture.copyFileToProject("kernel.ex")
        myFixture.configureByText("m.ex", TEXT)
    }

    fun testDefKeywordIsAnnotated() = assertKeywordIsAnnotated("def")

    fun testDefpKeywordIsAnnotated() = assertKeywordIsAnnotated("defp")

    fun testDefmacroKeywordIsAnnotated() = assertKeywordIsAnnotated("defmacro")

    fun testDefmacropKeywordIsAnnotated() = assertKeywordIsAnnotated("defmacrop")

    fun testKeywordResolvesToTheKernelMacroOfThatName() {
        for (keyword in KEYWORDS) {
            val resolved = keywordResolution(keyword)

            assertEquals(
                "`$keyword` should resolve to Kernel.$keyword",
                listOf(keyword),
                resolved.map { CallDefinitionClause.nameArityInterval(it as Call, ResolveState.initial())?.name }.distinct()
            )
        }
    }

    fun testClauseHasNoReferenceThatWouldHideItsDeclaration() {
        for (keyword in KEYWORDS) {
            assertNull("The `$keyword` clause must not have a reference", clause(keyword).reference)
        }
    }

    private fun assertKeywordIsAnnotated(keyword: String) {
        val start = clause(keyword).functionNameElement()!!.textRange.startOffset
        val end = start + keyword.length

        val covering = myFixture.doHighlighting().filter { it.forcedTextAttributes != null && it.startOffset == start && it.endOffset == end }

        assertTrue("`$keyword` at $start..$end should be annotated", covering.isNotEmpty())
    }

    private fun keywordResolution(keyword: String): List<PsiElement> {
        val reference = Callable.definer(clause(keyword)) as PsiPolyVariantReference

        return reference.multiResolve(false).filter { it.isValidResult }.mapNotNull { it.element }
    }

    private fun clause(keyword: String): Call {
        val offset = TEXT.indexOf("$keyword ")
        assertTrue("`$keyword` not found in the fixture", offset >= 0)

        return generateSequence(myFixture.file.findElementAt(offset)) { it.parent }
            .filterIsInstance<Call>()
            .first { it.functionNameElement()?.textRange?.startOffset == offset }
    }

    private companion object {
        val KEYWORDS = listOf("def", "defp", "defmacro", "defmacrop")

        val TEXT = """
            defmodule M do
              def a, do: b()
              defp b, do: 1
              defmacro c, do: 2
              defmacrop d, do: 3
            end
        """.trimIndent() + "\n"
    }
}
