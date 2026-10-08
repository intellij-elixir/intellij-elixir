package org.elixir_lang.parser

import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderFactory
import com.intellij.lang.PsiParser
import com.intellij.lang.impl.PsiBuilderImpl
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.impl.source.resolve.FileContextUtil
import org.elixir_lang.ElixirLexer
import org.elixir_lang.ElixirParserDefinition
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.parser_definition.ElixirLangElixirParsingTestCase
import org.elixir_lang.parser_definition.ParsingTestCase
import java.nio.file.Path

/**
 * [TwoPassParser] has to build the tree [ElixirParser] builds, error elements and their messages included, whichever
 * pass built it, and build an error-free tree with the fast pass alone.
 */
class TwoPassParserTest : ParsingTestCase() {
    /**
     * `__MODULE__ {struct | foo: :bar}` is tried as a call without parentheses first, and its pinned `|` fails at `foo:`
     * before the alternative is abandoned: an error the struct update rolls back, so the fast pass alone suffices.
     */
    fun testAPinnedFailureRolledBackLeavesNoError() {
        assertSameTree("%__MODULE__{struct | foo: :bar}")
    }

    fun testCleanSourceParsesToTheSameTree() {
        assertSameTree("defmodule Foo do\n  @moduledoc \"Foo\"\n\n  def bar(x) when x > 0, do: x + 1\n  def bar(_), do: ~s(none)\nend\n")
    }

    /** The builder places only its first marker before leading whitespace and comments, so the root has to be it. */
    fun testLeadingCommentAndWhitespaceStayInTheFile() {
        assertSameTree("# SPDX-License-Identifier: Apache-2.0\n\n  defmodule Foo do\nend\n")
        assertSameTree("\n# only a comment\n")
        assertSameTree("  # a comment, then an error\n1 +")
    }

    /** Without a language level no token is remapped, and the rollback before the second pass skips no whitespace. */
    fun testLeadingWhitespaceStaysInTheFileWithoutALanguageLevel() {
        assertSameTree("  # a comment, then an error\n1 +", languageLevel = null)
        assertSameTree("\n  1\n", languageLevel = null)
    }

    /** The file's root takes the leading `.` as an error: an error element the fast pass itself reports. */
    fun testSourceWithAnErrorElementParsesToTheSameTree() {
        assertSameTree(".0")
    }

    /** `callParenthesesArguments` pins its `(`: a pinned section that fails. */
    fun testUnclosedCallParsesToTheSameTree() {
        assertSameTree("foo(")
    }

    /** `doBlock` reports a missing `end` through `report_error_`. */
    fun testUnclosedDoBlockParsesToTheSameTree() {
        assertSameTree("foo do\n  1\n")
    }

    /** `binaryWholeNumber` reports missing digits through `report_error_`. */
    fun testPrefixWithoutDigitsParsesToTheSameTree() {
        assertSameTree("0b")
        assertSameTree("0x")
    }

    /** `hexadecimalEscapeSequence` pins its `{`: `consumeTokens` with a pin. */
    fun testUnclosedHexadecimalEscapeParsesToTheSameTree() {
        assertSameTree("\"\\x{\"")
    }

    /** `heredoc` pins its opening and reports a missing terminator. */
    fun testUnterminatedHeredocParsesToTheSameTree() {
        assertSameTree("\"\"\"\nabc\n")
    }

    /** A trailing operator with no operand, at the end of the file. */
    fun testMissingOperandParsesToTheSameTree() {
        assertSameTree("1 +")
        assertSameTree("x = ")
    }

    /** A rule carrying `recoverWhile` that fails: `expression` inside an unclosed list and map. */
    fun testUnclosedContainersParseToTheSameTree() {
        assertSameTree("[1,")
        assertSameTree("%{")
        assertSameTree("fn ->")
        assertSameTree("~s(")
    }

    /**
     * Every file of the parsing corpus: the two parsers agree, and a file the recording parser parses without an error
     * is parsed by the fast pass alone. A rule with `recoverWhile` reports in the recording pass only when the
     * variants reach past its start, which the fast pass cannot see, so the corpus is what pins that it never does.
     */
    fun testTheCorpusParsesToTheSameTree() {
        val corpus = System.getenv(ElixirLangElixirParsingTestCase.CORPUS_ENVIRONMENT_VARIABLE)
        assertFalse("${ElixirLangElixirParsingTestCase.CORPUS_ENVIRONMENT_VARIABLE} is not set", corpus.isNullOrEmpty())
        val corpusRoot = Path.of(corpus)
        val languageLevel = buildLanguageLevel()
        val differing = ElixirLangElixirParsingTestCase.sourcePaths(corpusRoot).filter { relativePath ->
            val text = FileUtil.loadFile(corpusRoot.resolve(relativePath).toFile(), Charsets.UTF_8.name(), true).trim()
            val recording = tree(text, ElixirParser(), languageLevel)
            recording != tree(text, TwoPassParser(), languageLevel) ||
                    !recording.contains(ERROR_ELEMENT) && recording != fastTree(text, languageLevel)
        }
        assertEquals(emptyList<String>(), differing)
    }

