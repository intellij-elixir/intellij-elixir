package org.elixir_lang.documentation

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.HeredocLiteral

/**
 * [documentationMarkdownText] of an indented `@moduledoc` heredoc is the text Elixir 1.20.4 decodes from it
 * (`Code.string_to_quoted`, run with `mise exec -- elixir`).
 */
class DocumentationMarkdownTextTest : PlatformTestCase() {
    fun testListItemsLoseTheDocIndent() = assertDocumentationText(listOf("    * one", "    * two"), "* one\n* two\n")

    fun testACodeBlockKeepsItsExtraIndent() =
        assertDocumentationText(listOf("        code", "            nested"), "    code\n        nested\n")

    fun testBlankLinesAreKept() = assertDocumentationText(listOf("", "    * one", ""), "\n* one\n\n")

    fun testAnOutdentedContentLineIsKeptWhole() =
        assertDocumentationText(listOf("    A", "  Bcd", "    C"), "A\nBcd\nC\n")

    /** The heredoc's lines are in a module whose closing `"""` is indented by 4 spaces. */
    private fun assertDocumentationText(lines: List<String>, expected: String) {
        val body = lines.joinToString("") { "$it\n" }
        val file = myFixture.configureByText("doc.ex", "defmodule Doc do\n  @moduledoc \"\"\"\n$body    \"\"\"\nend\n")
        val heredoc = PsiTreeUtil.findChildOfType(file, HeredocLiteral::class.java)!!

        assertEquals(expected, heredoc.documentationMarkdownText())
    }
}
