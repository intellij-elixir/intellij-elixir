package org.elixir_lang.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_11
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_12
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_19
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_20
import org.elixir_lang.language_level.ElixirLanguageLevelResolver

/**
 * Expected messages were taken from `Code.string_to_quoted/1` on 1.11.4, 1.12.3, 1.19.5 and 1.20.4, and for sigils from
 * `Code.eval_string/1`, since the sigil macros unescape.
 */
class InvalidEscapeTest : BasePlatformTestCase() {
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

    /** 1.11's message ends with the token Elixir reports, the quote's opening delimiter. */
    fun testInvalidHexadecimalEscapeInQuotes() {
        for ((source, delimiter) in listOf(
            "\"\\x{33h}\"" to "\"",
            "'\\x{33h}'" to "'",
            "\"\"\"\n\\x{33h}\n\"\"\"" to "\"\"\"",
            "\"\"\"\na\\x\n\"\"\"" to "\"\"\"",
            "\"\"\"\n\\x{33\n\"\"\"" to "\"\"\"",
            ":\"\\x{33h}\"" to ":\"",
            "[\"\\x{33h}\": 1]" to "\"",
            "[a: \"\\x{33h}\"]" to "\"",
            "\"\\x\"" to "\"",
            "\"\\xg\"" to "\"",
            "\"\\x{}\"" to "\"",
            "\"\\x{33\"" to "\"",
            "\"\\x{zz}\"" to "\"",
            "\"\\x{1234567}\"" to "\"",
            "\"\\xg#{1}\"" to "\"",
        )) {
            assertEscapeError(V1_11, source, "\\x", MISSING_HEX_SEQUENCE + delimiter)
            assertEscapeError(V1_12, source, "\\x", INVALID_HEX_ESCAPE)
            assertEscapeError(V1_20, source, "\\x", INVALID_HEX_ESCAPE)
        }
    }

    fun testInvalidUnicodeEscapeInQuotes() {
        for (source in listOf(
            "\"\\u\"",
            "\"\\u1\"",
            "\"\\u12\"",
            "\"\\u123\"",
            "\"\\uZ\"",
            "\"\\u{}\"",
            "\"\\u{zz}\"",
            "\"\\u{33h}\"",
            "\"\\u{33\"",
            "\"\\u{1234567}\"",
        )) {
            assertEscapeError(V1_11, source, "\\u", INVALID_UNICODE_SEQUENCE)
            assertEscapeError(V1_12, source, "\\u", INVALID_UNICODE_ESCAPE)
        }
    }

    fun testValidEscapes() {
        for (languageLevel in listOf(V1_11, V1_19)) {
            for (source in listOf(
                "\"\\x1\"",
                "\"\\x12\"",
                "\"\\x123\"",
                "\"\\x{1}\"",
                "\"\\x{12345}\"",
                "\"\\u1234\"",
                "\"\\u12345\"",
                "\"\\u{1}\"",
                "\"\\u{12345}\"",
                "\"\"\"\n\\x12\\u1234\n\"\"\"",
            )) {
                assertNoErrors(languageLevel, source)
            }
        }
    }

    /**
     * `sigil_s`, `sigil_c` and `sigil_w` unescape when the code is compiled, failing with the message without the tokenizer's
     * "Syntax error after"; other sigils receive the text as written.
     */
    fun testInvalidEscapeInSigilsThatUnescape() {
        for (source in listOf(
            "~s\"\\x{33h}\"",
            "~c\"\\x{33h}\"",
            "~w\"\\x{33h}\"",
            "~s(\\xg)",
            "~s\"\"\"\n\\x{33h}\n\"\"\"",
        )) {
            assertEscapeError(V1_11, source, "\\x", MISSING_HEX_SEQUENCE)
            assertEscapeError(V1_12, source, "\\x", INVALID_HEX_ESCAPE_WHEN_COMPILED)
            assertEscapeError(V1_20, source, "\\x", INVALID_HEX_ESCAPE_WHEN_COMPILED)
        }

        assertEscapeError(V1_12, "~s\"\\u12\"", "\\u", INVALID_UNICODE_ESCAPE_WHEN_COMPILED)
    }