    /**
     * The recording pass is only needed after an error, so a file whose last parse had one starts with it, and goes
     * back to the fast pass once a parse has none.
     */
    fun testAFileWhoseLastParseHadErrorsStartsWithTheRecordingPass() {
        val file = createPsiFile("file", "foo(")
        ensureParsed(file)
        assertTrue(TwoPassParser.lastParseHadErrors(file.virtualFile))

        assertEquals(listOf(RECORDING), passes(file, "foo("))
        assertTrue(TwoPassParser.lastParseHadErrors(file.virtualFile))

        assertEquals(listOf(RECORDING), passes(file, "foo()"))
        assertFalse(TwoPassParser.lastParseHadErrors(file.virtualFile))

        assertEquals(listOf(FAST), passes(file, "foo()"))
        assertEquals(listOf(FAST, RECORDING), passes(file, "foo("))
        assertTrue(TwoPassParser.lastParseHadErrors(file.virtualFile))
    }

    /**
     * Reparses [file] as [text] and answers the passes that ran: [ElixirParserUtil.FAST_PASS] is set only when the fast
     * pass runs, and cleared when the recording pass follows it.
     */
    private fun passes(file: PsiFile, text: String): List<String> {
        val definition = ElixirParserDefinition()
        val builder = builder(definition, text, buildLanguageLevel())
        builder.putUserData(FileContextUtil.CONTAINING_FILE_KEY, file)
        TwoPassParser().parse(definition.fileNodeType, builder)

        return when (builder.getUserData(ElixirParserUtil.FAST_PASS)) {
            true -> listOf(FAST)
            false -> listOf(FAST, RECORDING)
            null -> listOf(RECORDING)
        }
    }

    private fun assertSameTree(text: String, languageLevel: ElixirLanguageLevel? = buildLanguageLevel()) {
        val recording = tree(text, ElixirParser(), languageLevel)
        assertEquals(text, recording, tree(text, TwoPassParser(), languageLevel))

        if (!recording.contains(ERROR_ELEMENT)) {
            assertEquals("$text by the fast pass alone", recording, fastTree(text, languageLevel))
        }
    }

    private fun tree(text: String, parser: PsiParser, languageLevel: ElixirLanguageLevel?): String {
        val definition = ElixirParserDefinition()
        val builder = builder(definition, text, languageLevel)

        // the recording parser is run bare; the two-pass parser installs the remapper itself
        if (parser is ElixirParser) {
            builder.setTokenTypeRemapper(ElixirParserUtil.remapper(builder))
        }

        return DebugUtil.nodeTreeToString(parser.parse(definition.fileNodeType, builder), false)
    }

    /** The fast pass alone, which must leave no error in the tree. */
    private fun fastTree(text: String, languageLevel: ElixirLanguageLevel?): String {
        val definition = ElixirParserDefinition()
        val builder = builder(definition, text, languageLevel) as PsiBuilderImpl
        builder.setTokenTypeRemapper(ElixirParserUtil.remapper(builder))

        builder.putUserData(ElixirParserUtil.FAST_PASS, true)
        ElixirParser().parseLight(definition.fileNodeType, builder)
        assertFalse("$text: the fast pass left an error in the tree", builder.hasErrorsAfter(builder.productions.first() as PsiBuilder.Marker))

        return DebugUtil.nodeTreeToString(builder.treeBuilt, false)
    }

    private fun builder(definition: ElixirParserDefinition, text: String, languageLevel: ElixirLanguageLevel?): PsiBuilder {
        val builder = PsiBuilderFactory.getInstance().createBuilder(definition, ElixirLexer(), text)
        builder.putUserData(ElixirParserUtil.LANGUAGE_LEVEL, languageLevel)

        return builder
    }

    private companion object {
        const val ERROR_ELEMENT = "PsiErrorElement"
        const val FAST = "fast"
        const val RECORDING = "recording"
    }
}
