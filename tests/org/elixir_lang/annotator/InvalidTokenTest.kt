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
 * Expected messages were taken from `Code.string_to_quoted/1` on 1.11.4, 1.12.3, 1.13.4, 1.14.5 and 1.20.4. An error's
 * range is the word, or the number and what follows it, that Elixir rejects.
 */
class InvalidTokenTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** A word may start with a non-ASCII uppercase letter only as an atom or a keyword key; Elixir names its column. */
    fun testNonAsciiUppercaseLetterStartsOnlyAnAtomOrKeywordKey() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.13.0"), elixir("1.14.0"), elixir("1.20.0"))) {
            assertErrors(languageLevel, "\u00C9x = 1", "\u00C9" to unexpectedToken("\u00C9", 1, "00C9"))
            assertErrors(languageLevel, "\u00C9 = 1", "\u00C9" to unexpectedToken("\u00C9", 1, "00C9"))
            assertErrors(languageLevel, "a.\u00C9x", "\u00C9" to unexpectedToken("\u00C9", 3, "00C9"))
            assertErrors(languageLevel, "&\u00C9x/1", "\u00C9" to unexpectedToken("\u00C9", 2, "00C9"))
            assertErrors(languageLevel, "\t\u00C9x = 1", "\u00C9" to unexpectedToken("\u00C9", 2, "00C9"))
            assertErrors(languageLevel, "\u0394 = 1", "\u0394" to unexpectedToken("\u0394", 1, "0394"))
            assertErrors(languageLevel, "\u01C5x = 1", "\u01C5" to unexpectedToken("\u01C5", 1, "01C5"))
            assertNoErrors(languageLevel, ":\u00C9x")
            assertNoErrors(languageLevel, "[\u00C9x: 1]")
            assertNoErrors(languageLevel, "x\u00C9 = 1")
        }
    }

    /** Before 1.14 a restricted uppercase letter still starts an atom or keyword key; from 1.14 it starts nothing. */
    fun testRestrictedUppercaseLetterStartsNoWord() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.13.0"))) {
            assertErrors(
                languageLevel,
                "\uD835\uDCB3 = 1",
                "\uD835\uDCB3" to unexpectedToken("\uD835\uDCB3", 1, "****")
            )
            assertNoErrors(languageLevel, ":\uD835\uDCB3")
            assertNoErrors(languageLevel, "[\uD835\uDCB3: 1]")
        }

        for (languageLevel in listOf(elixir("1.14.0"), elixir("1.20.0"))) {
            assertErrors(
                languageLevel,
                "\uD835\uDCB3 = 1",
                "\uD835\uDCB3" to unexpectedToken("\uD835\uDCB3", 1, "****")
            )
        }
    }

    /** Elixir reads a word through `@` before it checks the first letter, unless the letter is restricted from 1.14. */
    fun testAtInAWordStartingWithALetterThatStartsOnlyAnAtom() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.13.0"), elixir("1.14.0"), elixir("1.20.0"))) {
            assertErrors(languageLevel, "\u00C9x@y = 1", "\u00C9" to invalidCharacter("@", "0040", "atom", "\u00C9x@y"))
            assertErrors(languageLevel, "x = \u00C9x", "\u00C9" to unexpectedToken("\u00C9", 5, "00C9"))
            assertErrors(languageLevel, "x = \u00C9x@y", "\u00C9" to invalidCharacter("@", "0040", "atom", "\u00C9x@y"))
        }

        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.13.0"))) {
            assertErrors(languageLevel, "\u01C5x@y = 1", "\u01C5" to invalidCharacter("@", "0040", "atom", "\u01C5x@y"))
        }

        for (languageLevel in listOf(elixir("1.14.0"), elixir("1.20.0"))) {
            assertErrors(languageLevel, "\u01C5x@y = 1", "\u01C5" to unexpectedToken("\u01C5", 1, "01C5"))
        }
    }

    fun testNonAsciiCharacterInAnAlias() {
        val alias = "Fo\u00F3"

        assertErrors(elixir("1.13.0"), alias, alias to invalidCharacter("\u00F3", "00F3", "alias$ASCII_ONLY", alias))
        assertErrors(
            elixir("1.14.0"),
            alias,
            alias to invalidCharacter("\u00F3", "00F3", "alias$WITHOUT_PUNCTUATION", alias)
        )
    }

    /** From 1.14 Elixir puts an alias in NFC before checking it, so it names the composed character. */
    fun testAliasNamesItsCharacterInNfcFrom1_14() {
        for ((alias, composed, codePoint) in listOf(
            Triple("C\u0327", "\u00C7", "00C7"),
            Triple("E\u0301x", "\u00C9x", "00C9"),
        )) {
            assertErrors(
                elixir("1.14.0"),
                alias,
                alias to invalidCharacter(composed.substring(0, 1), codePoint, "alias$WITHOUT_PUNCTUATION", composed),
            )
        }

        // Without a composed form an alias is already in NFC, and its combining mark is the character named.
        val alias = "Fo\u0327o"
        assertErrors(elixir("1.13.0"), alias, alias to invalidCharacter("\u0327", "0327", "alias$ASCII_ONLY", alias))
        assertErrors(
            elixir("1.14.0"),
            alias,
            alias to invalidCharacter("\u0327", "0327", "alias$WITHOUT_PUNCTUATION", alias)
        )
    }

    fun testAliasEndingInPunctuation() {
        for ((alias, character, codePoint) in listOf(Triple("Ola?", "?", "003F"), Triple("Foo!", "!", "0021"))) {
            assertErrors(elixir("1.13.0"), alias, alias to invalidCharacter(character, codePoint, "alias", alias))
            assertErrors(
                elixir("1.14.0"),
                alias,
                alias to invalidCharacter(character, codePoint, "alias$WITHOUT_PUNCTUATION", alias)
            )
        }
    }

    fun testAliasNamesTheFirstCharacterBelowAFrom1_14() {
        assertErrors(elixir("1.13.0"), "Foo1?", "Foo1?" to invalidCharacter("?", "003F", "alias", "Foo1?"))
        assertErrors(
            elixir("1.14.0"),
            "Foo1?",
            "Foo1?" to invalidCharacter("1", "0031", "alias$WITHOUT_PUNCTUATION", "Foo1?")
        )

        val alias = "Fo1\u00F3"

        assertErrors(elixir("1.13.0"), alias, alias to invalidCharacter("\u00F3", "00F3", "alias$ASCII_ONLY", alias))
        assertErrors(
            elixir("1.14.0"),
            alias,
            alias to invalidCharacter("1", "0031", "alias$WITHOUT_PUNCTUATION", alias)
        )
    }

    /** Erlang's `~4.16.0B` fills a code point that needs more than four digits with stars. */
    fun testCodePointAboveFFFFInAnAlias() {
        val character = "\uD840\uDC00"
        val alias = "Foo$character"

        assertHasError(elixir("1.13.0"), alias, alias to invalidCharacter(character, "****", "alias$ASCII_ONLY", alias))
        assertHasError(
            elixir("1.17.0"),
            alias,
            alias to invalidCharacter(character, "****", "alias$WITHOUT_PUNCTUATION", alias)
        )
    }

    fun testInvalidAliasAfterADotOrInAStruct() {
        assertErrors(
            elixir("1.20.0"),
            "Foo.B\u00E1r",
            "B\u00E1r" to invalidCharacter("\u00E1", "00E1", "alias$WITHOUT_PUNCTUATION", "B\u00E1r")
        )
        assertErrors(
            elixir("1.20.0"),
            "foo.Bar?",
            "Bar?" to invalidCharacter("?", "003F", "alias$WITHOUT_PUNCTUATION", "Bar?")
        )
        assertErrors(
            elixir("1.20.0"),
            "%Foo?{}",
            "Foo?" to invalidCharacter("?", "003F", "alias$WITHOUT_PUNCTUATION", "Foo?")
        )
        assertErrors(
            elixir("1.20.0"),
            "alias Foo?, as: Bar",
            "Foo?" to invalidCharacter("?", "003F", "alias$WITHOUT_PUNCTUATION", "Foo?")
        )
    }

    fun testValidAliasesAndTheirKeywordAndAtomForms() {
        for (source in listOf("Foo_bar1", "Foo.bar?", ":Foo?", "[Foo?: 1]", "[Fo\u00F3: 1]", ":Fo\u00F3")) {
            assertNoErrors(elixir("1.20.0"), source)
        }
    }

    fun testAtInAnIdentifierStartingWithALetterAboveFFFF() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.17.0"))) {
            for (word in listOf("\uD840\uDC00@y", "x\uD840\uDC00@y")) {
                assertHasError(languageLevel, word, word to invalidCharacter("@", "0040", "identifier", word))
            }
        }
    }

    fun testAtInAnIdentifier() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for ((source, word) in listOf(
                "foo@bar" to "foo@bar",
                "x.foo@bar" to "foo@bar",
                "@foo@bar" to "foo@bar",
                "foo@bar?" to "foo@bar?",
                "\"#{foo@bar}\"" to "foo@bar",
                "foo@@bar" to "foo@@bar",
                "foo@1" to "foo@1",
                "def foo@bar, do: 1" to "foo@bar",
            )) {
                assertErrors(languageLevel, source, word to invalidCharacter("@", "0040", "identifier", word))
            }
        }
    }

    fun testAtInAnAliasIsNamedAsSuch() {
        for (alias in listOf("Foo@bar", "Foo@bar?")) {
            assertHasError(elixir("1.20.0"), alias, alias to invalidCharacter("@", "0040", "alias", alias))
        }
    }

    fun testAtEndingAnIdentifier() {
        assertHasError(elixir("1.20.0"), "foo@ bar", "foo@" to invalidCharacter("@", "0040", "identifier", "foo@"))
    }

    fun testAtOutsideAWord() {
        for (source in listOf(
            ":foo@bar",
            "[foo@bar: 1]",
            "foo?@bar",
            "foo @bar",
            "foo @ bar",
            "...@foo",
            "@foo",
        )) {
            assertNoErrors(elixir("1.20.0"), source)
        }
    }

    fun testLetterAfterANumber() {
        for ((source, character, number, elixir11Token) in listOf(
            listOf("1var", "v", "1", "var"),
            listOf("123_456_foo", "_", "123_456", "'_foo'"),
            listOf("1e10", "e", "1", "e10"),
            listOf("1.5foo", "f", "1.5", "foo"),
            listOf("123Foo", "F", "123", "'Foo'"),
            listOf("1.0e10foo", "f", "1.0e10", "foo"),
            listOf("1_000a", "a", "1_000", "a"),
            listOf("1.0ex", "e", "1.0", "ex"),
            listOf("0o9", "o", "0", "o9"),
            listOf("0b2", "b", "0", "b2"),
        )) {
            assertErrors(elixir("1.11.0"), source, source to "syntax error before: $elixir11Token")
            assertErrors(elixir("1.12.0"), source, source to afterNumber1_12(character, number))
            assertErrors(elixir("1.13.0"), source, source to afterNumber1_12(character, number))
            assertErrors(elixir("1.14.0"), source, source to afterNumber(character, number))
            assertErrors(elixir("1.20.0"), source, source to afterNumber(character, number))
        }
    }

    fun testLetterAfterANumberInAWordEndingInPunctuation() {
        assertHasError(elixir("1.11.0"), "1var?", "1var?" to "syntax error before: 'var?'")
        assertHasError(elixir("1.20.0"), "1var?", "1var?" to afterNumber("v", "1"))
    }

    fun testWhatFollowsANumberInAnotherBase() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            assertErrors(languageLevel, "0x1Fg", "0x1Fg" to "syntax error before: g")
            assertErrors(languageLevel, "0x1F_g", "0x1F_g" to "syntax error before: '_g'")
            assertErrors(languageLevel, "0b12", "0b12" to "syntax error before: \"2\"")
        }

        assertErrors(elixir("1.11.0"), "0b12a", "0b12a" to "syntax error before: \"2\"")
        assertErrors(elixir("1.20.0"), "0b12a", "0b12a" to afterNumber("a", "2"))
    }

    fun testRejectedWordAfterANumberReportsTheWordsOwnError() {
        assertHasError(
            elixir("1.11.0"),
            "1foo@bar",
            "1foo@bar" to invalidCharacter("@", "0040", "identifier", "foo@bar")
        )
        assertHasError(
            elixir("1.11.0"),
            "1foo:bar",
            "1foo:" to "keyword argument must be followed by space after: foo:"
        )
        assertHasError(elixir("1.11.0"), "1__block__", "1__block__" to "reserved token: __block__")
        assertHasError(elixir("1.11.0"), "1Foo?", "1Foo?" to invalidCharacter("?", "003F", "alias", "Foo?"))
        assertHasError(elixir("1.11.0"), "0b1Foo?", "0b1Foo?" to invalidCharacter("?", "003F", "alias", "Foo?"))
        assertHasError(
            elixir("1.20.0"),
            "0b1Foo?",
            "0b1Foo?" to invalidCharacter("?", "003F", "alias$WITHOUT_PUNCTUATION", "Foo?")
        )

        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            assertHasError(
                languageLevel,
                "0b1foo:bar",
                "0b1foo:" to "keyword argument must be followed by space after: foo:"
            )
            assertHasError(languageLevel, "0b1__block__", "0b1__block__" to "reserved token: __block__")
            assertHasError(
                languageLevel,
                "0x1Ffoo@bar",
                "0x1Ffoo@bar" to invalidCharacter("@", "0040", "identifier", "oo@bar")
            )
        }
    }

    fun testRejectedWordAfterDigitsFollowingABaseNumberBefore1_12() {
        assertHasError(
            elixir("1.11.0"),
            "0b12foo@bar",
            "0b12foo@bar" to invalidCharacter("@", "0040", "identifier", "foo@bar")
        )
        assertHasError(
            elixir("1.11.0"),
            "0b12foo:bar",
            "0b12foo:" to "keyword argument must be followed by space after: foo:"
        )
        assertHasError(elixir("1.11.0"), "0b12Foo?", "0b12Foo?" to invalidCharacter("?", "003F", "alias", "Foo?"))
        assertHasError(elixir("1.11.0"), "0b12__block__", "0b12__block__" to "reserved token: __block__")
        assertHasError(elixir("1.11.0"), "0b12if", "0b12if" to "syntax error before: \"2\"")
    }

    fun testLatin1WordAfterANumberIsNotQuoted() {
        assertHasError(elixir("1.11.0"), "1fo\u00E9", "1fo\u00E9" to "syntax error before: fo\u00E9")

        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            assertHasError(languageLevel, "0b1fo\u00E9", "0b1fo\u00E9" to "syntax error before: fo\u00E9")
            assertHasError(languageLevel, "0b1fo\u0141", "0b1fo\u0141" to "syntax error before: 'fo\u0141'")
        }
    }

    fun testKeywordWithASpaceAfterANumber() {
        assertHasError(elixir("1.11.0"), "1foo: 1", "1foo:" to "syntax error before: 'foo:'")

        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            assertHasError(languageLevel, "0b1foo: 1", "0b1foo:" to "syntax error before: 'foo:'")
            assertHasError(languageLevel, "0b1Foo: 1", "0b1Foo:" to "syntax error before: 'Foo:'")
            assertHasError(languageLevel, "0b1foo:\n1", "0b1foo:" to "syntax error before: 'foo:'")
            assertHasError(languageLevel, "0b1foo@bar: 1", "0b1foo@bar:" to "syntax error before: 'foo@bar:'")
        }
    }

    fun testWordsThatCannotFollowANumber() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for ((source, range, token) in listOf(
                Triple("0b1nil", "0b1nil", "nil"),
                Triple("0o7true", "0o7true", "true"),
                Triple("0b1false", "0b1false", "false"),
                Triple("0b1fn -> 1 end", "0b1fn", "fn"),
                Triple("0b1not x", "0b1not", "'not'"),
            )) {
                assertHasError(languageLevel, source, range to "syntax error before: $token")
            }
        }

        for ((source, range, token) in listOf(
            Triple("1true", "1true", "true"),
            Triple("1nil", "1nil", "nil"),
            Triple("1fn -> 1 end", "1fn", "fn"),
            Triple("1not x", "1not", "'not'"),
            Triple("1not\nin x", "1not", "'not'"),
            Triple("1not in? x", "1not", "'not'"),
        )) {
            assertHasError(elixir("1.11.0"), source, range to "syntax error before: $token")
        }

        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for (source in listOf("0b1not in? x", "0b1not in: 1", "0b1not inx")) {
                assertHasError(languageLevel, source, "0b1not" to "syntax error before: 'not'")
            }
        }
    }

    /** Before 1.12 Elixir reads a reserved word followed by `::` as a keyword key, so `in::` makes no `not in`. */
    fun testNotInBeforeATypeOperatorFrom1_12() {
        assertHasError(elixir("1.11.0"), "0b1not in::x", "0b1not" to "syntax error before: 'not'")

        for (languageLevel in listOf(elixir("1.12.0"), elixir("1.20.0"))) {
            assertNoInvalidTokenErrors(languageLevel, "0b1not in::x")
        }
    }

    /** A letter outside the Basic Multilingual Plane continues `in`, so what follows the `not` is no `not in`. */
    fun testLetterOutsideTheBmpContinuesIn() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            assertHasError(languageLevel, "0b1not in\uD835\uDCB3", "0b1not" to "syntax error before: 'not'")
        }
    }

    fun testWordsThatCanFollowANumber() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for (source in listOf("0b1and 2", "0b1or 2", "0b1in x", "x = 0b1when true", "0b1not in x")) {
                assertNoInvalidTokenErrors(languageLevel, source)
            }
        }

        for (source in listOf("1or 2", "1in x", "x = 1when true", "1not in x", "1not  in x", "1not \\\nin x")) {
            assertNoInvalidTokenErrors(elixir("1.11.0"), source)
        }

        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            assertNoInvalidTokenErrors(languageLevel, "0b1not \\\nin x")
        }
    }

    fun testDoKeywordAfterANumber() {
        assertHasError(elixir("1.11.0"), "1do: 2", "1do:" to DO_KEYWORD)

        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for (source in listOf("0b1do: 2", "0b1do:\t2", "0b1do:\n2")) {
                assertHasError(languageLevel, source, "0b1do:" to DO_KEYWORD)
            }
        }
    }

    fun testErlangReservedWordAfterANumberIsQuoted() {
        assertErrors(elixir("1.11.0"), "1if", "1if" to "syntax error before: 'if'")
        assertErrors(elixir("1.11.0"), "1.0case", "1.0case" to "syntax error before: 'case'")
        assertErrors(elixir("1.20.0"), "0b1if", "0b1if" to "syntax error before: 'if'")
    }

    /** The OTP running Elixir decides, not the Elixir release or the OTP its build targeted. */
    fun testMaybeIsQuotedFromOtp27() {
        assertErrors(elixir("1.11.4", otp = "24.3.4.6"), "0b1maybe", "0b1maybe" to "syntax error before: maybe")
        assertErrors(elixir("1.18.4", otp = "26.2.5.21"), "0b1maybe", "0b1maybe" to "syntax error before: maybe")
        assertErrors(elixir("1.18.4", otp = "27.3.4"), "0b1maybe", "0b1maybe" to "syntax error before: 'maybe'")
        assertErrors(elixir("1.20.0", otp = "27.0"), "0b1maybe", "0b1maybe" to "syntax error before: 'maybe'")
    }

    /** Before `/`, an operator is an identifier to Elixir's tokenizer, and the lexer reads it the same way. */
    fun testOperatorReferencedBeforeASlashIsNotAWord() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for (source in listOf("&@/1", "&@/2", "x = &@/1")) {
                assertNoInvalidTokenErrors(languageLevel, source)
            }
        }

        assertNoInvalidTokenErrors(elixir("1.20.0"), "@/1")
    }

    fun testKeywordAfterANumberFrom1_12() {
        assertNoErrors(elixir("1.11.0"), "1and 2")
        assertErrors(elixir("1.12.0"), "1and 2", "1and" to afterNumber1_12("a", "1"))
        assertErrors(elixir("1.20.0"), "1and 2", "1and" to afterNumber("a", "1"))
    }

    fun testValidNumbers() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for (source in listOf(
                "1_000",
                "1.0e10",
                "1.0E-5",
                "0x1F",
                "0b101",
                "0o17",
                "1..2",
                "1.Bar",
                "x1 = 1",
                "?a",
                "[1, 2]",
                "1 + 2",
            )) {
                assertNoErrors(languageLevel, source)
            }
        }
    }

    fun testReservedToken() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for ((source, word) in listOf(
                "__block__" to "__block__",
                "__aliases__(1)" to "__aliases__",
                "x.__block__" to "__block__",
                "def __block__, do: 1" to "__block__",
                "__block__ 1" to "__block__",
            )) {
                assertErrors(languageLevel, source, word to "reserved token: $word")
            }
        }
    }

    fun testReservedNamesInOtherForms() {
        for (source in listOf(":__block__", "[__block__: 1]", "__block__?", "__MODULE__", "__cursor__", "foo.\"__block__\"")) {
            assertNoErrors(elixir("1.20.0"), source)
        }
    }

    fun testKeywordWithoutSpaceAfterTheColon() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            for ((source, keyword) in listOf(
                "foo:bar" to "foo:",
                "[foo:bar]" to "foo:",
                "f foo:bar" to "foo:",
                "foo?:bar" to "foo?:",
                "x.foo:bar" to "foo:",
                "@foo:bar" to "foo:",
                "foo:'bar'" to "foo:",
                "[foo:\"bar\"]" to "foo:",
                "__block__:x" to "__block__:",
            )) {
                assertErrors(
                    languageLevel,
                    source,
                    keyword to "keyword argument must be followed by space after: $keyword"
                )
            }
        }
    }

    fun testKeywordWithoutSpaceAfterAReservedWordOrAnAt() {
        for ((source, keyword) in listOf(
            "do:bar" to "do:",
            "if x, do:1" to "do:",
            "true:bar" to "true:",
            "and:1" to "and:",
            "foo@bar:1" to "foo@bar:",
            "Foo?:bar" to "Foo?:",
        )) {
            assertHasError(
                elixir("1.20.0"),
                source,
                keyword to "keyword argument must be followed by space after: $keyword"
            )
        }
    }

    fun testKeywordsAndColonsThatAreValid() {
        for (source in listOf(
            "foo::bar",
            "[foo:\n1]",
            "[foo:\t1]",
            "foo :bar",
            "[foo: 1]",
            "[\"foo\": 1]",
            "%{foo: 1}",
            "if x, do: 1",
        )) {
            assertNoErrors(elixir("1.20.0"), source)
        }
    }

    fun testQuotedTextAndCommentsAreNotWords() {
        for (source in listOf(
            "\"foo@bar 1var __block__ foo:bar Foo?\"",
            "# foo@bar 1var __block__ foo:bar Foo?",
            "~w(foo@bar 1var __block__ foo:bar Foo?)",
            "'Foo?'",
        )) {
            assertNoErrors(elixir("1.20.0"), source)
        }
    }

    fun testElixirInDocumentationIsNotChecked() {
        val source = "defmodule Sample do\n  @moduledoc \"\"\"\n      foo@bar = 1var\n  \"\"\"\nend\n"

        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.20.0"))
        myFixture.configureByText(ElixirFileType.INSTANCE, source)

        assertEquals(
            "no Elixir is injected into ${source}, so this test checks nothing",
            ElixirLanguage,
            InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, source.indexOf("foo@bar"))?.language
        )
        assertEquals(emptyList<String?>(), myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description })
    }

    fun testElixirInATemplateSigilIsChecked() {
        assertTemplateErrors(
            myFixture,
            testRootDisposable,
            elixir("1.20.0"),
            "defmodule Test do\n  def render(assigns) do\n    ~H'''\n    <div><%= foo@bar %></div>\n    '''\n  end\nend\n",
            "foo@bar" to invalidCharacter("@", "0040", "identifier", "foo@bar")
        )
    }

    private fun invalidCharacter(character: String, codePoint: String, kind: String, word: String): String =
        "invalid character \"$character\" (code point U+$codePoint) in $kind: $word"

    private fun afterNumber(character: String, number: String): String =
        "invalid character \"$character\" after number $number. If you intended to write a number, make sure to separate " +
            "the number from the character (using comma, space, etc). If you meant to write a function name or a variable, " +
            "note that identifiers in Elixir cannot start with numbers. Unexpected token: $character"

    private fun afterNumber1_12(character: String, number: String): String =
        "invalid character $character after number $number. If you intended to write a number, make sure to add the " +
            "proper punctuation character after the number (space, comma, etc). If you meant to write an identifier, note " +
            "that identifiers in Elixir cannot start with numbers. Unexpected token: $character"

    private fun unexpectedToken(character: String, column: Int, codePoint: String): String =
        "unexpected token: \"$character\" (column $column, code point U+$codePoint)"

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

    /** For valid source the plugin's parser still reports an error in, such as `0b1and 2`. */
    private fun assertNoInvalidTokenErrors(languageLevel: ElixirLanguageLevel, source: String) {
        assertEquals(
            "errors in ${escaped(source)} on $languageLevel",
            emptyList<Pair<String, String?>>(),
            errors(languageLevel, source)
                .filter { (_, description) -> INVALID_TOKEN_MESSAGE.containsMatchIn(description.orEmpty()) }
        )
    }

    private fun assertErrors(
        languageLevel: ElixirLanguageLevel,
        source: String,
        vararg expected: Pair<String, String>
    ) {
        assertEquals("errors in ${escaped(source)} on $languageLevel", expected.toList(), errors(languageLevel, source))
    }

    /** For source the parser already reports an error in, at a place Elixir does not name. */
    private fun assertHasError(languageLevel: ElixirLanguageLevel, source: String, expected: Pair<String, String>) {
        val errors = errors(languageLevel, source)

        assertEquals(
            "$expected among the errors in ${escaped(source)} on $languageLevel: $errors",
            1,
            errors.count { it == expected }
        )
    }

    private companion object {
        const val ASCII_ONLY = " (only ASCII characters are allowed)"
        const val WITHOUT_PUNCTUATION = " (only ASCII characters, without punctuation, are allowed)"
        const val DO_KEYWORD =
            "unexpected keyword: do:. In case you wanted to write a \"do\" expression, you must either use do-blocks or " +
                "separate the keyword argument with comma."
        val INVALID_TOKEN_MESSAGE =
            Regex("^(syntax error before: |invalid character |reserved token: |keyword argument must be followed)")
    }
}
