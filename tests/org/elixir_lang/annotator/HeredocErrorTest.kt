package org.elixir_lang.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.psi.HeredocLiteral
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

/** Expected messages were taken from `Code.string_to_quoted/1` on 1.11.4, 1.12.3, 1.15.8, 1.16.3 and 1.20.4. */
class HeredocErrorTest : BasePlatformTestCase() {
    private var files = 0

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Before 1.12 a terminator after content is rejected where it stands; later it is content and the heredoc runs to the end. */
    fun testTerminatorAfterContent() {
        for ((source, terminator) in listOf(
            "\"\"\"\nbar\"\"\"" to DOUBLE,
            "'''\nbar'''\n" to SINGLE,
            "~S\"\"\"\nbar\"\"\"" to DOUBLE,
            "\"\"\"\nbar\"\"\"x" to DOUBLE,
            "\"\"\"\nbar \"\"\"" to DOUBLE,
            "\"\"\"\na\nb\"\"\"" to DOUBLE,
            "\"\"\"\n  bar\"\"\"" to DOUBLE,
        )) {
            assertOnlyError(elixir("1.11.0"), source, terminator to INVALID_LOCATION + terminator)

            for (languageLevel in listOf(elixir("1.12.0"), elixir("1.20.0"))) {
                assertOnlyError(
                    languageLevel,
                    source,
                    fromOpening(source, terminator) to missingTerminator(terminator, 1)
                )
            }
        }
    }

    fun testUnterminated() {
        for ((source, terminator) in listOf(
            "~s\"\"\"\nbar" to DOUBLE,
            "\"\"\"\n" to DOUBLE,
            "'''\nbar\n" to SINGLE,
            "\"\"\"\nbar\n" to DOUBLE,
        )) {
            for (languageLevel in listOf(elixir("1.11.0"), elixir("1.12.0"), elixir("1.20.0"))) {
                assertOnlyError(
                    languageLevel,
                    source,
                    fromOpening(source, terminator) to missingTerminator(terminator, 1)
                )
            }
        }
    }

    fun testTerminatorAfterContentBeforeATerminator() {
        for (source in listOf(
            "\"\"\"\na\"\"\"\nb\n\"\"\"",
            "defmodule A do\n  @moduledoc \"\"\"\n  a\"\"\"\n  b\n  \"\"\"\nend",
        )) {
            assertOnlyError(elixir("1.11.0"), source, DOUBLE to INVALID_LOCATION + DOUBLE)
            assertNoErrors(elixir("1.12.0"), source)
        }
    }

    fun testContentAfterTheOpening() {
        for (source in listOf(
            "\"\"\"bar\n\"\"\"",
            "\"\"\"  bar\n\"\"\"",
            "\"\"\"\"\"\"",
            "\"\"\"",
        )) {
            for (languageLevel in listOf(elixir("1.11.0"), elixir("1.14.0"))) {
                assertOnlyError(languageLevel, source, DOUBLE to ZERO_OR_MORE_WHITESPACE + DOUBLE)
            }
            for (languageLevel in listOf(elixir("1.15.0"), elixir("1.20.0"))) {
                assertOnlyError(languageLevel, source, DOUBLE to ONLY_WHITESPACE + DOUBLE)
            }
        }
    }

