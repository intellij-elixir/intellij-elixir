package org.elixir_lang.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.util.ThrowableRunnable
import junit.framework.Test
import junit.framework.TestSuite
import org.elixir_lang.ElixirFileType
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.junit.LightTestCase
import org.elixir_lang.junit.SharedFixture
import org.elixir_lang.junit.SharedFixtureHost
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

/**
 * Syntax that some Elixir releases reject and others accept. Outcomes were taken from `Code.string_to_quoted/1` on every
 * release from 1.11.4 to 1.20.4, and each message from the release it is asserted on.
 *
 * Where several sources share a check, each is a test of its own, named by its method and source, and those tests share
 * one fixture.
 */
class VersionedSyntaxTest private constructor(
    private val fixture: SharedFixture<VersionedSyntaxTest>?,
    private val case: Case?,
) : LightTestCase(), SharedFixtureHost<VersionedSyntaxTest> {
    /** A test method's instance, which runs on its own. */
    constructor() : this(null, null)

    private var files = 0

    init {
        name = case?.name ?: "shared fixture"
        fixture?.add(this)
    }

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    override fun runShared(serve: ThrowableRunnable<Throwable>) {
        runBare(serve)
    }

    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        SharedFixture.runCase(fixture, this) { super.runBare(testRunnable) }
    }

    override fun check(case: VersionedSyntaxTest) {
        case.case!!.check(this)
    }

    fun testPowerOperatorBefore1_13() {
        assertErrors(elixir("1.12.0"), "2 ** 3 ** 4", "**" to before("'*'"), "**" to before("'*'"))
        assertErrors(elixir("1.12.0"), "x.** ** y", "**" to before("'*'"))
        // Both `**` have the same text, so compare where the error starts.
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.12.0"))
        myFixture.configureByText("versioned_syntax_${files++}.ex", "x.** **: 1")
        assertEquals(listOf(5 to before("'*'")), myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.startOffset to it.description })

        assertEquals(
            listOf("**" to before("'..//'")),
            errors(elixir("1.12.0"), "x.** ..//: 1").filter { it.first == "**" }
        )
        assertEquals(
            emptyList<Pair<String, String?>>(),
            errors(elixir("1.11.0"), "x.** ..//: 1").filter { it.first == "**" }
        )
        assertErrors(elixir("1.12.0"), "x.** !/2", "/" to before("'/'"))
        assertErrors(elixir("1.12.0"), "x.** ==\\\n/2", "==" to before("'=='"))
        assertErrors(elixir("1.12.0"), "[x.**]\\\n", "**" to "invalid escape \\ at end of file")
        assertEquals(
            emptyList<Pair<String, String?>>(),
            errors(elixir("1.11.0"), "Kernel.**]").filter { it.first == "**" }
        )
        assertNoErrors(elixir("1.12.0"), "\"**\"")
    }

    fun testMultiLetterSigilBefore1_15() {
        assertErrors(elixir("1.14.0"), "x = ~MAT()", "A" to sigilDelimiter('A', 7))
    }

    fun testUppercaseSigilWithDigitsBefore1_17() {
        for (languageLevel in listOf(elixir("1.15.0"), elixir("1.16.2"))) {
            assertErrors(languageLevel, "~A1()", "1" to sigilDelimiter('1', 3))
            assertErrors(languageLevel, "~AB1()", "1" to sigilDelimiter('1', 4))
            assertNoErrors(languageLevel, "~MAT()")
        }
    }

    fun testIdentifierNotInNfcTooltip() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.13.0"))
        myFixture.configureByText("nfc.ex", "c\u0327?")

        val tooltip = myFixture.doHighlighting(HighlightSeverity.ERROR).single().toolTip.orEmpty()

        for (expected in listOf(
            "(code points 0x0063 0x0327 0x003F)",
            "(code points 0x00E7 0x003F)",
            "Syntax error before: c\u0327?",
        )) {
            assertTrue("$expected in $tooltip", expected in tooltip)
        }
    }

    fun testEscapedNewlineBeforeArityBefore1_20() {
        // Before `..//` was a token, 1.11 read the `/` after the break as the one out of place.
        assertErrors(elixir("1.11.4"), "&..//\\\n/3", "/" to before("'/'"))
        for (languageLevel in listOf(elixir("1.12.0-rc.0"), elixir("1.19.0"))) {
            assertErrors(
                languageLevel,
                "&..//\\\n/3",
                "..//" to "unexpected token: \".\" (column 2, code point U+002E)"
            )
        }
        assertNoErrors(elixir("1.20.0"), "&..//\\\n/3")

        assertErrors(elixir("1.12.0"), "&**\\\n/2", "**" to before("'*'"))
        assertErrors(elixir("1.19.0"), "&**\\\n/2", "**" to before("'**'"))
        assertNoErrors(elixir("1.20.0"), "&**\\\n/2")
    }

    fun testEscapedNewlineBeforeArityAfterAnOperandBefore1_20() {
        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.12.0"))) {
            assertEquals(
                "x.** on $languageLevel",
                listOf("==" to before("'=='")),
                errors(languageLevel, "x.** ==\\\n/2").filter { it.second in ARITY_ERRORS }
            )
        }
        for (languageLevel in listOf(elixir("1.13.0"), elixir("1.19.0"))) {
            assertEquals(
                "x.** on $languageLevel",
                listOf("/" to before("'/'")),
                errors(languageLevel, "x.** ==\\\n/2").filter { it.second in ARITY_ERRORS }
            )
        }

        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.12.0"))) {
            assertEquals(
                "x.** in on $languageLevel",
                listOf("in" to before("in")),
                errors(languageLevel, "x.** in\\\n/2").filter { it.second in ARITY_ERRORS }
            )
        }
    }

    fun testMapEntriesThatAreNotPairsBefore1_17() {
        assertErrors(elixir("1.12.0"), "%{...a}", "...a" to before("'}'"))
        assertNoErrors(elixir("1.13.0"), "%{...a}")
        assertErrors(elixir("1.16.2"), "%{..}", ".." to before("'}'"))
        assertNoErrors(elixir("1.17.0"), "%{..}")
        assertErrors(elixir("1.16.2"), "%{.. \\\n}", ".." to before("'}'"))
        assertErrors(elixir("1.16.2"), "%{..\\\n\n}", ".." to before("eol"))
    }

    fun testSignKeywordKeyAfterACallBefore1_12() {
        assertEquals(
            emptyList<String?>(),
            errors(elixir("1.11.0"), "f\\\n +: 1")
                .map { it.second }
                .filter { it?.startsWith("unexpected token") == true }
        )
        assertEquals(
            emptyList<String?>(),
            errors(elixir("1.11.0"), "x.\"f#{a}\" +: 1")
                .map { it.second }
                .filter { it?.startsWith("unexpected token: \":\"") == true }
        )
        assertEquals(
            emptyList<String?>(),
            errors(elixir("1.11.0"), "x.% +: 1")
                .map { it.second }
                .filter { it?.startsWith("unexpected token: \":\"") == true }
        )
    }

    fun testGraphemeClusterInAQuotedCallNameIsTheProjectOtps() {
        assertNoErrors(elixir("1.15.8", "24.3.4.6"), ":foo.\"a\u0898\"()")
        assertErrors(elixir("1.15.8", "25.3.2.21"), ":foo.\"a\u0898\"()", "\"a\u0898\"" to NOT_A_LIST_OF_CHARACTERS)

        for (otp in listOf("24.3.4.6", "26.2.5.21")) {
            assertErrors(
                elixir("1.15.8", otp),
                ":foo.\"\u0378\u200D\"()",
                "\"\u0378\u200D\"" to NOT_A_LIST_OF_CHARACTERS
            )
        }
    }

    fun testHexadecimalEscapeWithoutTwoDigitsFrom1_20() {
        assertErrors(elixir("1.20.0"), "\"\\x{110000}\"", "\\x{110000}" to HEX)
    }

    fun testHexadecimalEscapeOfASurrogateFrom1_20() {
        assertErrors(elixir("1.20.0"), "\"\\x{D800}\"", "\\x{D800}" to HEX)
    }

    fun testElixirInDocumentationIsNotChecked() {
        val source = "defmodule Sample do\n  @moduledoc \"\"\"\n      x = ..\n  \"\"\"\nend\n"

        ElixirLanguageLevelResolver.overrideLanguageLevel(project, elixir("1.13.0"))
        myFixture.configureByText(ElixirFileType.INSTANCE, source)

        assertEquals(
            "no Elixir is injected into $source, so this test checks nothing",
            ElixirLanguage,
            InjectedLanguageManager.getInstance(project).findInjectedElementAt(myFixture.file, source.indexOf(".."))?.language
        )
        assertEquals(emptyList<String?>(), myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description })
    }

    fun testWithoutAnElixirSdkTheNewestReleaseApplies() {
        val source = "x = ..\nfoo//bar\n"

        myFixture.configureByText("no_sdk.ex", source)

        assertEquals(
            listOf("//" to STEP),
            myFixture.doHighlighting(HighlightSeverity.ERROR).map { source.substring(it.startOffset, it.endOffset) to it.description }
        )
    }

    private fun errors(languageLevel: ElixirLanguageLevel, source: String): List<Pair<String, String?>> {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, languageLevel)
        // A new file name each time, or a file with the same text keeps the tree parsed under the previous language
        // level.
        myFixture.configureByText("versioned_syntax_${files++}.ex", source)

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

    private fun assertErrors(
        languageLevel: ElixirLanguageLevel,
        source: String,
        vararg expected: Pair<String, String>
    ) {
        assertEquals("errors in $source on $languageLevel", expected.toList(), errors(languageLevel, source))
    }

    private class Case(val name: String, val check: VersionedSyntaxTest.() -> Unit)

    companion object {
        private const val NFC = "Elixir expects unquoted Unicode atoms, variables, and calls to be in NFC form."
        private const val HEX =
            "invalid hex escape character, expected \\xHH where H is a hexadecimal digit. Syntax error after: \\x"
        private const val UNICODE =
            "invalid Unicode escape character, expected \\uHHHH or \\u{H*} where H is a hexadecimal digit. Syntax error after: \\u"
        private const val NOT_A_LIST_OF_CHARACTERS = "errors were found at the given arguments: * 1st argument: not a list of characters"
        private const val STEP =
            "the range step operator (//) must immediately follow the range definition operator (..), for example: 1..9//2. " +
                "If you wanted to define a default argument, use (\\\\) instead. Syntax error before: '//'"
        private val NULLARY_RANGE = before("'..'")
        private val ARITY_ERRORS = listOf(before("'/'"), before("'=='"), before("in"), before("'in'"))

        @JvmStatic
        fun suite(): Test {
            val fixture = SharedFixture { VersionedSyntaxTest(null, null) }
            val suite = fixture.suite(VersionedSyntaxTest::class.java.name)
            // A source checked in more than one way is one test making each check.
            val cases = cases().groupBy { it.name }.map { (name, checks) ->
                Case(name) { for (check in checks) check.check(this) }
            }

            for (case in cases) suite.addTest(VersionedSyntaxTest(fixture, case))
            for (test in TestSuite(VersionedSyntaxTest::class.java).tests()) suite.addTest(test)

            return suite
        }

        private fun cases(): List<Case> = buildList {
            sources("testNullaryRangeBefore1_14", listOf(
                "..",
                ".. = right",
                "left = ..",
                "not ..",
                "require(..)",
                "require (..)",
                "require(.., bar)",
                "require (..), bar",
                "require(foo, ..)",
                "require foo, (..)",
                "assert [.., :ok]",
                "assert {.., :ok}",
                "defmacro (..) do\n  :ok\nend",
                "Range.range?(..)",
                "Range.range? (..)",
                "[..]",
                "{..}",
                "(..)",
                "foo(..)",
                ".. |> foo",
                "[a: ..]",
                "%{a: ..}",
                "fn -> .. end",
                "%{..}",
                "x = ..\n",
                "&..\\\n/0",
            )) { source ->
                assertErrors(elixir("1.11.0"), source, ".." to NULLARY_RANGE)
                assertErrors(elixir("1.13.0"), source, ".." to NULLARY_RANGE)
            }
            sources("testNullaryRangeBefore1_14", listOf("assert (..) == 0..-1//1", "assert 0..-1//1 == (..)")) { source ->
                assertErrors(elixir("1.13.0"), source, ".." to NULLARY_RANGE)
            }

            sources(
                "testNullaryRangeFrom1_14",
                listOf("..", ".. = right", "require (..), bar", "assert (..) == 0..-1//1", "x = ..\n", "&..\\\n/0")
            ) { source ->
                assertNoErrors(elixir("1.14.0"), source)
                assertNoErrors(elixir("1.20.0"), source)
            }

            pairs("testPowerOperatorBefore1_13", listOf(
                "-(2**2)" to "'*'",
                "-(2 ** 2)" to "'*'",
                "def -(2 ** 2)" to "'*'",
                "def (-(2 ** 2))" to "'*'",
                "2 ** 2" to "'*'",
                "2**2" to "'*'",
                "-(2)**2" to "'*'",
                "+(2 ** 2)" to "'*'",
                "!(2 ** 2)" to "'*'",
                "-2 ** 2" to "'*'",
                "-x ** 2" to "'*'",
                "(-2) ** 2" to "'*'",
                "2 ** -2" to "'*'",
                "x ** y" to "'*'",
                "&**/2" to "'*'",
                "[**: 1]" to "'*'",
                "def a ** b, do: 1" to "'*'",
                ":**" to "",
                "x.**" to "",
                "Kernel.**(2, 2)" to "')'",
                "x.**(a: 1)" to "')'",
                "x.**\n" to "",
                "x.** # c" to "",
                "x.**\t# c" to "",
                "x.**\n# c\n" to "",
                "\"#{x.** }\"" to "",
                "\"#{x.**\n}\"" to "",
                "(x.**)" to "')'",
                "[x.**]" to "']'",
                "[x.**\n]" to "']'",
                "[x.** # c\n]" to "']'",
                "[x.**\\\n]" to "']'",
                "{x.**}" to "'}'",
                "<<x.**>>" to "'>>'",
                "f(x.**, 1)" to "','",
                "x.**;" to "';'",
                "[x.**.y]" to "'.'",
                "%{x.** => 1}" to "'=>'",
                "[Kernel.**]" to "']'",
                "x.**\n|> y" to "'|>'",
                "x.**=1" to "'='",
                "x.**::t" to "'::'",
                "x.** do end" to "do",
                "x.** do: 1" to "do",
                "[x.** a: 1]" to "a",
                "f(x.** a: 1)" to "a",
                "x.** \"a\": 1" to "[<<\"a\">>]",
                "x.** 'a': 1" to "[<<\"a\">>]",
                "fn a -> x.** end" to "'end'",
                "cond do a -> x.** end" to "'end'",
                "try do x.** after 1 end" to "'after'",
                "try do x.** rescue _ -> 1 end" to "rescue",
                "try do x.** catch _ -> 1 end" to "'catch'",
                "x.** in y" to "in",
                "x.** \\\\ y" to "'\\\\\\\\'",
                "x.** end: 1" to "'end'",
                "x.** when: 1" to "'when'",
                "x.** not: 1" to "'not'",
                "x.** _a: 1" to "'_a'",
                "x.** A: 1" to "'A'",
                "x.** a?: 1" to "'a?'",
                "x.** nil: 1" to "nil",
                "x.** \"a b\": 1" to "[<<\"a b\">>]",
                "x.** \"\": 1" to "[<<>>]",
                "x.** \"end\": 1" to "[<<\"end\">>]",
                "x.** not in y" to "'not in'",
                "x.** not  in y" to "'not in'",
                "[x.** not in y]" to "'not in'",
                "x.** # c \\\n" to "",
                "x.**\\\n\n" to "",
                "x.** # c \\" to "",
                "x.** not \\\nin y" to "'not in'",
                "x.** not\\\nin y" to "'not in'",
                "x.** not \\\n in y" to "'not in'",
                "x.** not\t\\\n\tin y" to "'not in'",
                "x.** 'a\"b': 1" to "[<<\"a\\\"b\">>]",
                "x.** \"a\\\\b\": 1" to "[<<\"a\\\\b\">>]",
                "x.** \"a\tb\": 1" to "[<<\"a\\tb\">>]",
                "x.** \"a\\tb\": 1" to "[<<\"a\\tb\">>]",
                "x.** \"a\\nb\": 1" to "[<<\"a\\nb\">>]",
                "x.** \"a#b\": 1" to "[<<\"a#b\">>]",
                "x.** \"é\": 1" to "[<<\"é\"/utf8>>]",
                "x.** \"aé\": 1" to "[<<\"aé\"/utf8>>]",
                "x.** \"日本\": 1" to "[<<230,151,165,230,156,172>>]",
                "x.** \"a\u007Fb\": 1" to "[<<97,127,98>>]",
                "x.** \"a\u000Bb\": 1" to "[<<\"a\\vb\">>]",
                "x.** \"a\u000Cb\": 1" to "[<<\"a\\fb\">>]",
                "x.** \"a\u0008b\": 1" to "[<<\"a\\bb\">>]",
                "x.** \"a\u001Bb\": 1" to "[<<\"a\\eb\">>]",
                "x.** \"a\u0007b\": 1" to "[<<97,7,98>>]",
                "x.** 'a\\'b': 1" to "[<<\"a'b\">>]",
                "x.** \"a\\vb\": 1" to "[<<\"a\\vb\">>]",
                "x.** \"a\\rb\": 1" to "[<<\"a\\rb\">>]",
                "x.** \"a\\fb\": 1" to "[<<\"a\\fb\">>]",
                "x.** \"a\\bb\": 1" to "[<<\"a\\bb\">>]",
                "x.** \"a\\eb\": 1" to "[<<\"a\\eb\">>]",
                "x.** \"a\\ab\": 1" to "[<<97,7,98>>]",
                "x.** \"a\\0b\": 1" to "[<<97,0,98>>]",
                "x.** \"a\\sb\": 1" to "[<<\"a b\">>]",
                "x.** \"a\\db\": 1" to "[<<97,127,98>>]",
                "x.** \"a\\zb\": 1" to "[<<\"azb\">>]",
                "x.** \"a\\x41\": 1" to "[<<\"aA\">>]",
                "x.** \"a\\u0041\": 1" to "[<<\"aA\">>]",
                "x.** \"a\\u{41}\": 1" to "[<<\"aA\">>]",
                "x.** aé: 1" to "aé",
                // Erlang quotes an atom beyond Latin-1.
                "x.** \u0141x: 1" to "'\u0141x'",
                "x.** \u65E5\u672C: 1" to "'\u65E5\u672C'",
                "x.** é: 1" to "é",
            ) + listOf(
                "*", "..", "<>", "++", "--", "+++", "---", "==", "!=", "===", "!==", "=~", "<", ">", "<=", ">=", "&&", "||", "&&&",
                "|||", "<<<", ">>>", "^^^", "~>", "<~", "<~>", "<|>", "~>>", "<<~", "|", "=", "|>", "::", "<-", "and", "or", "when",
            ).map { "x.** $it y" to "'$it'" }) { source, token ->
                assertErrors(elixir("1.11.0"), source, "**" to before(token))
                assertErrors(elixir("1.12.0"), source, "**" to before(token))
                assertNoErrors(elixir("1.13.0"), source)
            }
            pairs("testPowerOperatorBefore1_13", listOf(
                "+", "-", "++", "--", "<>", "==", "!=", "&&", "||", "..", "@", "!", "^", "~~~", "|", "<-", "->", "\\\\", "|>", "=~", "<",
                ">", "<=", ">=", "===", "!==", "&&&", "|||", "<<<", ">>>", "~>", "<~", "<~>", "<|>", "~>>", "<<~", "&",
                "*", "=", "^^^", "+++", "---", "...", "%", "%{}", "{}", "<<>>",
            ).map { operator -> "x.** $operator: 1" to ("**" to before("'${operator.replace("\\", "\\\\")}'")) }) { source, expected ->
                assertErrors(elixir("1.11.0"), source, expected)
                assertErrors(elixir("1.12.0"), source, expected)
                assertNoErrors(elixir("1.13.0"), source)
            }
            // `else` is reserved from OTP 27, which no release before 1.13 runs on.
            pairs("testPowerOperatorBefore1_13", listOf("if a do x.** else 1 end" to "else", "x.** else: 1" to "else")) { source, token ->
                assertErrors(elixir("1.11.0", "24.3.4.6"), source, "**" to before(token))
                assertErrors(elixir("1.12.0", "24.3.4.6"), source, "**" to before(token))
                assertNoErrors(elixir("1.13.0"), source)
            }
            pairs("testPowerOperatorBefore1_13", listOf("x.** true: 1" to "true", "x.** false: 1" to "false", "x.** Foo: 1" to "'Foo'")) { source, token ->
                assertErrors(elixir("1.11.0"), source, "**" to before(token))
                assertErrors(elixir("1.12.0"), source, "**" to before(token))
                assertNoErrors(elixir("1.13.0"), source)
            }
            sources(
                "testPowerOperatorBefore1_13",
                listOf("x.** /: 1", "x.** ::: 1", "x.** =>: 1", "x.** ..// y", "x.** .. / y", "x.** .. // y", "x.** .. //: 1")
            ) { source ->
                for (languageLevel in listOf(elixir("1.11.0"), elixir("1.12.0"))) {
                    assertEquals(
                        "$source on $languageLevel",
                        emptyList<String?>(),
                        errors(languageLevel, source).filter { it.first == "**" }.map { it.second }
                    )
                }
            }
            pairs("testPowerOperatorBefore1_13", listOf(
                "x.** ==\n/2" to "'=='",
                "x.** in\n/2" to "in",
                "x.** == # c\n/2" to "'=='",
                "x.** ==\n\n/2" to "'=='",
            )) { source, token ->
                for (languageLevel in listOf(elixir("1.11.0"), elixir("1.12.0"))) {
                    assertEquals(
                        "$source on $languageLevel",
                        listOf("**" to before(token)),
                        errors(languageLevel, source).filter { it.first == "**" }
                    )
                }
            }
            sources("testPowerOperatorBefore1_13", listOf("x.**\\\n", "x.** \\\n", "x.**\n\\\n", "x.** # c\n\\\n")) { source ->
                assertErrors(elixir("1.12.0"), source, "**" to "invalid escape \\ at end of file")
            }
            sources("testPowerOperatorBefore1_13", listOf("x.**\\", "x.** \\")) { source ->
                assertTrue(source, ("**" to "invalid escape \\ at end of file") in errors(elixir("1.12.0"), source))
            }
            sources("testPowerOperatorBefore1_13", listOf("x.** do/2", "x.** do /2", "x.** end/2", "x.** end /2")) { source ->
                assertEquals(
                    source,
                    emptyList<Pair<String, String?>>(),
                    errors(elixir("1.11.0"), source).filter { it.first == "**" }
                )
            }
            sources("testPowerOperatorBefore1_13", listOf(
                "Kernel.** 2", "x.** + 1", "x.**()", "x.**(1)", "Kernel.**(2)", "x.**\n1", "x.** - y", "x.** / y", "x.** not y",
                "x.** ! y", "x.** @ y", "x.** # c\n1", "x.** :a", "x.** {1}", "x.** A", "x.**[1]", "x.** y", "x.** fn -> 1 end",
                "x.** ==/2", "x.** == /2", "x.**\n==/2", "[x.** ==/2]", "x.** in/2", "x.** ../2", "x.** |>/2", "x.** =/2", "x.** ::/2",
                "x.** and/2", "x.** when/2", "x.** */2", "x.** <-/2", "x.** |/2", "x.** \\\\/2",
            )) { source ->
                assertNoErrors(elixir("1.11.0"), source)
                assertNoErrors(elixir("1.12.0"), source)
            }
            // Operator references the plugin's parser does not all read, so only the `**` error is checked.
            sources("testPowerOperatorBefore1_13", listOf(
                "x.** ++/2", "x.** <>/2", "x.** --/2", "x.** +++ /2", "x.** ---\t/2", "x.** |> /2", "x.** <<</2", "x.** === /2",
                "x.** >= /2", "x.** &&& /2", "x.** or/2", "x.** = /2", "x.** :: /2", "x.** ^^^/2", "x.** * /2", "x.** .. /2",
                "x.** in /2", "x.** when /2", "x.** \\\\ /2",
            )) { source ->
                for (languageLevel in listOf(elixir("1.11.0"), elixir("1.12.0"))) {
                    assertEquals(
                        "$source on $languageLevel",
                        emptyList<Pair<String, String?>>(),
                        errors(languageLevel, source).filter { it.first == "**" }
                    )
                }
            }

            pairs("testMultiLetterSigilBefore1_15", listOf(
                "~MAT()" to 'A',
                "~MAT{1,2,3}" to 'A',
                "~UNKNOWN'abc'" to 'N',
                "~UNKNOWN'foo bar'" to 'N',
                "~AB()" to 'B',
                "~MAT(x)i" to 'A',
                "~MAT\"x\"" to 'A',
                "~MAT\"\"\"\nx\n\"\"\"" to 'A',
                "~MAT[a]" to 'A',
                "~A1()" to '1',
                "~AB1()" to 'B',
            )) { source, character ->
                assertErrors(elixir("1.11.0"), source, "$character" to sigilDelimiter(character, 3))
                assertErrors(elixir("1.14.0"), source, "$character" to sigilDelimiter(character, 3))
            }

            sources("testUppercaseSigilWithDigitsBefore1_17", listOf("~A1()", "~AB1()", "~MAT()", "~UNKNOWN'abc'")) { source ->
                assertNoErrors(elixir("1.17.0"), source)
            }
            sources("testUppercaseSigilWithDigitsBefore1_17", listOf("~A()", "~r()")) { source ->
                assertNoErrors(elixir("1.11.0"), source)
            }

            pairs("testIdentifierNotInNfcBefore1_14", listOf(
                "c\u0327" to "c\u0327",
                "c\u0327\n" to "c\u0327",
                "c\u0327\\\n1" to "c\u0327",
                "c\u0327 = 1; \u00E7" to "c\u0327",
                ":c\u0327" to "c\u0327",
                "[c\u0327: 1]" to "c\u0327",
                "e\u0301 = 1" to "e\u0301",
                "x.c\u0327" to "c\u0327",
                "c\u0327()" to "c\u0327",
                "c\u0327c\u0327" to "c\u0327c\u0327",
                "c\u0327?" to "c\u0327?",
            )) { source, word ->
                assertErrors(elixir("1.11.0"), source, word to NFC)
                assertErrors(elixir("1.13.0"), source, word to NFC)
                assertNoErrors(elixir("1.14.0"), source)
            }
            sources("testIdentifierNotInNfcBefore1_14", listOf("\u00E7", "x\u0327", "\"c\u0327\"", ":\"c\u0327\"")) { source ->
                assertNoErrors(elixir("1.13.0"), source)
            }

            // An alias is put in NFC like any other word; from 1.14 its non-ASCII character is `InvalidToken`'s to report.
            pairs("testAliasNotInNfcBefore1_14", listOf(
                "C\u0327" to "C\u0327",
                "E\u0301x" to "E\u0301x",
                "Foo.C\u0327" to "C\u0327",
            )) { source, alias ->
                assertErrors(elixir("1.11.0"), source, alias to NFC)
                assertErrors(elixir("1.13.0"), source, alias to NFC)
            }

            pairs("testEscapedNewlineBeforeArityBefore1_20", listOf(
                "&+\\\n/2" to ("/" to before("'/'")),
                "&/\\\n/2" to ("/" to before("'/'")),
                "&or\\\n/2" to ("or" to before("'or'")),
                "&+\\\n /2" to ("/" to before("'/'")),
                "&+ \\\n/2" to ("/" to before("'/'")),
                "&(+\\\n/2)" to ("/" to before("'/'")),
                "&and\\\n/2" to ("and" to before("'and'")),
                "&!\\\n/1" to ("/" to before("'/'")),
                "&-\\\n/1" to ("/" to before("'/'")),
                "+\\\n/2" to ("/" to before("'/'")),
                "&*\\\n/2" to ("*" to before("'*'")),
                "&not\\\n/1" to ("/" to before("'/'")),
                "&when\\\n/2" to ("when" to before("'when'")),
            )) { source, expected ->
                assertErrors(elixir("1.11.0"), source, expected)
                assertErrors(elixir("1.19.0"), source, expected)
                assertNoErrors(elixir("1.20.0"), source)
            }
            sources(
                "testEscapedNewlineBeforeArityBefore1_20",
                listOf("&+/\\\n2", "&\\\n+/2", "&foo\\\n/2", "&+/2", "&+ /2", "&<<>>\\\n/1", "&{}\\\n/1", "&%{}\\\n/1")
            ) { source ->
                for (languageLevel in listOf(elixir("1.11.0"), elixir("1.19.0"), elixir("1.20.0"))) {
                    assertNoErrors(languageLevel, source)
                }
            }

            sources("testEscapedNewlineBeforeArityAfterAnOperandBefore1_20", listOf("x ==\\\n/2", "f ==\\\n/2", "x.y ==\\\n/2")) { source ->
                for (languageLevel in listOf(elixir("1.11.0"), elixir("1.19.0"))) {
                    assertEquals(
                        "$source on $languageLevel",
                        listOf("/" to before("'/'")),
                        errors(languageLevel, source).filter { it.second in ARITY_ERRORS }
                    )
                }
            }
            sources("testEscapedNewlineBeforeArityAfterAnOperandBefore1_20", listOf("[==\\\n/2]", "f(==\\\n/2)", "x = ==\\\n/2")) { source ->
                assertEquals(
                    source,
                    listOf("==" to before("'=='")),
                    errors(elixir("1.19.0"), source).filter { it.second in ARITY_ERRORS }
                )
            }

            pairs("testMapEntriesThatAreNotPairsBefore1_17", listOf(
                "%{:a}" to (":a" to "'}'"),
                "%{{:a, :b}}" to ("{:a, :b}" to "'}'"),
                "%{+foo, bar => bat, ...baz}" to ("+foo" to "','"),
                "%{1}" to ("1" to "'}'"),
                "%{\"a\"}" to ("\"a\"" to "'}'"),
                "%{[1]}" to ("[1]" to "'}'"),
                "%{:a, b: 1}" to (":a" to "','"),
                "%{a => 1, :b}" to (":b" to "'}'"),
                "%{^a}" to ("^a" to "'}'"),
                "%{@a}" to ("@a" to "'}'"),
                "%{-1}" to ("-1" to "'}'"),
                "%{not a}" to ("not a" to "'}'"),
                "%Foo{:a}" to (":a" to "'}'"),
                "%{m | :a}" to (":a" to "'}'"),
                "%{a, :b}" to (":b" to "'}'"),
                "%{x.y, 1}" to ("1" to "'}'"),
                "%{foo(), :a}" to (":a" to "'}'"),
                "%{m | a, :b}" to (":b" to "'}'"),
                "%{-a}" to ("-a" to "'}'"),
                "%{!a}" to ("!a" to "'}'"),
                "%{~~~a}" to ("~~~a" to "'}'"),
                "%{..., :a}" to (":a" to "'}'"),
                "%{@a, 1}" to ("@a" to "','"),
                "%{a, b, :c}" to (":c" to "'}'"),
                "%{:a\n}" to (":a" to "eol"),
                "%{:a # c\n}" to (":a" to "eol"),
                "%{:a\\\n}" to (":a" to "'}'"),
                "%{:a \\\n}" to (":a" to "'}'"),
                "%{fn -> 1 end \\\n}" to ("fn -> 1 end" to "'}'"),
                "%{fn -> 1 end\\\n}" to ("fn -> 1 end" to "'}'"),
                "%{fn -> 1 end \\\n \\\n}" to ("fn -> 1 end" to "'}'"),
                "%{fn -> 1 end\\\n\n}" to ("fn -> 1 end" to "eol"),
                "%{fn -> 1 end # c\n}" to ("fn -> 1 end" to "eol"),
                "%{1 \\\n}" to ("1" to "'}'"),
                "%{1\\\n\n}" to ("1" to "eol"),
            )) { source, (entry, token) ->
                assertErrors(elixir("1.11.0"), source, entry to before(token))
                assertErrors(elixir("1.16.2"), source, entry to before(token))
                assertNoErrors(elixir("1.17.0"), source)
            }
            sources(
                "testMapEntriesThatAreNotPairsBefore1_17",
                listOf("%{a}", "%{a, b}", "%{x.y}", "%{foo()}", "%{...}", "%{a | b}", "%{a: 1}", "%{m | a: 1}", "%{a => 1}", "%{x.()}", "%{foo.bar.()}")
            ) { source ->
                assertNoErrors(elixir("1.11.0"), source)
            }

            pairs("testSignKeywordKeyAfterACallBefore1_12", listOf(
                "f +: :ok" to 4,
                "f +:\n:ok" to 4,
                "f -: 1" to 4,
                "x.f +: 1" to 6,
                "f +: 1, b: 2" to 4,
                "f  -: 1" to 5,
                "x.\"f\" +: 1" to 8,
                "x.\"f\" -: 1" to 8,
                "x.'f' +: 1" to 8,
                "x.\"f\"\t+: 1" to 8,
                "x.\"f\"  +: 1" to 9,
                "Kernel.\"f\" +: 1" to 13,
                "[x.\"f\" +: 1]" to 9,
                "x.and +: 1" to 8,
                "x.+ +: 1" to 6,
                "x.|| +: 1" to 7,
                "x.! +: 1" to 6,
                "x.nil +: 1" to 8,
                "x.do +: 1" to 7,
                "x.end +: 1" to 8,
                "x.<> +: 1" to 7,
                "x.@ +: 1" to 6,
            )) { source, column ->
                assertErrors(elixir("1.11.0"), source, ":" to colon(column))
                assertNoErrors(elixir("1.12.0"), source)
            }
            sources(
                "testSignKeywordKeyAfterACallBefore1_12",
                listOf("[+: 1]", "f(+: 1)", "%{+: 1}", "f a, +: 1", "f ++: 1", "f when: 1", "f \\\n+: 1", "x.\"f\"+: 1", "x.\"f\" \\\n+: 1")
            ) { source ->
                assertNoErrors(elixir("1.11.0"), source)
            }

            pairs("testDotKeywordKeyBefore1_13", listOf("[.: :.]" to 3, "[.: 1]" to 3, "f .: 1" to 4, "f(.: 1)" to 4, "%{.: 1}" to 4)) { source, column ->
                assertErrors(elixir("1.11.0"), source, ":" to colon(column))
                assertErrors(elixir("1.12.0"), source, ":" to colon(column))
                assertNoErrors(elixir("1.13.0"), source)
            }
            sources("testDotKeywordKeyBefore1_13", listOf(":.", "[..: 1]")) { source ->
                assertNoErrors(elixir("1.12.0"), source)
            }

            sources("testSteppedRangeAtomBefore1_12", listOf(":..//", "[:..//]")) { source ->
                assertErrors(elixir("1.11.0"), source, "..//" to before("'/'"))
                assertNoErrors(elixir("1.12.0"), source)
            }

            pairs("testDoBlockEndBeforeTypeOperatorBefore1_12", listOf(
                "<<if true do\n    \"hello\"\n  end::binary>>\n" to unexpectedToken(">>", "do"),
                "<<if true do \"a\" end::binary>>" to unexpectedToken(">>", "do"),
                "<<if true do \"a\" else \"b\" end::binary>>" to unexpectedToken(">>", "do"),
                "<<case x do\n_ -> \"a\"\nend::binary>>" to unexpectedToken(">>", "do"),
                "<<foo do end::binary>>" to unexpectedToken(">>", "do"),
                "<<fn -> 1 end::binary>>" to unexpectedToken(">>", "fn"),
                "[if true do 1 end::x]" to unexpectedToken("]", "do"),
                "{if true do 1 end::x}" to unexpectedToken("}", "do"),
                "foo(if true do 1 end::x)" to unexpectedToken(")", "do"),
                "%{a: if true do 1 end::x}" to unexpectedToken("}", "do"),
                "if true do 1 end::integer" to missingTerminator("do"),
                "x = if true do 1 end::integer" to missingTerminator("do"),
                "if true do\n1\nend::x" to missingTerminator("do"),
                "foo do end::x" to missingTerminator("do"),
                "x = fn -> 1 end::integer" to missingTerminator("fn"),
                "if a do\n  if b do\n    1\n  end::x\nend" to missingTerminator("do"),
                "[foo do\nbar do\n1\nend::x\nend]" to unexpectedToken("]", "do"),
                "\"#{if a do 1 end::x}\"" to unexpectedToken("}", "do"),
                "x[if true do 1 end::x]" to unexpectedToken("]", "do"),
            )) { source, message ->
                assertErrors(elixir("1.11.0"), source, "end" to message)
                assertNoErrors(elixir("1.12.0"), source)
            }
            sources("testDoBlockEndBeforeTypeOperatorBefore1_12", listOf(
                "if true do 1 end :: integer",
                "<<if true do \"a\" end :: binary>>",
                "<<(if true do \"a\" end)::binary>>",
            )) { source ->
                assertNoErrors(elixir("1.11.0"), source)
            }

            sources("testOperatorAndArityWithoutCaptureBefore1_13", listOf(
                "@/1",
                "@ /1",
                "@/2",
                "foo(@/1)",
                "+/1",
                "!/1",
                "^/1",
                "&/1",
                "-/1",
                "~~~/1",
                "@/1 + 1",
                "[+/1]",
                "&(@/1)",
                "&(+/1)",
                "x = @/1",
                "&\n+/1",
                "& \n@/1",
                "&\n\n!/1",
                "&# c\n^/1",
                "&\n~~~/1",
                "&\n-/1",
                "&\n&/1",
            )) { source ->
                assertErrors(elixir("1.11.0"), source, "/" to before("'/'"))
                assertErrors(elixir("1.12.0"), source, "/" to before("'/'"))
                assertNoErrors(elixir("1.13.0"), source)
            }
            sources(
                "testOperatorAndArityWithoutCaptureBefore1_13",
                listOf("&@/1", "&+/1", "not/1", "when/2", "@foo/1", ".../1", "&+/1 |> foo", "&+/1 + 1", "&-/1 == x", "&@/1 |> foo", "& +/1", "&\\\n+/2")
            ) { source ->
                assertNoErrors(elixir("1.12.0"), source)
            }

            pairs("testGraphemeClusterInAQuotedCallNameFrom1_13To1_17", listOf(
                ":foo.\"\u0E1A\u0E39\u0E21\u0E40\u0E21\u0E2D\u0E41\u0E23\u0E07\"()" to
                    "\"\u0E1A\u0E39\u0E21\u0E40\u0E21\u0E2D\u0E41\u0E23\u0E07\"",
                "foo.\"\u27A1\uFE0F\"()" to "\"\u27A1\uFE0F\"",
                ":foo.\"e\u0301\"()" to "\"e\u0301\"",
                ":foo.'\u0E1A\u0E39'()" to "'\u0E1A\u0E39'",
                ":foo.\"\u0E1A\u0E39\"" to "\"\u0E1A\u0E39\"",
                "Foo.\"\u0E1A\u0E39\"()" to "\"\u0E1A\u0E39\"",
            )) { source, name ->
                assertNoErrors(elixir("1.12.0"), source)
                assertErrors(elixir("1.13.0"), source, name to NOT_A_LIST_OF_CHARACTERS)
                assertErrors(elixir("1.17.0"), source, name to NOT_A_LIST_OF_CHARACTERS)
                assertNoErrors(elixir("1.18.0"), source)
            }
            sources(
                "testGraphemeClusterInAQuotedCallNameFrom1_13To1_17",
                listOf(":\"\u0E1A\u0E39\"", "[\"\u0E1A\u0E39\": 1]", ":foo.\"\u0E1A\u0E21\"()")
            ) { source ->
                assertNoErrors(elixir("1.13.0"), source)
            }

            sources("testStepOperatorNotAfterARangeFrom1_12", listOf(
                "foo..(bar//bat)",
                "foo..bar baz//bat",
                "foo++bar//bat",
                "foo//bar",
                "1//2",
                "(foo//bar)",
                "[foo//bar]",
                "&foo//2",
                "def foo(a // b)",
                "a..b//c//d",
            )) { source ->
                assertErrors(elixir("1.12.0"), source, "//" to STEP)
                assertErrors(elixir("1.20.0"), source, "//" to STEP)
            }
            sources(
                "testStepOperatorNotAfterARangeFrom1_12",
                listOf("foo..(bar//bat)", "foo++bar//bat", "foo//bar", "(foo//bar)", "[foo//bar]", "&foo//2", "def foo(a // b)")
            ) { source ->
                assertNoErrors(elixir("1.11.0"), source)
            }
            sources(
                "testStepOperatorNotAfterARangeFrom1_12",
                listOf("a..b//c", "(a..b)//c", "x = 1..2//3", "1..2 + 3//4", "foo..bar++baz//bat", "(foo++bar)..baz//bat")
            ) { source ->
                assertNoErrors(elixir("1.20.0"), source)
            }

            pairs("testInvalidEscapeInAQuotedCallNameFrom1_18", listOf(
                "a.'\\xg'" to ("\\x" to HEX),
                "a.'\\ug'" to ("\\u" to UNICODE),
                "a.\"\\xg\"()" to ("\\x" to HEX),
                "Foo.\"\\xg\"()" to ("\\x" to HEX),
                "a.'\\u{110000}'" to ("\\u{110000}" to "invalid or reserved Unicode code point \\u{110000}. Syntax error after: \\u"),
                "a.'\\u{D800}'" to ("\\u{D800}" to "invalid or reserved Unicode code point \\u{D800}. Syntax error after: \\u"),
            )) { source, expected ->
                assertNoErrors(elixir("1.17.0"), source)
                assertErrors(elixir("1.18.0"), source, expected)
                assertErrors(elixir("1.20.0"), source, expected)
            }
            sources("testInvalidEscapeInAQuotedCallNameFrom1_18", listOf("a.\"\\x41\"()", "a.'\\q'")) { source ->
                assertNoErrors(elixir("1.20.0"), source)
            }

            pairs("testHexadecimalEscapeWithoutTwoDigitsFrom1_20", listOf(
                "\"\\xA\"" to "\\xA",
                "\"\\x{41}\"" to "\\x{41}",
                "'\\xA'" to "\\xA",
                ":\"\\xA\"" to "\\xA",
                "a.\"\\xA\"()" to "\\xA",
                "a.\"\\x{41}\"()" to "\\x{41}",
            )) { source, escape ->
                assertNoErrors(elixir("1.19.0"), source)
                assertErrors(elixir("1.20.0"), source, escape to HEX)
            }
            sources("testHexadecimalEscapeWithoutTwoDigitsFrom1_20", listOf("\"\\xFF\"", "~s(\\xA)", "~S(\\xA)")) { source ->
                assertNoErrors(elixir("1.20.0"), source)
            }
        }

        /** A test per source, named by [method] and the source. */
        private fun MutableList<Case>.sources(method: String, sources: List<String>, check: VersionedSyntaxTest.(String) -> Unit) {
            for (source in sources) add(Case("$method[${label(source)}]") { check(source) })
        }

        /** A test per source and what is expected of it, named by [method] and the source. */
        private fun <T> MutableList<Case>.pairs(
            method: String,
            pairs: List<Pair<String, T>>,
            check: VersionedSyntaxTest.(String, T) -> Unit,
        ) {
            for ((source, expected) in pairs) add(Case("$method[${label(source)}]") { check(source, expected) })
        }

        /** [source] as a Kotlin string literal would spell it, so a test name is one line and names no two sources alike. */
        private fun label(source: String): String = buildString {
            for (character in source) {
                when {
                    character == '\\' -> append("\\\\")
                    character == '\n' -> append("\\n")
                    character == '\t' -> append("\\t")
                    character < ' ' || character == '\u007F' -> append("\\u%04X".format(character.code))
                    else -> append(character)
                }
            }
        }

        private fun before(token: String) = "syntax error before: $token"

        private fun colon(column: Int) = "unexpected token: \":\" (column $column, code point U+003A)"

        private fun sigilDelimiter(character: Char, column: Int) =
            "invalid sigil delimiter: \"$character\" (column $column, code point U+${"%04X".format(character.code)}). " +
                "The available delimiters are: //, ||, \"\", '', (), [], {}, <>"

        private fun unexpectedToken(token: String, opener: String) =
            "unexpected token: $token. The \"$opener\" at line 1 is missing terminator \"end\""

        private fun missingTerminator(opener: String) = "missing terminator: end (for \"$opener\" starting at line 1)"
    }
}
