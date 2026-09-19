package org.elixir_lang.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.ElixirFileType
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.injection.ElixirSigilInjector
import org.elixir_lang.psi.SigilLine
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir
import org.elixir_lang.settings.ElixirExperimentalSettings

/**
 * Expected outcomes were measured by running `Code.string_to_quoted/1` on every release from 1.12 to 1.20; a case whose
 * outcome changed runs on the language levels either side of that release.
 */
class UnicodeSecurityTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testBidiInCommentFrom1_13() {
        val source = "# a\u202A"

        assertNoErrors(elixir("1.12.0"), source)
        assertErrors(
            elixir("1.13.0"),
            source,
            "\u202A" to "invalid bidirectional formatting character in comment: \\u202A"
        )
    }

    fun testBidiInStringFrom1_13() {
        val source = "\"a\u202Eb\""

        assertNoErrors(elixir("1.12.0"), source)
        assertErrors(
            elixir("1.13.0"),
            source,
            "\u202E" to inString("invalid bidirectional formatting character", "202E")
        )
    }

    fun testEveryBidiCharacterInAString() {
        for (codePoint in listOf(0x202A..0x202E, 0x2066..0x2069).flatten()) {
            val character = cp(codePoint)
            val hex = "%04X".format(codePoint)

            assertErrors(
                elixir("1.20.0"),
                "\"a${character}b\"",
                character to inString("invalid bidirectional formatting character", hex)
            )
        }
    }

    fun testBidiInEveryQuotedForm() {
        for ((source, character) in listOf(
            "'a\u2066'" to "\u2066",
            "~s(a\u2067)" to "\u2067",
            "\"\"\"\na\u2068\n\"\"\"" to "\u2068",
            ":\"a\u2069\"" to "\u2069",
            "\"#{a}\u202B\"" to "\u202B",
            "\"\\\u202C\"" to "\u202C",
        )) {
            assertNoErrors(elixir("1.12.0"), source)
            assertErrors(
                elixir("1.13.0"),
                source,
                character to inString("invalid bidirectional formatting character", "%04X".format(character.codePointAt(0)))
            )
        }
    }

    fun testNeitherAnEscapeSequenceNorOtherFormatCharactersAreBidi() {
        assertNoErrors(elixir("1.20.0"), "\"\\u{202A}\"")
        assertNoErrors(elixir("1.20.0"), "\"a\u200E\"")
        assertNoErrors(elixir("1.20.0"), "\"a\u206A\"")
    }

    fun testLineBreakInCommentFrom1_19() {
        for (codePoint in listOf(0x2028, 0x2029, 0x0085, 0x000B, 0x000C)) {
            val character = cp(codePoint)
            val source = "# a$character"

            assertNoErrors(elixir("1.18.0"), source)
            assertErrors(
                elixir("1.19.0"),
                source,
                character to "invalid line break character in comment: \\u%04X".format(codePoint)
            )
        }
    }

    fun testLineBreakInStringFrom1_20() {
        for ((source, codePoint) in listOf(
            "\"a\u2028\"" to 0x2028,
            "\"a\u0085b\"" to 0x0085,
            "'a\u2029'" to 0x2029,
            "~s(a\u2028)" to 0x2028,
        )) {
            assertNoErrors(elixir("1.19.0"), source)
            assertErrors(
                elixir("1.20.0"),
                source,
                cp(codePoint) to inString("invalid line break character", "%04X".format(codePoint))
            )
        }
    }

    fun testMixedScriptIdentifierFrom1_14() {
        val source = "\u0430dmin = 1"

        assertNoErrors(elixir("1.13.0"), source)
        assertErrors(
            elixir("1.14.0"),
            source,
            "\u0430dmin" to "invalid mixed-script identifier found: \u0430dmin " +
                "(U+0430 \u0430 is Cyrillic; the rest is Latin)"
        )
    }

    fun testMixedScriptInEveryIdentifierPosition() {
        for ((source, identifier) in listOf(
            "[\u0430dmin: 1]" to "\u0430dmin",
            ":\u0430dmin" to "\u0430dmin",
            "quote do: \u0430dmin(1)" to "\u0430dmin",
            "if \u0430dmin_, do: 1" to "\u0430dmin_",
            "Foo.\u0430dmin()" to "\u0430dmin",
        )) {
            assertNoErrors(elixir("1.13.0"), source)
            assertErrors(
                elixir("1.14.0"),
                source,
                identifier to "invalid mixed-script identifier found: $identifier " +
                    "(U+0430 \u0430 is Cyrillic; the rest is Latin)"
            )
        }
    }

    fun testTrailingQuestionOrExclamationMarkIsPartOfTheIdentifier() {
        for (identifier in listOf("\u0430dmin?", "\u0430dmin!")) {
            assertErrors(
                elixir("1.14.0"),
                "$identifier = 1",
                identifier to "invalid mixed-script identifier found: $identifier " +
                    "(U+0430 \u0430 is Cyrillic; the rest is Latin)"
            )
        }
    }

    fun testCodeInjectedIntoDocumentationIsNotCompiled() {
        assertNoErrors(
            elixir("1.20.0"),
            "defmodule Sample do\n  @moduledoc \"\"\"\n      \u0430dmin = 1\n  \"\"\"\nend\n"
        )
    }

    fun testCodeInjectedIntoSigilDocumentationIsNotCompiled() {
        val source = "defmodule Sample do\n  @moduledoc ~S\"\"\"\n      \u0430dmin = 1\n  \"\"\"\nend\n"

        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.20.0"))
        myFixture.configureByText(ElixirFileType.INSTANCE, source)

        assertInjectedElixirAt(source.indexOf("dmin"))
        assertEquals(emptyList<String?>(), myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description })
    }

    /** As when a user injects Elixir into a non-template sigil, which a sigil host accepts once HTML injection is on. */
    fun testElixirInjectedDirectlyIntoASigilIsNotChecked() {
        val settings = ElixirExperimentalSettings.instance
        val originalEnableHtmlInjection = settings.state.enableHtmlInjection
        settings.state.enableHtmlInjection = true

        try {
            InjectedLanguageManager.getInstance(project).registerMultiHostInjector(
                object : MultiHostInjector {
                    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
                        val text = context.text
                        registrar
                            .startInjecting(ElixirLanguage)
                            .addPlace(null, null, context as PsiLanguageInjectionHost, TextRange(text.indexOf('(') + 1, text.lastIndexOf(')')))
                            .doneInjecting()
                    }

                    override fun elementsToInjectIn() = listOf(SigilLine::class.java)
                },
                testRootDisposable
            )
            val source = "~S(\u0430dmin = 1)"

            ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.20.0"))
            myFixture.configureByText(ElixirFileType.INSTANCE, source)

            assertInjectedElixirAt(source.indexOf("dmin"))
            assertEquals(emptyList<String?>(), myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description })
        } finally {
            settings.state.enableHtmlInjection = originalEnableHtmlInjection
        }
    }

    fun testCodeInjectedIntoATemplateSigilIsChecked() {
        assertTemplateErrors(
            myFixture,
            testRootDisposable,
            elixir("1.14.0"),
            "defmodule Test do\n  def render(assigns) do\n    ~H'''\n    <div><%= \u0430dmin %></div>\n    '''\n  end\nend\n",
            "\u0430dmin" to "invalid mixed-script identifier found: \u0430dmin (U+0430 \u0430 is Cyrillic; the rest is Latin)"
        )
    }

    fun testBidiInATemplateSigilIsReportedOnceForTheSigil() {
        val settings = ElixirExperimentalSettings.instance
        val originalEnableHtmlInjection = settings.state.enableHtmlInjection
        settings.state.enableHtmlInjection = true

        try {
            InjectedLanguageManager.getInstance(project).registerMultiHostInjector(ElixirSigilInjector(), testRootDisposable)

            assertErrors(
                elixir("1.20.0"),
                "defmodule Test do\n  def render(assigns) do\n    ~H'''\n    <div><%= x # a\u202A %></div>\n    '''\n  end\nend\n",
                "\u202A" to inString("invalid bidirectional formatting character", "202A")
            )
        } finally {
            settings.state.enableHtmlInjection = originalEnableHtmlInjection
        }
    }

    fun testElixirInAMarkdownCodeBlockIsNotChecked() {
        val source = "```elixir\n\u0430dmin = 1\n```\n"

        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.20.0"))
        myFixture.configureByText("README.md", source)

        assertInjectedElixirAt(source.indexOf("dmin"))
        assertEquals(emptyList<String?>(), myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description })
    }

    fun testTemplateInjectedIntoDocumentationIsNotCompiled() {
        for ((language, documentation) in listOf("heex" to "\"\"\"", "heex" to "~S\"\"\"", "eex" to "\"\"\"")) {
            val source =
                "defmodule Sample do\n  # language=$language\n  @doc $documentation\n  <div><%= \u0430dmin %></div>\n  \"\"\"\n  def sample, do: 1\nend\n"

            ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.20.0"))
            myFixture.configureByText(ElixirFileType.INSTANCE, source)

            assertEquals(
                "no template is injected into ${escaped(source)}, so this test checks nothing",
                language,
                InjectedLanguageManager.getInstance(project)
                    .findInjectedElementAt(myFixture.file, source.indexOf("dmin"))
                    ?.containingFile?.viewProvider?.baseLanguage?.id?.lowercase()
            )
            assertEquals(
                "errors in ${escaped(source)}",
                emptyList<String?>(),
                myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description }
            )
        }
    }

    private fun assertInjectedElixirAt(offset: Int) {
        assertEquals(
            "no Elixir is injected at $offset, so this test checks nothing",
            ElixirLanguage,
            InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, offset)?.language
        )
    }

    fun testBidiInDocumentationIsReportedOnceForTheString() {
        assertErrors(
            elixir("1.20.0"),
            "defmodule Sample do\n  @moduledoc \"\"\"\n      admin = 1 # a\u202A\n  \"\"\"\nend\n",
            "\u202A" to inString("invalid bidirectional formatting character", "202A")
        )
    }

    fun testUnderscoreSeparatesScriptsFrom1_18() {
        for (identifier in listOf("http_\u0441\u0435\u0440\u0432\u0435\u0440", "\u0441\u0435\u0440\u0432\u0435\u0440_http")) {
            val source = "$identifier = 1"

            assertErrors(
                elixir("1.17.0"),
                source,
                identifier to "invalid mixed-script identifier found: $identifier (U+0068 h, U+0074 t, U+0070 p are Latin; the rest is Cyrillic)"
            )
            assertNoErrors(elixir("1.18.0"), source)
        }
    }

    fun testLatinWithCjkNeedsAnUnderscoreFrom1_18() {
        for ((source, identifier, scripts) in listOf(
            Triple(":T\u30B7\u30E3\u30C4", "T\u30B7\u30E3\u30C4", "U+0054 T is Latin; the rest is Japanese"),
            Triple("a\u6F22\u5B57 = 1", "a\u6F22\u5B57", "U+0061 a is Latin; the rest is Han"),
        )) {
            assertNoErrors(elixir("1.17.0"), source)
            assertErrors(
                elixir("1.18.0"),
                source,
                identifier to "invalid mixed-script identifier found: $identifier ($scripts)"
            )
        }

        assertNoErrors(elixir("1.18.0"), "a_\u6F22\u5B57 = 1")
    }

    fun testCyrillicWithKatakanaIsNeverAllowed() {
        val source = "[\u0422\u30B7\u30E3\u30C4: 1]"
        val identifier = "\u0422\u30B7\u30E3\u30C4"
        val message = "invalid mixed-script identifier found: $identifier (U+0422 \u0422 is Cyrillic; the rest is Japanese)"

        assertErrors(elixir("1.14.0"), source, identifier to message)
        assertErrors(elixir("1.20.0"), source, identifier to message)
    }

    fun testQuotedAtomsAndKeysAreNotIdentifiers() {
        assertNoErrors(elixir("1.20.0"), ":\"\u0430dmin\"")
        assertNoErrors(elixir("1.20.0"), "[\"\u0430dmin\": 1]")
    }

    fun testSingleScriptIdentifiersAreAllowed() {
        for (source in listOf(
            "\u043E\u043B\u0435\u0433 = 1",
            "\u03B5\u03BB\u03BB\u03B7\u03BD = 1",
            "caf\u00E9 = 1",
            "\u5E7B\uD55C = 1",
            "c\u0327 = 1",
            "\u00B5 = 1",
        )) {
            assertNoErrors(elixir("1.14.0"), source)
            assertNoErrors(elixir("1.20.0"), source)
        }
    }

    fun testRestrictedIdentifierCharacterFrom1_14() {
        val source = "\u3164 = 1"

        assertNoErrors(elixir("1.13.0"), source)
        assertErrors(elixir("1.14.0"), source, "\u3164" to "unexpected token: \"\u3164\" (code point U+3164)")
    }

    fun testRestrictedCharacterAfterAllowedOnes() {
        assertErrors(elixir("1.14.0"), "_shib\u3164 = 1", "\u3164" to "unexpected token: \"\u3164\" (code point U+3164)")

        val boldMu = cp(0x1D6B3)
        assertNoErrors(elixir("1.13.0"), "foO$boldMu")
        assertErrors(elixir("1.14.0"), "foO$boldMu", boldMu to "unexpected token: \"$boldMu\" (code point U+1D6B3)")
    }

    fun testUnicode17RestrictsBopomofoFrom1_19() {
        val source = "\u5E7B\u3112\u3127\u3124 = 1"

        assertNoErrors(elixir("1.18.0"), source)
        assertErrors(elixir("1.19.0"), source, "\u3112" to "unexpected token: \"\u3112\" (code point U+3112)")
    }

    fun testUnicode17RestrictsLatinSmallLetterUWithDiaeresisAndGraveFrom1_19() {
        val source = ":foo\u01DC"

        assertNoErrors(elixir("1.18.0"), source)
        assertErrors(elixir("1.19.0"), source, "\u01DC" to "unexpected token: \"\u01DC\" (code point U+01DC)")
    }

    fun testRestrictionIsCheckedBeforeNormalization() {
        assertNoErrors(elixir("1.19.0"), ":foou\u0308\u0300")
    }

    fun testUnicode15RestrictsLatinCapitalLetterSmallCapitalIFrom1_15() {
        val source = "foo\uA7AE = 1"

        assertNoErrors(elixir("1.14.0"), source)
        assertErrors(elixir("1.15.0"), source, "\uA7AE" to "unexpected token: \"\uA7AE\" (code point U+A7AE)")
    }

    fun testUnicode16GivesCombiningMarksScriptsFrom1_18() {
        val source = "\u6F22\u0300 = 1"

        assertNoErrors(elixir("1.17.0"), source)
        assertErrors(
            elixir("1.18.0"),
            source,
            "\u6F22\u0300" to "invalid mixed-script identifier found: \u6F22\u0300 " +
                "(U+0300 \u0300 is Latin, Cherokee, Coptic, Cyrillic, Greek, Old_Permic, Sunuwar or Tai_Le; the rest is Han)"
        )
        assertNoErrors(elixir("1.18.0"), "e\u0300x = 1")
    }

    fun testUnicode17RestrictsLatinSmallLetterEWithBreveFrom1_19() {
        val source = "\u0115 = 1"

        assertNoErrors(elixir("1.18.0"), source)
        assertErrors(elixir("1.19.0"), source, "\u0115" to "unexpected token: \"\u0115\" (code point U+0115)")
    }

    /** A script shared by most characters is taken as the one meant, so overlapping scripts are not blamed. */
    fun testMessageNamesOnlyTheCharactersOutsideTheScriptMostShare() {
        assertNoErrors(elixir("1.17.0"), "a\u30AB\u30CA\u6F22\u5B57 = 1")
        assertErrors(
            elixir("1.18.0"),
            "a\u30AB\u30CA\u6F22\u5B57 = 1",
            "a\u30AB\u30CA\u6F22\u5B57" to "invalid mixed-script identifier found: a\u30AB\u30CA\u6F22\u5B57 (U+0061 a is Latin; the rest is Japanese)"
        )
    }

    fun testMessageCountsLatinWithAHighlyRestrictiveScriptBefore1_18() {
        assertErrors(
            elixir("1.17.0"),
            "a\u0422\u30B7\u30E3\u30C4 = 1",
            "a\u0422\u30B7\u30E3\u30C4" to "invalid mixed-script identifier found: a\u0422\u30B7\u30E3\u30C4 (U+0422 \u0422 is Cyrillic; the rest is Latin and Japanese)"
        )
        assertErrors(
            elixir("1.18.0"),
            "a\u0422\u30B7\u30E3\u30C4 = 1",
            "a\u0422\u30B7\u30E3\u30C4" to "invalid mixed-script identifier found: a\u0422\u30B7\u30E3\u30C4 " +
                "(U+0061 a is Latin; U+0422 \u0422 is Cyrillic; the rest is Japanese)"
        )
    }

    fun testMessageNamesTheCharactersOfTheChunkThatDoesNotResolveFrom1_18() {
        assertErrors(
            elixir("1.17.0"),
            "http_\u0441\u0435\u0440\u0432\u0435\u0440_a\u0436 = 1",
            "http_\u0441\u0435\u0440\u0432\u0435\u0440_a\u0436" to "invalid mixed-script identifier found: http_\u0441\u0435\u0440\u0432\u0435\u0440_a\u0436 " +
                "(U+0068 h, U+0074 t, U+0070 p, U+0061 a are Latin; the rest is Cyrillic)"
        )
        assertErrors(
            elixir("1.18.0"),
            "http_\u0441\u0435\u0440\u0432\u0435\u0440_a\u0436 = 1",
            "http_\u0441\u0435\u0440\u0432\u0435\u0440_a\u0436" to "invalid mixed-script identifier found: http_\u0441\u0435\u0440\u0432\u0435\u0440_a\u0436 " +
                "(in a\u0436, U+0436 \u0436 is Cyrillic; the rest is Latin)"
        )
    }

    fun testMessageTakesTheFirstCharactersScriptOnATie() {
        assertErrors(
            elixir("1.20.0"),
            "a\u0436 = 1",
            "a\u0436" to "invalid mixed-script identifier found: a\u0436 (U+0436 \u0436 is Cyrillic; the rest is Latin)"
        )
        assertErrors(
            elixir("1.20.0"),
            "\u0436a = 1",
            "\u0436a" to "invalid mixed-script identifier found: \u0436a (U+0061 a is Latin; the rest is Cyrillic)"
        )
    }

    private fun cp(codePoint: Int): String = String(Character.toChars(codePoint))

    private fun inString(prefix: String, hex: String): String =
        "$prefix in string: \\u$hex. If you want to use such character, use it in its escaped \\u$hex form instead"

    private fun errors(languageLevel: ElixirLanguageLevel, source: String): List<Pair<String, String?>> {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, languageLevel)
        myFixture.configureByText(ElixirFileType.INSTANCE, source)

        return myFixture
            .doHighlighting(HighlightSeverity.ERROR)
            .map { source.substring(it.startOffset, it.endOffset) to it.description }
    }

    private fun assertNoErrors(languageLevel: ElixirLanguageLevel, source: String) {
        assertEquals(
            "errors in ${escaped(source)} on $languageLevel",
            emptyList<Pair<String, String?>>(),
            errors(languageLevel, source)
        )
    }

    private fun assertErrors(
        languageLevel: ElixirLanguageLevel,
        source: String,
        vararg expected: Pair<String, String>
    ) {
        assertEquals("errors in ${escaped(source)} on $languageLevel", expected.toList(), errors(languageLevel, source))
    }
}
