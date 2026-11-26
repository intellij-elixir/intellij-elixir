package org.elixir_lang.injection.markdown

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.intellij.plugins.markdown.lang.MarkdownLanguage

/**
 * Every line of an indented documentation heredoc that Elixir keeps reaches its injected Markdown, and the
 * Markdown reads the text Elixir decodes: each line starts after the smaller of the closing delimiter's
 * indentation and its own leading spaces and tabs.
 *
 * Every expected text is the heredoc's value as Elixir 1.20.4 prints it (`mise exec -- elixir`), with an
 * interpolation evaluated to the empty string because the plugin's Markdown holds none.
 */
class IndentedDocumentationLinesInjectionTest : PlatformTestCase() {
    /** Each documentation heredoc's opening delimiter; it closes with its last three characters. */
    private val opens = listOf("\"\"\"", "'''", "~S\"\"\"", "~s\"\"\"")

    // ---- Row 1: the decoded text ----

    fun testAnEmptyLineIsKept() = assertDecodedInEveryHost(listOf("  A", "", "  B"), 2) { "A\n\nB\n" }

    fun testAWhitespaceLineShorterThanTheIndentIsAnEmptyLine() =
        assertDecodedInEveryHost(listOf("  A", " ", "  B"), 2) { "A\n\nB\n" }

    fun testAnOutdentedLineNoLongerThanThePrefixIsKept() =
        assertDecodedInEveryHost(listOf("  A", "x", "  B"), 2) { "A\nx\nB\n" }

    fun testAnOutdentedLineLongerThanThePrefixKeepsItsFirstCharacters() =
        assertDecodedInEveryHost(listOf("  A", " xy", "  B"), 2) { "A\nxy\nB\n" }

    fun testAnOutdentedLineUnderADeeperClosingDelimiterIsKept() =
        assertDecodedInEveryHost(listOf("    A", "  xy", "    B"), 4) { "A\nxy\nB\n" }

    fun testAnOutdentedLineOpeningWithAnInterpolationKeepsWhatFollows() =
        assertDecodedInEveryHost(listOf("  A", " #{x} y", "  B"), 2) { open ->
            if (open.startsWith("~S")) "A\n#{x} y\nB\n" else "A\n y\nB\n"
        }

    fun testWhitespaceLineAsLongAsTheIndentIsKept() =
        assertDecodedInEveryHost(listOf("  A", "   ", "  B"), 2) { "A\n \nB\n" }

    fun testContentPastThePrefixIsKept() = assertDecodedInEveryHost(listOf("  A", "   B"), 2) { "A\n B\n" }

    // ---- Row 2: what a user sees ----

    fun testTwoParagraphsAreTwoParagraphs() =
        assertAsInAColumnZeroHeredoc(listOf("Para one.", "", "Para two."))

    fun testAParagraphAfterAListIsNotPartOfTheLastItem() =
        assertAsInAColumnZeroHeredoc(listOf("* item one", "* item two", "", "Para after the list."))

    fun testTextOverADashesLineIsNotASetextHeading() =
        assertAsInAColumnZeroHeredoc(listOf("Some text.", "", "---", "", "After."))

    fun testEachHeaderFoldsToItsOwnSection() =
        assertAsInAColumnZeroHeredoc(listOf("## A", "", "text a", "", "## B", "", "text b"))

    // ---- Row 3: code blocks and lists ----

    fun testAnEmptyLineBetweenTwoCodeLinesKeepsTheCodeBlockWhole() {
        val withEmptyLine = listOf("  A paragraph.", "", "      iex> a = 1", "", "      iex> b = 2")
        val withWhitespaceLine = listOf("  A paragraph.", "", "      iex> a = 1", "  ", "      iex> b = 2")

        configure(source(withEmptyLine, 2))
        val emptyLineTexts = elixirTexts()

        configure(source(withWhitespaceLine, 2), "whitespace.ex")
        val whitespaceLineTexts = elixirTexts()

        assertEquals("one Elixir injection", 1, emptyLineTexts.size)
        assertEquals(whitespaceLineTexts, emptyLineTexts)
    }

    fun testAnOutdentedProseLineEndsTheCodeBlock() {
        configure(
            source(listOf("  A paragraph.", "", "      iex> a = 1", "x", "", "      iex> b = 2"), 2)
        )

        assertEquals(
            "the prose line `x` ends the first code block, so the second `iex>` line is another injection",
            listOf("a = 1\n", "b = 2\n"),
            elixirTexts()
        )
    }