    /**
     * Before 1.12 Elixir finds a heredoc's terminator line by line before it reads interpolations, so an unclosed one is
     * reported after the heredoc, or the heredoc's terminator is missing; from 1.12 Elixir reports what it reads inside.
     */
    fun testUnclosedInterpolation() {
        for (source in listOf(
            "\"\"\"\n#{\n\"\"\"",
            "\"\"\"\na #{b\n\"\"\"",
            "\"\"\"\n#{\na\n\"\"\"",
        )) {
            assertEquals(
                "heredoc errors in $source on 1.11.0",
                emptyList<String>(),
                heredocMessages(elixir("1.11.0"), source)
            )
            assertEquals(listOf(ZERO_OR_MORE_WHITESPACE + DOUBLE), heredocMessages(elixir("1.12.0"), source))
            assertEquals(listOf(ONLY_WHITESPACE + DOUBLE), heredocMessages(elixir("1.20.0"), source))
        }

        val unclosedToTheEnd = "\"\"\"\n#{\n"

        assertEquals(listOf(missingTerminator(DOUBLE, 1)), heredocMessages(elixir("1.11.0"), unclosedToTheEnd))
        assertEquals(emptyList<String>(), heredocMessages(elixir("1.12.0"), unclosedToTheEnd))
        assertEquals(emptyList<String>(), heredocMessages(elixir("1.20.0"), unclosedToTheEnd))
    }

    /** Before 1.12 Elixir reads a closed interpolation's text as heredoc content while it looks for the terminator. */
    fun testClosedInterpolation() {
        val openingInside = "\"#{\"\"\"x\n\"\"\"}\""

        assertOnlyError(elixir("1.11.0"), openingInside, DOUBLE to ZERO_OR_MORE_WHITESPACE + DOUBLE)
        assertOnlyError(elixir("1.20.0"), openingInside, DOUBLE to ONLY_WHITESPACE + DOUBLE)

        val heredocInside = "\"\"\"\n#{\"\"\"\nx\n\"\"\"}\n\"\"\""

        assertOnlyError(elixir("1.11.0"), heredocInside, DOUBLE to INVALID_LOCATION + DOUBLE)
        assertNoErrors(elixir("1.12.0"), heredocInside)
    }

    /**
     * Before 1.12 a string, charlist, sigil or quoted atom reads its interpolation as it reaches it, so a heredoc in one gets
     * its own error, unless a heredoc enclosing the quote fails first.
     */
    fun testUnclosedInterpolationOfALineQuote() {
        for (source in listOf(
            "\"#{\n\"\"\"\nfoo",
            "'#{\n\"\"\"\nfoo",
            "~s(#{\n\"\"\"\nfoo",
            ":\"#{\n\"\"\"\nfoo",
        )) {
            for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
                assertEquals(
                    "heredoc errors in $source on $languageLevel",
                    listOf(missingTerminator(DOUBLE, 2)),
                    heredocMessages(languageLevel, source)
                )
            }
        }

        val openingInAString = "\"#{\n\"\"\"x"

        assertEquals(listOf(ZERO_OR_MORE_WHITESPACE + DOUBLE), heredocMessages(elixir("1.11.0"), openingInAString))
        assertEquals(listOf(ONLY_WHITESPACE + DOUBLE), heredocMessages(elixir("1.20.0"), openingInAString))

        for (source in listOf(
            "\"\"\"\n#{\"#{\n\"\"\"\nfoo",
            "~s\"\"\"\n#{\"#{\n\"\"\"\nfoo",
        )) {
            assertEquals(
                "heredoc errors in $source on 1.11.0",
                emptyList<String>(),
                heredocMessages(elixir("1.11.0"), source)
            )
            assertEquals(listOf(missingTerminator(DOUBLE, 3)), heredocMessages(elixir("1.12.0"), source))
        }

        val openingInAStringInAHeredoc = "\"\"\"\n#{\"#{\n\"\"\"x"