    fun testInvalidEscapeInOtherSigils() {
        for (languageLevel in listOf(V1_11, V1_20)) {
            for (source in listOf(
                "~r\"\\x{33h}\"",
                "~r/\\u12/",
                "~x\"\\x{33h}\"",
                "~x\"\"\"\n\\x{33h}\n\"\"\"",
                "~S\"\\x{33h}\"",
                "~C\"\\x{33h}\"",
                "~S\"\"\"\n\\x{33h}\n\"\"\"",
            )) {
                assertNoErrors(languageLevel, source)
            }
        }
    }

    /** Elixir unescapes a quoted call name only from 1.18. */
    fun testQuotedCallName() {
        assertNoErrors(V1_12, "x.\"\\x{33h}\"()")
        assertEscapeError(V1_20, "x.\"\\x{33h}\"()", "\\x", INVALID_HEX_ESCAPE)
    }

    fun testCharacterLiteral() {
        for (languageLevel in listOf(V1_11, V1_20)) {
            assertFalse("?\\x{33h} on $languageLevel", errors(languageLevel, "?\\x{33h}").isEmpty())
        }
    }

    fun testInvalidEscapeKeepsTheModuleDocumentation() {
        val source = "defmodule A do\n  @moduledoc \"\"\"\n  Doc \\x{33h} here.\n  \"\"\"\n  def f, do: 1\nend\n"

        assertEscapeError(V1_20, source, "\\x", INVALID_HEX_ESCAPE)
        assertNotNull(
            "documentation after the escape is injected",
            InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, source.indexOf("here"))
        )
    }

    fun testInvalidEscapeInDocumentationKeepsLaterDefinitions() {
        assertEscapeError(
            V1_12,
            "defmodule A do\n  @doc \"\\x{33h}\"\n  def f, do: 1\n  def g, do: 2\nend\n",
            "\\x",
            INVALID_HEX_ESCAPE
        )
    }

    private fun errors(languageLevel: ElixirLanguageLevel, source: String): List<Pair<String, String?>> {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, languageLevel)
        myFixture.configureByText("escape_${files++}.ex", source)

        return myFixture
            .doHighlighting(HighlightSeverity.ERROR)
            .map { source.substring(it.startOffset, it.endOffset) to it.description }
    }

    private fun assertEscapeError(languageLevel: ElixirLanguageLevel, source: String, text: String, message: String) {
        assertEquals("errors in $source on $languageLevel", listOf(text to message), errors(languageLevel, source))
        assertNull("parser error in $source", PsiTreeUtil.findChildOfType(myFixture.file, PsiErrorElement::class.java))
        assertEquals(
            "bad characters in $source",
            emptyList<String>(),
            PsiTreeUtil.collectElements(myFixture.file) { it.node.elementType == TokenType.BAD_CHARACTER }.map { it.text }
        )
    }

    private fun assertNoErrors(languageLevel: ElixirLanguageLevel, source: String) {
        assertEquals(
            "errors in $source on $languageLevel",
            emptyList<Pair<String, String?>>(),
            errors(languageLevel, source)
        )
    }

    private companion object {
        const val MISSING_HEX_SEQUENCE = "missing hex sequence after \\x, expected \\xHH"
        const val INVALID_UNICODE_SEQUENCE = "invalid Unicode sequence after \\u, expected \\uHHHH or \\u{H*}"
        const val INVALID_HEX_ESCAPE_WHEN_COMPILED = "invalid hex escape character, expected \\xHH where H is a hexadecimal digit"
        const val INVALID_UNICODE_ESCAPE_WHEN_COMPILED =
            "invalid Unicode escape character, expected \\uHHHH or \\u{H*} where H is a hexadecimal digit"
        const val INVALID_HEX_ESCAPE =
            "invalid hex escape character, expected \\xHH where H is a hexadecimal digit. Syntax error after: \\x"
        const val INVALID_UNICODE_ESCAPE =
            "invalid Unicode escape character, expected \\uHHHH or \\u{H*} where H is a hexadecimal digit. Syntax error after: \\u"
    }
}