    fun testAListItemsParagraphAfterABlankLineStaysMarkdownInAColumnZeroHeredoc() =
        assertListItemParagraphIsMarkdown(listOf("  * a", "", "    b"), 0, "  * a\n\n    b\n")

    /** The blank line holds as many spaces as the prefix, so it decodes to `\n`. */
    fun testAListItemsParagraphAfterABlankLineStaysMarkdownWhenTheBlankLineHoldsThePrefix() =
        assertListItemParagraphIsMarkdown(listOf("    * a", "  ", "      b"), 2, "  * a\n\n    b\n")

    fun testAListItemsParagraphAfterAnEmptyLineStaysMarkdown() =
        assertListItemParagraphIsMarkdown(listOf("    * a", "", "      b"), 2, "  * a\n\n    b\n")

    /** `100. ` puts the item's content at column 5, so after a blank line a line indented 4 is outside the item: code. */
    fun testALineBeforeAWideListItemsContentAfterABlankLineIsCode() =
        assertExampleAfterListItemIsCode(listOf("100. item", "", "    iex> 1 + 1"), 0)

    /** The same for a one-character marker indented 3: its content is at column 5, so a line indented 4 is outside the item. */
    fun testALineBeforeAnIndentedListItemsContentAfterABlankLineIsCode() =
        assertExampleAfterListItemIsCode(listOf("   * item", "", "    iex> 1 + 1"), 0)

    /** The two rows above in a heredoc closed at column 2: its empty line is a blank line too, so the item's content column decides. */
    fun testALineBeforeAWideListItemsContentAfterAnEmptyLineIsCodeInAnIndentedHeredoc() =
        assertExampleAfterListItemIsCode(listOf("  100. item", "", "      iex> 1 + 1"), 2)

    fun testALineBeforeAnIndentedListItemsContentAfterAnEmptyLineIsCodeInAnIndentedHeredoc() =
        assertExampleAfterListItemIsCode(listOf("     * item", "", "      iex> 1 + 1"), 2)

    /** Four columns past the item's content is a code block inside the item, whose code is Elixir, cut at its own indent. */
    fun testACodeBlockInAListItemAfterABlankLineIsElixir() =
        assertElixirTexts(listOf("* item", "", "      Enum.map([1], &(&1 + 1))"), 0, "Enum.map([1], &(&1 + 1))\n")

    fun testACodeBlockInAListItemAfterAnEmptyLineIsElixirInAnIndentedHeredoc() =
        assertElixirTexts(listOf("  * item", "", "        Enum.map([1], &(&1 + 1))"), 2, "Enum.map([1], &(&1 + 1))\n")

    /** The blank line between two code lines of the item's code block does not end it: both lines are one Elixir injection. */
    fun testACodeBlockInAListItemKeepsItsBlankLine() =
        assertElixirTexts(listOf("* item", "", "      a = 1", "", "      b = 2"), 0, "a = 1\nb = 2\n")

    /** A line indented to the item's content ends the code block: it is the item's paragraph. */
    fun testALineAtTheItemsContentEndsItsCodeBlock() =
        assertElixirTexts(listOf("* item", "", "      a = 1", "", "  para"), 0, "a = 1\n")

    /** Markdown's blank line holds only spaces and tabs, so a line of a no-break space is the item's paragraph, which no code follows. */
    fun testALineOfANoBreakSpaceIsNotABlankLine() =
        assertListItemParagraphIsMarkdown(
            listOf("* item", "  \u00A0", "      iex> 1 + 1"), 0, "* item\n  \u00A0\n      iex> 1 + 1\n"
        )

    /** Outside a list too: the no-break-space line is a paragraph, so it ends the code block before it. */
    fun testALineOfANoBreakSpaceEndsTheCodeBlockBeforeIt() =
        assertElixirTexts(listOf("    x = 1", "\u00A0", "    y = x"), 0, "x = 1\n", "y = x\n")

    /** A tab advances to the next multiple of 4, so `    \t` reaches column 8, past the code block's column 6: the line is its code. */
    fun testATabbedLineOfAListItemsCodeBlockStaysItsCode() =
        assertElixirTexts(listOf("* item", "", "      x = 1", "    \ty = 2"), 0, "x = 1\n\ty = 2\n")