        assertEquals(emptyList<String>(), heredocMessages(elixir("1.11.0"), openingInAStringInAHeredoc))
        assertEquals(listOf(ONLY_WHITESPACE + DOUBLE), heredocMessages(elixir("1.20.0"), openingInAStringInAHeredoc))
    }

    /** An escaped delimiter closes nothing, so a heredoc after one can still be in a heredoc's unclosed interpolation. */
    fun testUnclosedInterpolationAfterAnEscapedDelimiter() {
        for (source in listOf(
            "\"\"\"\n\\\"\"\"\n#{\n\"\"\"\nfoo",
            "\"\"\"\n#{\n\"\\\"\"\n\"\"\"\nfoo",
            "\"\"\"\n#{\n~r/\\//\n\"\"\"\nfoo",
            "\"\"\"\n\\\\\n#{\n\"\"\"\nfoo",
            "\"\"\"\n#{\"\\\\\"}\n#{\n\"\"\"\nfoo",
        )) {
            assertEquals(
                "heredoc errors in $source on 1.11.0",
                emptyList<String>(),
                heredocMessages(elixir("1.11.0"), source)
            )
            assertEquals(listOf(missingTerminator(DOUBLE, 4)), heredocMessages(elixir("1.12.0"), source))
        }

        val openingAfterAnEscapedTerminator = "\"\"\"\n\\\"\"\"\n#{\n\"\"\"x"

        assertEquals(emptyList<String>(), heredocMessages(elixir("1.11.0"), openingAfterAnEscapedTerminator))
        assertEquals(
            listOf(ONLY_WHITESPACE + DOUBLE),
            heredocMessages(elixir("1.20.0"), openingAfterAnEscapedTerminator)
        )
    }

    /** Before 1.12 a heredoc ends only at a line starting with its own terminator, and only then are its interpolations read. */
    fun testUnclosedInterpolationOfAHeredocEndingAfterTheInnerOpening() {
        for ((source, expected) in listOf(
            "'''\n#{\n\"\"\"\nfoo\n'''" to missingTerminator(DOUBLE, 3),
            "'''\n#{\n\"\"\"x\n'''" to ZERO_OR_MORE_WHITESPACE + DOUBLE,
            "\"\"\"\n#{\n'''\nfoo\n\"\"\"" to missingTerminator(SINGLE, 3),
            "'''\n#{\"#{\n\"\"\"\nfoo\n'''" to missingTerminator(DOUBLE, 3),
            "\"\"\"\n#{\n'''x\n\"\"\"" to ZERO_OR_MORE_WHITESPACE + SINGLE,
            "'''\n#{\n\"\"\"\n'''\nfoo" to missingTerminator(DOUBLE, 3),
            "'''\n#{\n\"\"\"\nfoo" to missingTerminator(SINGLE, 1),
            "'''x\n#{\n\"\"\"\nfoo\n'''" to ZERO_OR_MORE_WHITESPACE + SINGLE,
            "\"\"\"x\n#{\n'''y\n\"\"\"" to ZERO_OR_MORE_WHITESPACE + DOUBLE,
        )) {
            assertEquals(
                "heredoc errors in $source on 1.11.0",
                listOf(expected),
                heredocMessages(elixir("1.11.0"), source)
            )
        }
    }

    /** Elixir stops at content after the opening, so a terminator after content on a later line adds nothing. */
    fun testContentAfterTheOpeningThenATerminatorAfterContent() {
        val source = "\"\"\"bar\nbaz\"\"\""

        assertOnlyError(elixir("1.11.0"), source, DOUBLE to ZERO_OR_MORE_WHITESPACE + DOUBLE)
        assertOnlyError(elixir("1.20.0"), source, DOUBLE to ONLY_WHITESPACE + DOUBLE)
    }

    /** Before 1.12 a backslash takes only a backslash or quote after it, so the terminator after one stands and a line continuation ends its line. */
    fun testTerminatorAfterAnEscapedCharacter() {
        for (source in listOf(
            "~S\"\"\"\na\\\\\"\"\"\n\"\"\"",
            "\"\"\"\na\\\"\"\"\"\n\"\"\"",
        )) {
            assertOnlyError(elixir("1.11.0"), source, DOUBLE to INVALID_LOCATION + DOUBLE)
            assertNoErrors(elixir("1.12.0"), source)
        }

        for (source in listOf(
            "\"\"\"\na\\\"\"\"\n\"\"\"",
            "~S\"\"\"\na\\\"\"\"\n\"\"\"",
            "\"\"\"\nhere\\\ndoc\\\n\"\"\"",
        )) {
            assertNoErrors(elixir("1.11.0"), source)
        }
    }

    /** A last line the end of the file cuts off is one heredoc line, with no empty line after it. */
    fun testLineCutOffByTheEndOfTheFile() {
        for (source in listOf(
            "\"\"\"\nbar\"\"\"",
            "\"\"\"\nbar\n",
            "~s\"\"\"\nbar\n",
            "~S\"\"\"\nbar\n",
        )) {
            errors(elixir("1.20.0"), source)

            assertEquals(source, 1, PsiTreeUtil.findChildOfType(myFixture.file, HeredocLiteral::class.java)!!.heredocLineList.size)
        }
    }

    fun testValidHeredocs() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for (source in listOf(
                "x = \"\"\"\n  bar\n  \"\"\" <> y",
                "\"\"\"\n  \"\"\"",
                "\"\"\"  \n  bar\n  \"\"\"",
                "\"\"\"\t\nbar\n\"\"\"",
                "~S\"\"\"\nbar\n\"\"\"abc",
            )) {
                assertNoErrors(languageLevel, source)
            }
        }
    }

    /** Elixir reads the rest of the module into the documentation, so the module's missing `end` comes after its first error. */
    fun testDocumentationRunsToTheEndOfTheFile() {
        val source = "defmodule A do\n  @doc \"\"\"\n  a\"\"\"\n  def f, do: 1\n  def g, do: 2\nend"

        assertEquals(DOUBLE to INVALID_LOCATION + DOUBLE, errors(elixir("1.11.0"), source).first())
        assertEquals(
            fromOpening(source, DOUBLE) to missingTerminator(DOUBLE, 2),
            errors(elixir("1.12.0"), source).first()
        )
    }

    private fun fromOpening(source: String, terminator: String): String = source.substring(source.indexOf(terminator))

    private fun errors(languageLevel: ElixirLanguageLevel, source: String): List<Pair<String, String?>> {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, languageLevel)
        myFixture.configureByText("heredoc_${files++}.ex", source)

        return myFixture
            .doHighlighting(HighlightSeverity.ERROR)
            .map { source.substring(it.startOffset, it.endOffset) to it.description }
    }

    private fun assertOnlyError(languageLevel: ElixirLanguageLevel, source: String, expected: Pair<String, String>) {
        assertEquals("errors in $source on $languageLevel", listOf(expected), errors(languageLevel, source))
        assertNull("parser error in $source", PsiTreeUtil.findChildOfType(myFixture.file, PsiErrorElement::class.java))
    }

    /** The errors about the heredoc's own shape, leaving out grammar errors. */
    private fun heredocMessages(languageLevel: ElixirLanguageLevel, source: String): List<String> =
        errors(languageLevel, source).mapNotNull { it.second }.filter { message ->
            message.startsWith("missing terminator") ||
                message.startsWith("heredoc allows only") ||
                message.startsWith("invalid location for heredoc terminator")
        }

    private fun assertNoErrors(languageLevel: ElixirLanguageLevel, source: String) {
        assertEquals(
            "errors in $source on $languageLevel",
            emptyList<Pair<String, String?>>(),
            errors(languageLevel, source)
        )
    }

    private fun missingTerminator(terminator: String, line: Int): String =
        "missing terminator: $terminator (for heredoc starting at line $line)"

    private companion object {
        const val DOUBLE = "\"\"\""
        const val SINGLE = "'''"
        const val INVALID_LOCATION = "invalid location for heredoc terminator, please escape token or move it to its own line: "
        const val ZERO_OR_MORE_WHITESPACE = "heredoc allows only zero or more whitespace characters followed by a new line after "
        const val ONLY_WHITESPACE = "heredoc allows only whitespace characters followed by a new line after opening "
    }
}
