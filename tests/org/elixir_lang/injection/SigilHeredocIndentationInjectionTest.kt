package org.elixir_lang.injection

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.heex.reference.HeexHostTestCase
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir
import org.elixir_lang.psi.SigilHeredocLiteral

/**
 * The text a sigil heredoc injects, for a language that parses the text the escaper decodes (`~r`), is the text Elixir
 * decodes: each line starts after the heredoc's indentation (`elixir_tokenizer:trim_space/2`). The places stay whole
 * lines, so the injected document keeps the host's indentation where the platform reads it: Enter in the fragment
 * indents the new line as the host does.
 *
 * A template language (`~H`, `~E`, `~L`) is parsed from the window's text, which is the host's, and the platform fails to
 * reparse a fragment whose decoded chars differ from it, so a template is given the host's text as it is. That is a limit
 * of the platform, recorded here by the rows that pin it.
 */
class SigilHeredocIndentationInjectionTest : HeexHostTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } finally {
            super.tearDown()
        }
    }

    // ---- A regex is the text Elixir decodes ----

    fun testARegexIsTheTextElixirDecodes() = assertInjected("r", "a+\nb\n", "    a+", "    b")

    fun testARegexKeepsAnEmptyLineAndAnOutdentedLine() = assertInjected("r", "a+\n\nb\n", "    a+", "", " b")

    // ---- A template is the host's text ----

    fun testAHeexTemplateIsTheHostsText() = assertTemplateIsTheHostsText("H")

    fun testAnEexTemplateIsTheHostsText() = assertTemplateIsTheHostsText("E")

    private fun assertTemplateIsTheHostsText(sigil: String) =
        assertInjected(sigil, "    <div>\n      <p>a</p>\n    </div>\n", "    <div>", "      <p>a</p>", "    </div>")

    fun testATemplateDecodesNoEscape() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.13.0"))

        assertInjected("H", "    <p>\\\"\"\"</p>\n", "    <p>\\\"\"\"</p>")
    }

    // ---- A sigil the injector leaves alone, which a user's injection may fill, is the text Elixir decodes too ----

    fun testASigilTheInjectorLeavesAloneDecodesWithoutTheIndentation() {
        myFixture.configureByText("decoded.ex", source("s", listOf("    {\"a\": 1}", "", " b")))
        val sigil = sigil()
        val escaper = sigil.createLiteralTextEscaper()
        val decoded = StringBuilder()

        for (line in sigil.heredocLineList) assertTrue(escaper.decode(line.textRangeInParent, decoded))

        assertEquals("{\"a\": 1}\n\nb\n", decoded.toString())
    }

    // ---- A range inside the first line's indentation is decoded for the part of the indentation it covers ----

    fun testARangeStartingInsideTheIndentationDropsTheIndentationItCovers() =
        assertDecodedRange("abc\n") { TextRange(it.startOffset + 1, it.endOffset) }

    fun testARangeEndingInsideTheIndentationDropsTheIndentationItCovers() = assertDecodedRange("") { TextRange(it.startOffset, it.startOffset + 2) }

    fun testARangeInsideTheIndentationIsEmpty() = assertDecodedRange("") { TextRange(it.startOffset + 1, it.startOffset + 3) }

    fun testARangeStartingAfterTheIndentationIsTheText() = assertDecodedRange("abc\n") { TextRange(it.startOffset + 4, it.endOffset) }

    private fun sigil(): SigilHeredocLiteral = PsiTreeUtil.findChildrenOfType(myFixture.file, SigilHeredocLiteral::class.java).single()

    /** The only line of a `~s` heredoc closed at four spaces, so its indentation is four characters; [range] picks from it. */
    private fun assertDecodedRange(expected: String, range: (line: TextRange) -> TextRange) {
        myFixture.configureByText("range.ex", source("s", listOf("    abc")))
        val sigil = sigil()
        val decoded = StringBuilder()

        assertTrue(sigil.createLiteralTextEscaper().decode(range(sigil.heredocLineList.single().textRangeInParent), decoded))
        assertEquals(expected, decoded.toString())
    }

    private fun assertInjected(sigil: String, expected: String, vararg lines: String) {
        myFixture.configureByText("decoded.ex", source(sigil, lines.toList()))
        val manager = InjectedLanguageManager.getInstance(project)
        val texts = enumeratedInjections().map { manager.getUnescapedText(it) }.distinct()

        assertEquals("~$sigil's injected text", listOf(expected), texts)
    }

    // ---- Editing in the fragment keeps the host's indentation ----

    fun testEnterInAHeexTemplateKeepsTheHostIndent() =
        assertEnter("H", "    <div>", "      <p>a</p><caret>", "    </div>", newLine = "      ")

    fun testEnterInAnEexTemplateKeepsTheHostIndent() =
        assertEnter("E", "    <div>", "      <p>a</p><caret>", "    </div>", newLine = "      ")

    fun testEnterInARegexKeepsTheHostIndent() = assertEnter("r", "    a+", "    b<caret>", newLine = "    ")

    fun testEnterInATemplateWithADecodedEscapeLogsNoError() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.13.0"))

        assertEnter("H", "    <p>\\\"\"\"</p>", "    <b>x</b><caret>", newLine = "    ")
    }

    private fun assertEnter(sigil: String, vararg lines: String, newLine: String) {
        val before = lines.toList()
        myFixture.configureByText("enter.ex", source(sigil, before))
        myFixture.type("\n")

        val caretLine = before.indexOfFirst { it.contains("<caret>") }
        val expected = before.map { it.replace("<caret>", "") }.toMutableList().apply { add(caretLine + 1, newLine) }

        assertEquals(source(sigil, expected), InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file).text)
    }

    fun testReformattingATemplateLeavesItsLinesWhereTheyAre() =
        assertReformatLeaves("H", "    <div>", "    <p>a</p>", "    </div>")

    fun testReformattingARegexLeavesItsLinesWhereTheyAre() = assertReformatLeaves("r", "    a+", "    b")

    private fun assertReformatLeaves(sigil: String, vararg lines: String) {
        val source = source(sigil, lines.toList())

        assertEquals(source, reformattedWithoutMixFormat("reformat.ex", source))
    }

    // ---- Typing at the start of a line the platform widens ----

    fun testTypingWhitespaceAtTheStartOfAHeexLine() =
        assertTypingKeepsTheInjectedDocumentWhole("H", listOf("    <div>", "<caret>    <p>a</p>", "    </div>"))

    fun testTypingWhitespaceAfterTheIndentationOfAHeexLine() =
        assertTypingKeepsTheInjectedDocumentWhole("H", listOf("    <div>", "    <caret><p>a</p>", "    </div>"))

    fun testTypingWhitespaceAtTheStartOfARegexLine() =
        assertTypingKeepsTheInjectedDocumentWhole("r", listOf("    a+", "<caret>    b"))

    fun testTypingWhitespaceAfterTheIndentationOfARegexLine() =
        assertTypingKeepsTheInjectedDocumentWhole("r", listOf("    a+", "    <caret>b"))

    fun testTypingWhitespaceOnAnEmptyRegexLine() =
        assertTypingKeepsTheInjectedDocumentWhole("r", listOf("    a+", "<caret>", "    b"))

    private fun assertTypingKeepsTheInjectedDocumentWhole(sigil: String, lines: List<String>) =
        assertTypingWhitespaceKeepsInjectedDocumentsWhole(source(sigil, lines)) { enumeratedInjections() }

    private fun source(sigil: String, lines: List<String>): String =
        "defmodule T do\n  def f(assigns) do\n    ~$sigil\"\"\"\n" +
            lines.joinToString("") { "$it\n" } +
            "    \"\"\"\n  end\nend\n"
}