    fun testATabbedLineOfAListItemsCodeBlockStaysItsCodeInAnIndentedHeredoc() =
        assertElixirTexts(listOf("  * item", "", "        x = 1", "      \ty = 2"), 2, "x = 1\n\ty = 2\n")

    /** A paragraph of the item between two code blocks ends the first: the examples are separate Elixir files. */
    fun testAListItemsParagraphBetweenTwoCodeBlocksSeparatesThem() =
        assertSeparateElixirTexts(listOf("* item", "", "      x = 1", "", "  Another example:", "", "      x"), 0)

    fun testAListItemsParagraphBetweenTwoCodeBlocksSeparatesThemInAnIndentedHeredoc() =
        assertSeparateElixirTexts(listOf("  * item", "", "        x = 1", "", "    Another example:", "", "        x"), 2)

    /** The paragraph also ends an `** (` exception's output, so the next block's code is Elixir, as after prose outside a list. */
    fun testAListItemsParagraphEndsAnExceptionInTheBlockBeforeIt() =
        assertElixirTexts(listOf("* item", "", "      ** (RuntimeError) boom", "", "  Another example:", "", "      x = 1"), 0, "x = 1\n")

    fun testAListItemsParagraphEndsAnExceptionInTheBlockBeforeItInAnIndentedHeredoc() =
        assertElixirTexts(listOf("  * item", "", "        ** (RuntimeError) boom", "", "    Another example:", "", "        x = 1"), 2, "x = 1\n")

    fun testANextListItemEndsAnExceptionInTheBlockBeforeIt() =
        assertElixirTexts(listOf("* item", "", "      ** (RuntimeError) boom", "", "* next", "", "      x = 1"), 0, "x = 1\n")

    private fun assertSeparateElixirTexts(lines: List<String>, closeIndent: Int) {
        configure(source(lines, closeIndent))

        assertEquals(listOf("x = 1\n", "x\n"), elixirTexts())
    }

    fun testAnIexExampleOutsideAListIsItsCode() =
        assertElixirTexts(listOf("  Paragraph.", "") + quoteExample("      "), 2, quoteExampleCode)

    /** An example in a list item's code block gives the prompt branches the code's own text, as it does outside a list. */
    fun testAnIexExampleInAListItemAfterABlankLineIsTheSameCode() =
        assertElixirTexts(listOf("  * item", "") + quoteExample("        "), 2, quoteExampleCode)

    private val quoteExampleCode = "quote do\n  unquote(\"hello\")\nend\n"

    private fun quoteExample(indent: String) =
        listOf("iex> quote do", "...>   unquote(\"hello\")", "...> end").map { "$indent$it" }

    /** The paragraph is indented past the outer item's content but short of the inner one's, so it returns to the outer item. */
    fun testAnOuterItemsParagraphAfterANestedItemStaysMarkdown() =
        assertListItemParagraphIsMarkdown(
            listOf("* outer", "", "  100. inner", "", "    outer paragraph"), 0, "* outer\n\n  100. inner\n\n    outer paragraph\n"
        )

    fun testAnOuterItemsParagraphAfterANestedItemStaysMarkdownInAnIndentedHeredoc() =
        assertListItemParagraphIsMarkdown(
            listOf("  * outer", "", "    100. inner", "", "      outer paragraph"), 2, "* outer\n\n  100. inner\n\n    outer paragraph\n"
        )

    fun testALineAtAWideListItemsContentAfterABlankLineStaysMarkdown() =
        assertListItemParagraphIsMarkdown(listOf("100. a", "", "     b"), 0, "100. a\n\n     b\n")

    /** [expected] is the Markdown's whole decoded text. */
    private fun assertListItemParagraphIsMarkdown(lines: List<String>, closeIndent: Int, expected: String) {
        configure(source(lines, closeIndent))

        val injected = enumeratedInjections()

        assertEmpty("no Elixir injection covers the item's paragraph", elixirTexts(injected))
        assertEquals(listOf(expected), markdownFiles(injected).map(::unescapedText))
    }

    /** The Elixir injections of [lines] have [expected] as their texts, in order. */
    private fun assertElixirTexts(lines: List<String>, closeIndent: Int, vararg expected: String) {
        configure(source(lines, closeIndent))

        assertEquals(expected.toList(), elixirTexts())
    }

