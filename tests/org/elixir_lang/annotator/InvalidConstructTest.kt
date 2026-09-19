package org.elixir_lang.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.ElixirFileType
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

/**
 * Expected messages were taken from `Code.string_to_quoted/1` on 1.11.4, 1.12.3, 1.14.5, 1.15.8, 1.19.5 and 1.20.4.
 */
class InvalidConstructTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testAtomFollowedByAnAlias() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for (source in listOf(
                ":foo.Bar",
                ":\"+\".Bar",
                ":'foo'.Bar",
                "true.Bar",
                "nil.Bar",
                "false.Bar",
                "(:foo).Bar",
                "(true).Bar",
                "%:foo.Bar{}",
                ":foo.Bar.baz",
                ":foo.Bar.Baz",
                ":foo . Bar",
                ":foo.\nBar",
                "alias :foo.Bar",
            )) {
                assertErrors(languageLevel, source, "." to ATOM_FOLLOWED_BY_ALIAS)
            }
        }
    }

    fun testQualifiersThatAreNotAtoms() {
        for (source in listOf(
            ":\"a#{b}\".Bar",
            ":foo.'Bar'",
            "\"foo\".Bar",
            "1.Bar",
            "foo.Bar",
            "@foo.Bar",
            ":foo.{Bar}",
            "__MODULE__.Bar",
            "Foo.Bar",
            "true.bar",
        )) {
            assertNoErrors(elixir("1.20.0"), source)
        }
    }

    fun testAnonymousFunctionWithoutAClause() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for (source in listOf("fn 1 end", "fn x end", "fn\n  1\nend", "fn 1; 2 end")) {
                assertErrors(
                    languageLevel,
                    source,
                    "fn" to "expected anonymous functions to be defined with -> inside: 'fn'"
                )
            }
        }
    }

    fun testAnonymousFunctionsWithAClause() {
        for (source in listOf("fn -> end", "fn x -> x end", "fn 1 -> 2; 3 end", "fn x -> x; 1 end")) {
            assertNoErrors(elixir("1.20.0"), source)
        }
    }

    /** Before 1.15 Elixir reported only a syntax error whose position depends on what follows; the later message is used. */
    fun testSpaceBetweenPercentAndBrace() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.14.0"), elixir("1.15.0"), elixir("1.20.0"))) {
            for ((source, range) in listOf(
                "% {}" to "% {",
                "%\t{}" to "%\t{",
                "%  {a: 1}" to "%  {",
                "% {1, 2, 3}" to "% {",
                "%  \t{}" to "%  \t{",
            )) {
                assertErrors(languageLevel, source, range to "unexpected space between % and {")
            }
        }
    }

    fun testEscapedNewlineBetweenPercentAndBrace() {
        for ((source, range) in listOf("%\\\n{}" to "%\\\n{", "%  \\\n{}" to "%  \\\n{")) {
            assertHasError(source, range to "unexpected space between % and {")
        }
    }

    /** `?\u` is a character literal followed by `{`, a syntax error Elixir reports before any unescaping. */
    fun testEscapeInACharacterLiteralIsNotACodePointError() {
        for (source in listOf("?\\u{D800}", "?\\x{110000}")) {
            assertEquals(
                "errors in $source",
                emptyList<String>(),
                errors(elixir("1.20.0"), source)
                    .mapNotNull { (_, description) -> description?.takeIf { "code point" in it } }
            )
        }
    }

    fun testMapsAndStructsThatAreValid() {
        for (source in listOf("%{}", "% Foo{}", "%Foo {}", "%@foo{}")) {
            assertNoErrors(elixir("1.20.0"), source)
        }
    }

    fun testInvalidCodePointInAUnicodeEscape() {
        for ((source, escape, digits, decimal) in listOf(
            listOf("\"\\u{110000}\"", "\\u{110000}", "110000", "1114112"),
            listOf("\"\\u{D800}\"", "\\u{D800}", "D800", "55296"),
            listOf("\"\\uD800\"", "\\uD800", "D800", "55296"),
            listOf("'\\u{DFFF}'", "\\u{DFFF}", "DFFF", "57343"),
            listOf(":\"\\u{110000}\"", "\\u{110000}", "110000", "1114112"),
            listOf("[\"\\u{110000}\": 1]", "\\u{110000}", "110000", "1114112"),
            listOf("\"#{1}\\u{110000}\"", "\\u{110000}", "110000", "1114112"),
            listOf("\"\"\"\n\\u{110000}\n\"\"\"", "\\u{110000}", "110000", "1114112"),
            listOf("'''\n\\u{D800}\n'''", "\\u{D800}", "D800", "55296"),
            listOf("\"\\u{FFFFFF}\"", "\\u{FFFFFF}", "FFFFFF", "16777215"),
            listOf("\"\\u{d800}\"", "\\u{d800}", "d800", "55296"),
            listOf("\"\\u{0D800}\"", "\\u{0D800}", "0D800", "55296"),
        )) {
            assertErrors(elixir("1.11.0"), source, escape to "invalid or reserved Unicode code point $decimal")
            assertErrors(elixir("1.12.0"), source, escape to unicodeCodePoint(digits))
            assertErrors(elixir("1.20.0"), source, escape to unicodeCodePoint(digits))
        }
    }

    fun testInvalidCodePointInAHexadecimalEscape() {
        for ((source, escape, digits, decimal) in listOf(
            listOf("\"\\x{110000}\"", "\\x{110000}", "110000", "1114112"),
            listOf("\"\\x{dfff}\"", "\\x{dfff}", "dfff", "57343"),
        )) {
            assertErrors(elixir("1.11.0"), source, escape to "invalid or reserved Unicode code point $decimal")
            assertErrors(elixir("1.19.0"), source, escape to unicodeCodePoint(digits))
            assertErrors(
                elixir("1.20.0"),
                source,
                escape to "invalid hex escape character, expected \\xHH where H is a hexadecimal digit. Syntax error after: \\x"
            )
        }
    }

    fun testValidCodePointsAndSigils() {
        for (source in listOf(
            "\"\\u{10FFFF}\"",
            "\"\\u{FFFE}\"",
            "\"\\u{D7FF}\"",
            "\"\\u{E000}\"",
            "~s(\\u{110000})",
            "~S(\\u{110000})",
            "~r/\\u{110000}/",
        )) {
            assertNoErrors(elixir("1.20.0"), source)
        }
    }

    fun testDivisionAtom() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for ((source, token) in listOf(
                "://" to "",
                "x = ://" to "",
                "[://]" to "']'",
                "{://}" to "'}'",
                "(://)" to "')'",
                "<<://>>" to "'>>'",
                "[://, 1]" to "','",
                "%{a: ://}" to "'}'",
                "foo(://)" to "')'",
                "\"#{://}\"" to "",
                "[://\\\n]" to "']'",
                "[:// # c\n]" to "']'",
                "{:// # c\n}" to "'}'",
                ":// # c\n" to "",
                ":// # c" to "",
                "[://\n]" to "']'",
                "[://\\\n# c\n]" to "']'",
                ":// # c \\\n" to "",
            )) {
                assertErrors(languageLevel, source, "//" to "syntax error before: $token")
            }
        }

        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for (source in listOf("://\\\n", ":// \\\n", "x = ://\\\n", "://\\\n\\\n", "://\n\\\n", ":// # c\n\\\n")) {
                assertErrors(languageLevel, source, "//" to "invalid escape \\ at end of file")
            }
        }

        for (source in listOf("://\\", ":// \\")) {
            assertTrue(source, ("//" to "invalid escape \\ at end of file") in errors(elixir("1.20.0"), source))
        }

        assertNoErrors(elixir("1.20.0"), ":/")
        assertEquals(
            emptyList<Pair<String, String?>>(),
            errors(elixir("1.20.0"), ":// 1").filter { (text, _) -> text == "//" }
        )
    }

    fun testOperatorReferenceRejectedOnEveryRelease() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for (source in listOf(
                "&=>\\\n/2", "&=>/2", "&=> /2", "=>/2", "&(=>/2)", "[&=>\\\n/2]", "x = =>/2", "%{a => &=>/2}",
            )) {
                val errors = errors(languageLevel, source)
                assertEquals(
                    "$source on $languageLevel: $errors",
                    1,
                    errors.count { it == "=>" to "syntax error before: '=>'" }
                )
            }

            for (source in listOf(
                "&//\\\n/2", "&//\\\n /2", "&// \\\n/2", "//\\\n/2", "[&//\\\n/2]", "&//\\\n/1", "f(&//\\\n/2)",
                "&//\\\n/2 |> f",
            )) {
                val errors = errors(languageLevel, source)
                assertEquals(
                    "$source on $languageLevel: $errors",
                    1,
                    errors.count { it == "/" to "syntax error before: '/'" }
                )
            }
        }

        // Elixir reads `=>` in a map as the association, and `//` after `(` or without a newline differs by release.
        assertFalse(
            errors(elixir("1.11.0"), "%{a => /2}")
                .any { (_, description) -> description == "syntax error before: '=>'" }
        )
        for (source in listOf("&(//\\\n/2)", "&///2", "&// /2")) {
            assertFalse(
                source,
                errors(elixir("1.11.0"), source).any { (_, description) -> description == "syntax error before: '/'" }
            )
        }
    }

    fun testQuotedRemoteCallNameIsNotUnescapedBefore1_18() {
        assertNoErrors(elixir("1.11.0"), "a.\"\\u{110000}\"()")
    }

    fun testElixirInDocumentationIsNotChecked() {
        val source = "defmodule Sample do\n  @moduledoc \"\"\"\n      x = :foo.Bar\n  \"\"\"\nend\n"

        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.20.0"))
        myFixture.configureByText(ElixirFileType.INSTANCE, source)

        assertEquals(
            "no Elixir is injected into $source, so this test checks nothing",
            ElixirLanguage,
            InjectedLanguageManager
                .getInstance(project)
                .findInjectedElementAt(myFixture.file, source.indexOf(":foo"))
                ?.language
        )
        assertEquals(emptyList<String?>(), myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description })
    }

    private fun unicodeCodePoint(digits: String): String =
        "invalid or reserved Unicode code point \\u{$digits}. Syntax error after: \\u"

    private fun errors(languageLevel: ElixirLanguageLevel, source: String): List<Pair<String, String?>> {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, languageLevel)
        myFixture.configureByText(ElixirFileType.INSTANCE, source)

        return myFixture
            .doHighlighting(HighlightSeverity.ERROR)
            .map { source.substring(it.startOffset, it.endOffset) to it.description }
    }

    private fun assertNoErrors(languageLevel: ElixirLanguageLevel, source: String) {
        assertEquals(
            "errors in $source on $languageLevel",
            emptyList<Pair<String, String?>>(),
            errors(languageLevel, source)
        )
    }

    /** For source the parser already reports an error in. */
    private fun assertHasError(source: String, expected: Pair<String, String>) {
        val errors = errors(elixir("1.20.0"), source)

        assertEquals("$expected among the errors in $source: $errors", 1, errors.count { it == expected })
    }

    private fun assertErrors(
        languageLevel: ElixirLanguageLevel,
        source: String,
        vararg expected: Pair<String, String>
    ) {
        assertEquals("errors in $source on $languageLevel", expected.toList(), errors(languageLevel, source))
    }

    private companion object {
        const val ATOM_FOLLOWED_BY_ALIAS =
            "atom cannot be followed by an alias. If the '.' was meant to be part of the atom's name, the atom name " +
                "must be quoted. Syntax error before: '.'"
    }
}
