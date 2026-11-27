package org.elixir_lang.injection.markdown

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.HeredocLiteral
import org.intellij.plugins.markdown.lang.MarkdownLanguage
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader

/**
 * Documentation is injected as one Markdown fragment per run between code blocks, rather than one per
 * line, and every character of those fragments still names the host character it came from.
 */
class LongDocumentationInjectionTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/injection/markdown"

    private fun moduleDocHeredoc(): HeredocLiteral {
        myFixture.configureByFile("long_documentation.ex")

        return PsiTreeUtil.findChildrenOfType(myFixture.file, HeredocLiteral::class.java).first()
    }

    private fun markdownInjection(heredoc: HeredocLiteral): Pair<PsiFile, List<PsiLanguageInjectionHost.Shred>> {
        var injection: Pair<PsiFile, List<PsiLanguageInjectionHost.Shred>>? = null

        // getInjectedPsiFiles skips invalid hosts, so it cannot see every registered fragment
        InjectedLanguageManager.getInstance(myFixture.project)
            .enumerateEx(heredoc, myFixture.file, true) { injectedPsi, shreds ->
                if (injectedPsi.language == MarkdownLanguage.INSTANCE) {
                    injection = Pair(injectedPsi, shreds)
                }
            }

        assertNotNull("Markdown was not injected into the @moduledoc heredoc", injection)

        return injection!!
    }

    fun testMarkdownSurvivesAlongsideTheElixirInItsCodeBlocks() {
        moduleDocHeredoc()
        myFixture.doHighlighting()

        val documentManager = PsiDocumentManager.getInstance(myFixture.project)
        val languages = InjectedLanguageManager.getInstance(myFixture.project)
            .getCachedInjectedDocumentsInRange(myFixture.file, TextRange(0, myFixture.file.textLength))
            .mapNotNull { window -> documentManager.getPsiFile(window)?.language?.id }

        // The platform keeps only one of two injected documents whose host ranges overlap, so a Markdown
        // place reaching across a code block costs the whole documentation its Markdown
        assertContainsElements(languages, "Markdown", "Elixir")
    }

    fun testDocumentationIsOneFragmentPerRunBetweenCodeBlocks() {
        val heredoc = moduleDocHeredoc()

        assertEquals(
            "The fixture has one code block, so its documentation is the run before it and the run after; " +
                    "a fragment per heredoc line makes validating the injected document cost O(lines) on " +
                    "every access",
            2,
            markdownInjection(heredoc).second.size
        )
    }

    fun testMarkdownIsParsedWithoutTheHeredocPrefix() {
        val heredoc = moduleDocHeredoc()
        val injection = markdownInjection(heredoc)

        // The injected file's own text is the documentation verbatim - the platform patches the parsed
        // leaves back onto the host characters - so the unescaped text is what Markdown actually parsed
        assertEquals(
            EXPECTED_MARKDOWN,
            InjectedLanguageManager.getInstance(myFixture.project).getUnescapedText(injection.first)
        )
    }

    fun testDocumentationStillParsesAsMarkdownProse() {
        val heredoc = moduleDocHeredoc()
        val injection = markdownInjection(heredoc)

        val headers = PsiTreeUtil.findChildrenOfType(injection.first, MarkdownHeader::class.java)

        // Were the heredoc's own indentation reaching Markdown, the whole documentation would be one
        // indented code block and there would be no heading
        assertEquals("## A heading", headers.singleOrNull()?.text)
    }

    fun testEveryDecodedCharacterNamesItsHostCharacter() {
        val heredoc = moduleDocHeredoc()
        val hostText = heredoc.text
        val mismatches = mutableListOf<String>()

        for (shred in markdownInjection(heredoc).second) {
            val escaper = heredoc.createLiteralTextEscaper()
            val rangeInsideHost = shred.rangeInsideHost
            val decoded = StringBuilder()

            assertTrue(escaper.decode(rangeInsideHost, decoded))

            // Collect every mismatch: asserting inside the loop would report only the first one
            for (offsetInDecoded in 0 until decoded.length) {
                val offsetInHost = escaper.getOffsetInHost(offsetInDecoded, rangeInsideHost)

                if (offsetInHost < 0 ||
                    offsetInHost >= hostText.length ||
                    hostText[offsetInHost] != decoded[offsetInDecoded]
                ) {
                    mismatches.add("$rangeInsideHost decoded[$offsetInDecoded] = '${decoded[offsetInDecoded]}' " +
                            "maps to host offset $offsetInHost")
                }
            }
        }

        assertEmpty("Decoded Markdown characters that do not name their host character", mismatches)
    }

    fun testHostOffsetsNeverDecrease() {
        val heredoc = moduleDocHeredoc()
        val decreases = mutableListOf<String>()

        for (shred in markdownInjection(heredoc).second) {
            val escaper = heredoc.createLiteralTextEscaper()
            val rangeInsideHost = shred.rangeInsideHost

            assertTrue(escaper.decode(rangeInsideHost, StringBuilder()))

            // hostToInjectedUnescaped binary-searches this over the host length, which is longer than the
            // decoded text by every character dropped, so the whole domain has to be ordered
            (0..rangeInsideHost.length).map { escaper.getOffsetInHost(it, rangeInsideHost) }
                .zipWithNext()
                .forEachIndexed { offsetInDecoded, (current, next) ->
                    if (next < current) {
                        decreases.add("$rangeInsideHost [$offsetInDecoded] $current -> $next")
                    }
                }
        }

        assertEmpty("Host offsets that decrease as the decoded offset grows", decreases)
    }

    fun testAGrownRangeKeepsTheTextBeforeItsStart() {
        val heredoc = moduleDocHeredoc()
        val rangeInsideHost = markdownInjection(heredoc).second.last().rangeInsideHost
        val escaper = heredoc.createLiteralTextEscaper()
        val own = StringBuilder()
        val decoded = StringBuilder()

        // The shred's marker is greedy at both ends, so text typed at the run's start widens the range a reparse decodes
        val grown = TextRange(rangeInsideHost.startOffset - 4, rangeInsideHost.endOffset)

        assertTrue(escaper.decode(rangeInsideHost, own))
        assertTrue(escaper.decode(grown, decoded))

        assertEquals(
            "A grown range is the text typed at its start, then the place's own text",
            TextRange(grown.startOffset, rangeInsideHost.startOffset).substring(heredoc.text) + own,
            decoded.toString()
        )
    }

    fun testOffsetsAtTheEndOfALongDocumentationBlockAreExact() {
        val heredoc = moduleDocHeredoc()
        val sentence = "The final sentence of the documentation."
        val rangeInsideHost = markdownInjection(heredoc).second.last().rangeInsideHost
        val escaper = heredoc.createLiteralTextEscaper()
        val decoded = StringBuilder()

        assertTrue(escaper.decode(rangeInsideHost, decoded))

        val offsetInDecoded = decoded.indexOf(sentence)
        assertTrue("Fixture no longer ends with '$sentence'", offsetInDecoded >= 0)

        val offsetInHost = heredoc.text.indexOf(sentence)

        assertEquals(
            "The start of the last sentence of a long documentation block",
            offsetInHost,
            escaper.getOffsetInHost(offsetInDecoded, rangeInsideHost)
        )
        assertEquals(
            "The end of the last sentence of a long documentation block",
            offsetInHost + sentence.length,
            escaper.getOffsetInHost(offsetInDecoded + sentence.length, rangeInsideHost)
        )
    }

    companion object {
        private val EXPECTED_MARKDOWN = buildString {
            append("Paragraph one of the documentation.\n")
            append("\n")
            append("## A heading\n")
            append("\n")
            append("  * a list item\n")
            append("    wrapped onto a second line\n")
            append("\n")
            append("A paragraph before the code block.\n")
            append("\n")
            // The `iex> ` line keeps its prompt and code-block indent but not its code, the `[2, 4]` line
            // only its indent, and neither keeps its newline; the exception lines are kept whole
            append("    iex> ")
            append("    ")
            append("\n")
            append("    ** (ArgumentError) argument error\n")
            append("        :erlang.length(1)\n")
            append("\n")
            (1..30).forEach { append("Filler line %02d of the documentation body.\n".format(it)) }
            append("\n")
            append("The final sentence of the documentation.\n")
        }
    }
}