    /** The `iex>` line after the item in [lines] is the one Elixir injection. */
    private fun assertExampleAfterListItemIsCode(lines: List<String>, closeIndent: Int) =
        assertElixirTexts(lines, closeIndent, "1 + 1\n")

    // ---- Typing at the start of a line that begins a Markdown place ----

    fun testTypingWhitespaceOnAnEmptyLineAfterACodeLine() =
        assertTypingWhitespaceKeepsTheInjectedDocumentWhole(
            listOf("  A paragraph.", "", "      iex> a = 1", "<caret>", "      iex> b = 2")
        )

    fun testTypingWhitespaceBeforeAnOutdentedLineAfterACodeLine() =
        assertTypingWhitespaceKeepsTheInjectedDocumentWhole(
            listOf("  A paragraph.", "", "      iex> a = 1", "<caret>x", "", "      iex> b = 2")
        )

    private fun assertTypingWhitespaceKeepsTheInjectedDocumentWhole(lines: List<String>) =
        assertTypingWhitespaceKeepsInjectedDocumentsWhole(source(lines, 2)) { markdownFiles() }

    // ---- Helpers ----

    /** [lines] as written between `@moduledoc <open>` and the closing delimiter at column [closeIndent]. */
    private fun source(lines: List<String>, closeIndent: Int, open: String = opens[0]): String =
        "defmodule Doc do\n  @moduledoc $open\n" +
            lines.joinToString("") { "$it\n" } +
            " ".repeat(closeIndent) + open.takeLast(3) + "\n\n  def f(x), do: 1\nend\n"

    /** A fresh file per [name]: configuring the same one again edits its document, and the injections follow the edit. */
    private fun configure(text: String, name: String = "doc.ex") {
        myFixture.configureByText(name, text)
    }

    private fun unescapedText(file: PsiFile): String = InjectedLanguageManager.getInstance(project).getUnescapedText(file)

    private fun markdownFiles(injected: List<PsiFile> = enumeratedInjections()): List<PsiFile> =
        injected.filter { it.language == MarkdownLanguage.INSTANCE }

    /** The decoded text of each Elixir file in [injected]. */
    private fun elixirTexts(injected: List<PsiFile> = enumeratedInjections()): List<String> =
        injected.filter { it.language.isKindOf(ElixirLanguage) }.map(::unescapedText)

    private fun assertDecodedInEveryHost(lines: List<String>, closeIndent: Int, expected: (open: String) -> String) {
        val problems = opens.mapNotNull { open ->
            configure(source(lines, closeIndent, open))

            val decoded = markdownFiles().map(::unescapedText)
            val expectedText = expected(open)

            if (decoded == listOf(expectedText)) null
            else "$open: expected <$expectedText> but the Markdown decoded to $decoded"
        }

        assertEmpty("The Markdown must read the text Elixir decodes:\n${problems.joinToString("\n")}", problems)
    }

    /**
     * [lines], indented by the module's two spaces, parse and fold as the same lines in a column-0 heredoc do.
     * The heredoc's own indent is left out of each text compared: it is the platform's contract that it
     * stays in the leaves, so a header's fold also runs on past the whitespace leaf that holds it; the texts are
     * compared without their trailing whitespace.
     */
    private fun assertAsInAColumnZeroHeredoc(lines: List<String>) {
        fun padded(pad: String) = lines.map { if (it.isEmpty()) it else "$pad$it" }

        configure(source(padded("  "), 2))
        val indented = elementsAndFolds()

        configure(source(padded(""), 0))
        val reference = elementsAndFolds()

        assertEquals(reference.joinToString("\n"), indented.joinToString("\n"))
    }

    private fun elementsAndFolds(): List<String> {
        val markdown = markdownFiles().single()

        fun normalized(text: String) = text.replace("\n  ", "\n").trim()

        val elements = markdown.children.flatMap { top ->
            listOf("${top.node.elementType} ${top.javaClass.simpleName} <${normalized(top.text)}>") +
                top.children.map { "  ${it.node.elementType} <${normalized(it.text)}>" }
        }

        CodeFoldingManager.getInstance(project).buildInitialFoldings(myFixture.editor)

        val document = myFixture.editor.document
        val folds = myFixture.editor.foldingModel.allFoldRegions.sortedBy { it.startOffset }.map {
            "fold <${normalized(it.placeholderText)}> <${normalized(document.getText(TextRange(it.startOffset, it.endOffset)))}>"
        }

        return elements + folds
    }
}
