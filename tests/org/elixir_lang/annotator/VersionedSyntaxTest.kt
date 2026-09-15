package org.elixir_lang.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.ElixirFileType
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_11
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_12
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_13
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_14
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_15
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_16_2
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_17
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_18
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_19
import org.elixir_lang.language_level.ElixirLanguageLevel.V1_20
import org.elixir_lang.language_level.ElixirLanguageLevelResolver

/**
 * Syntax that some Elixir releases reject and others accept. Outcomes were taken from `Code.string_to_quoted/1` on every
 * release from 1.11.4 to 1.20.4, and each message from the newest release that rejects the construct.
 */
class VersionedSyntaxTest : BasePlatformTestCase() {
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

    fun testNullaryRangeBefore1_14() {
        for (source in listOf(
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
        )) {
            assertErrors(V1_11, source, ".." to NULLARY_RANGE)
            assertErrors(V1_13, source, ".." to NULLARY_RANGE)
        }

        for (source in listOf("assert (..) == 0..-1//1", "assert 0..-1//1 == (..)")) {
            assertErrors(V1_13, source, ".." to NULLARY_RANGE)
        }
    }

    fun testNullaryRangeFrom1_14() {
        for (source in listOf("..", ".. = right", "require (..), bar", "assert (..) == 0..-1//1", "x = ..\n", "&..\\\n/0")) {
            assertNoErrors(V1_14, source)
            assertNoErrors(V1_20, source)
        }
    }

    fun testPowerOperatorBefore1_13() {
        for ((source, token) in listOf(
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
            "if a do x.** else 1 end" to "else",
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
            "x.** else: 1" to "else",
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
            "x.** é: 1" to "é",
        ) + listOf(
            "*", "..", "<>", "++", "--", "+++", "---", "==", "!=", "===", "!==", "=~", "<", ">", "<=", ">=", "&&", "||", "&&&",
            "|||", "<<<", ">>>", "^^^", "~>", "<~", "<~>", "<|>", "~>>", "<<~", "|", "=", "|>", "::", "<-", "and", "or", "when",
        ).map { "x.** $it y" to "'$it'" }) {
            assertErrors(V1_11, source, "**" to before(token))
            assertErrors(V1_12, source, "**" to before(token))
            assertNoErrors(V1_13, source)
        }

        assertErrors(V1_12, "2 ** 3 ** 4", "**" to before("'*'"), "**" to before("'*'"))
        assertErrors(V1_12, "x.** ** y", "**" to before("'*'"))
        // Both `**` have the same text, so compare where the error starts.
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, V1_12)
        myFixture.configureByText("versioned_syntax_${files++}.ex", "x.** **: 1")
        assertEquals(listOf(5 to before("'*'")), myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.startOffset to it.description })

        for (operator in listOf(
            "+", "-", "++", "--", "<>", "==", "!=", "&&", "||", "..", "@", "!", "^", "~~~", "|", "<-", "->", "\\\\", "|>", "=~", "<",
            ">", "<=", ">=", "===", "!==", "&&&", "|||", "<<<", ">>>", "~>", "<~", "<~>", "<|>", "~>>", "<<~", "&",
            "*", "=", "^^^", "+++", "---", "...", "%", "%{}", "{}", "<<>>",
        )) {
            val source = "x.** $operator: 1"
            val expected = "**" to before("'${operator.replace("\\", "\\\\")}'")

            assertErrors(V1_11, source, expected)
            assertErrors(V1_12, source, expected)
            assertNoErrors(V1_13, source)
        }
        for ((source, token) in listOf("x.** true: 1" to "true", "x.** false: 1" to "false", "x.** Foo: 1" to "'Foo'")) {
            assertErrors(V1_11, source, "**" to before(token))
            assertErrors(V1_12, source, "**" to before(token))
            assertNoErrors(V1_13, source)
        }
        assertEquals(listOf("**" to before("'..//'")), errors(V1_12, "x.** ..//: 1").filter { it.first == "**" })
        assertEquals(emptyList<Pair<String, String?>>(), errors(V1_11, "x.** ..//: 1").filter { it.first == "**" })
        for (source in listOf("x.** /: 1", "x.** ::: 1", "x.** =>: 1", "x.** ..// y", "x.** .. / y", "x.** .. // y", "x.** .. //: 1")) {
            for (languageLevel in listOf(V1_11, V1_12)) {
                assertEquals("$source on $languageLevel", emptyList<String?>(), errors(languageLevel, source).filter { it.first == "**" }.map { it.second })
            }
        }
        assertErrors(V1_12, "x.** !/2", "/" to before("'/'"))

        for ((source, token) in listOf(
            "x.** ==\n/2" to "'=='",
            "x.** in\n/2" to "in",
            "x.** == # c\n/2" to "'=='",
            "x.** ==\n\n/2" to "'=='",
        )) {
            for (languageLevel in listOf(V1_11, V1_12)) {
                assertEquals("$source on $languageLevel", listOf("**" to before(token)), errors(languageLevel, source).filter { it.first == "**" })
            }
        }
        assertErrors(V1_12, "x.** ==\\\n/2", "==" to before("'=='"))

        for (source in listOf("x.**\\\n", "x.** \\\n", "x.**\n\\\n", "x.** # c\n\\\n")) {
            assertErrors(V1_12, source, "**" to "invalid escape \\ at end of file")
        }
        for (source in listOf("x.**\\", "x.** \\")) {
            assertTrue(source, ("**" to "invalid escape \\ at end of file") in errors(V1_12, source))
        }
        assertErrors(V1_12, "[x.**]\\\n", "**" to "invalid escape \\ at end of file")
        assertEquals(emptyList<Pair<String, String?>>(), errors(V1_11, "Kernel.**]").filter { it.first == "**" })
        for (source in listOf("x.** do/2", "x.** do /2", "x.** end/2", "x.** end /2")) {
            assertEquals(source, emptyList<Pair<String, String?>>(), errors(V1_11, source).filter { it.first == "**" })
        }
        assertNoErrors(V1_12, "\"**\"")

        for (source in listOf(
            "Kernel.** 2", "x.** + 1", "x.**()", "x.**(1)", "Kernel.**(2)", "x.**\n1", "x.** - y", "x.** / y", "x.** not y",
            "x.** ! y", "x.** @ y", "x.** # c\n1", "x.** :a", "x.** {1}", "x.** A", "x.**[1]", "x.** y", "x.** fn -> 1 end",
            "x.** ==/2", "x.** == /2", "x.**\n==/2", "[x.** ==/2]", "x.** in/2", "x.** ../2", "x.** |>/2", "x.** =/2", "x.** ::/2",
            "x.** and/2", "x.** when/2", "x.** */2", "x.** <-/2", "x.** |/2", "x.** \\\\/2",
        )) {
            assertNoErrors(V1_11, source)
            assertNoErrors(V1_12, source)
        }

        // Operator references the plugin's parser does not all read, so only the `**` error is checked.
        for (source in listOf(
            "x.** ++/2", "x.** <>/2", "x.** --/2", "x.** +++ /2", "x.** ---\t/2", "x.** |> /2", "x.** <<</2", "x.** === /2",
            "x.** >= /2", "x.** &&& /2", "x.** or/2", "x.** = /2", "x.** :: /2", "x.** ^^^/2", "x.** * /2", "x.** .. /2",
            "x.** in /2", "x.** when /2", "x.** \\\\ /2",
        )) {
            for (languageLevel in listOf(V1_11, V1_12)) {
                assertEquals("$source on $languageLevel", emptyList<Pair<String, String?>>(), errors(languageLevel, source).filter { it.first == "**" })
            }
        }
    }

    fun testMultiLetterSigilBefore1_15() {
        for ((source, character) in listOf(
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
        )) {
            assertErrors(V1_11, source, "$character" to sigilDelimiter(character, 3))
            assertErrors(V1_14, source, "$character" to sigilDelimiter(character, 3))
        }

        assertErrors(V1_14, "x = ~MAT()", "A" to sigilDelimiter('A', 7))
    }

    fun testUppercaseSigilWithDigitsBefore1_17() {
        for (languageLevel in listOf(V1_15, V1_16_2)) {
            assertErrors(languageLevel, "~A1()", "1" to sigilDelimiter('1', 3))
            assertErrors(languageLevel, "~AB1()", "1" to sigilDelimiter('1', 4))
            assertNoErrors(languageLevel, "~MAT()")
        }

        for (source in listOf("~A1()", "~AB1()", "~MAT()", "~UNKNOWN'abc'")) {
            assertNoErrors(V1_17, source)
        }

        for (source in listOf("~A()", "~r()")) {
            assertNoErrors(V1_11, source)
        }
    }

    fun testIdentifierNotInNfcBefore1_14() {
        for ((source, word) in listOf(
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
        )) {
            assertErrors(V1_11, source, word to NFC)
            assertErrors(V1_13, source, word to NFC)
            assertNoErrors(V1_14, source)
        }

        for (source in listOf("\u00E7", "x\u0327", "\"c\u0327\"", ":\"c\u0327\"")) {
            assertNoErrors(V1_13, source)
        }
    }

    fun testIdentifierNotInNfcTooltip() {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, V1_13)
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
        for ((source, expected) in listOf(
            "&+\\\n/2" to ("/" to before("'/'")),
            "&/\\\n/2" to ("/" to before("'/'")),
            "&or\\\n/2" to ("or" to before("'or'")),
            "&..//\\\n/3" to ("..//" to "unexpected token: \".\" (column 2, code point U+002E)"),
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
        )) {
            assertErrors(V1_11, source, expected)
            assertErrors(V1_19, source, expected)
            assertNoErrors(V1_20, source)
        }

        for (source in listOf("&+/\\\n2", "&\\\n+/2", "&foo\\\n/2", "&+/2", "&+ /2", "&<<>>\\\n/1", "&{}\\\n/1", "&%{}\\\n/1")) {
            for (languageLevel in listOf(V1_11, V1_19, V1_20)) {
                assertNoErrors(languageLevel, source)
            }
        }

        assertErrors(V1_12, "&**\\\n/2", "**" to before("'*'"))
        assertErrors(V1_19, "&**\\\n/2", "**" to before("'**'"))
        assertNoErrors(V1_20, "&**\\\n/2")
    }

    fun testEscapedNewlineBeforeArityAfterAnOperandBefore1_20() {
        val named = listOf(before("'/'"), before("'=='"), before("in"), before("'in'"))

        for (source in listOf("x ==\\\n/2", "f ==\\\n/2", "x.y ==\\\n/2")) {
            for (languageLevel in listOf(V1_11, V1_19)) {
                assertEquals("$source on $languageLevel", listOf("/" to before("'/'")), errors(languageLevel, source).filter { it.second in named })
            }
        }

        for (languageLevel in listOf(V1_11, V1_12)) {
            assertEquals("x.** on $languageLevel", listOf("==" to before("'=='")), errors(languageLevel, "x.** ==\\\n/2").filter { it.second in named })
        }
        for (languageLevel in listOf(V1_13, V1_19)) {
            assertEquals("x.** on $languageLevel", listOf("/" to before("'/'")), errors(languageLevel, "x.** ==\\\n/2").filter { it.second in named })
        }

        for (languageLevel in listOf(V1_11, V1_12)) {
            assertEquals("x.** in on $languageLevel", listOf("in" to before("in")), errors(languageLevel, "x.** in\\\n/2").filter { it.second in named })
        }

        for (source in listOf("[==\\\n/2]", "f(==\\\n/2)", "x = ==\\\n/2")) {
            assertEquals(source, listOf("==" to before("'=='")), errors(V1_19, source).filter { it.second in named })
        }
    }

    fun testMapEntriesThatAreNotPairsBefore1_17() {
        for ((source, expected) in listOf(
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
        )) {
            val (entry, token) = expected

            assertErrors(V1_11, source, entry to before(token))
            assertErrors(V1_16_2, source, entry to before(token))
            assertNoErrors(V1_17, source)
        }

        assertErrors(V1_12, "%{...a}", "...a" to before("'}'"))
        assertNoErrors(V1_13, "%{...a}")
        assertErrors(V1_16_2, "%{..}", ".." to before("'}'"))
        assertNoErrors(V1_17, "%{..}")
        assertErrors(V1_16_2, "%{.. \\\n}", ".." to before("'}'"))
        assertErrors(V1_16_2, "%{..\\\n\n}", ".." to before("eol"))

        for (source in listOf("%{a}", "%{a, b}", "%{x.y}", "%{foo()}", "%{...}", "%{a | b}", "%{a: 1}", "%{m | a: 1}", "%{a => 1}", "%{x.()}", "%{foo.bar.()}")) {
            assertNoErrors(V1_11, source)
        }
    }

    fun testSignKeywordKeyAfterACallBefore1_12() {
        for ((source, column) in listOf(
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
        )) {
            assertErrors(V1_11, source, ":" to colon(column))
            assertNoErrors(V1_12, source)
        }

        for (source in listOf("[+: 1]", "f(+: 1)", "%{+: 1}", "f a, +: 1", "f ++: 1", "f when: 1", "f \\\n+: 1", "x.\"f\"+: 1", "x.\"f\" \\\n+: 1")) {
            assertNoErrors(V1_11, source)
        }

        assertEquals(
            emptyList<String?>(),
            errors(V1_11, "f\\\n +: 1").map { it.second }.filter { it?.startsWith("unexpected token") == true }
        )
        assertEquals(
            emptyList<String?>(),
            errors(V1_11, "x.\"f#{a}\" +: 1").map { it.second }.filter { it?.startsWith("unexpected token: \":\"") == true }
        )
        assertEquals(
            emptyList<String?>(),
            errors(V1_11, "x.% +: 1").map { it.second }.filter { it?.startsWith("unexpected token: \":\"") == true }
        )
    }

    fun testDotKeywordKeyBefore1_13() {
        for ((source, column) in listOf("[.: :.]" to 3, "[.: 1]" to 3, "f .: 1" to 4, "f(.: 1)" to 4, "%{.: 1}" to 4)) {
            assertErrors(V1_11, source, ":" to colon(column))
            assertErrors(V1_12, source, ":" to colon(column))
            assertNoErrors(V1_13, source)
        }

        for (source in listOf(":.", "[..: 1]")) {
            assertNoErrors(V1_12, source)
        }
    }

    fun testSteppedRangeAtomBefore1_12() {
        for (source in listOf(":..//", "[:..//]")) {
            assertErrors(V1_11, source, "..//" to before("'/'"))
            assertNoErrors(V1_12, source)
        }
    }

    fun testDoBlockEndBeforeTypeOperatorBefore1_12() {
        for ((source, message) in listOf(
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
        )) {
            assertErrors(V1_11, source, "end" to message)
            assertNoErrors(V1_12, source)
        }

        for (source in listOf(
            "if true do 1 end :: integer",
            "<<if true do \"a\" end :: binary>>",
            "<<(if true do \"a\" end)::binary>>",
        )) {
            assertNoErrors(V1_11, source)
        }
    }

    fun testOperatorAndArityWithoutCaptureBefore1_13() {
        for (source in listOf(
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
        )) {
            assertErrors(V1_11, source, "/" to before("'/'"))
            assertErrors(V1_12, source, "/" to before("'/'"))
            assertNoErrors(V1_13, source)
        }

        for (source in listOf("&@/1", "&+/1", "not/1", "when/2", "@foo/1", ".../1", "&+/1 |> foo", "&+/1 + 1", "&-/1 == x", "&@/1 |> foo", "& +/1", "&\\\n+/2")) {
            assertNoErrors(V1_12, source)
        }
    }

    fun testGraphemeClusterInAQuotedCallNameFrom1_13To1_17() {
        for ((source, name) in listOf(
            ":foo.\"\u0E1A\u0E39\u0E21\u0E40\u0E21\u0E2D\u0E41\u0E23\u0E07\"()" to
                "\"\u0E1A\u0E39\u0E21\u0E40\u0E21\u0E2D\u0E41\u0E23\u0E07\"",
            "foo.\"\u27A1\uFE0F\"()" to "\"\u27A1\uFE0F\"",
            ":foo.\"e\u0301\"()" to "\"e\u0301\"",
            ":foo.'\u0E1A\u0E39'()" to "'\u0E1A\u0E39'",
            ":foo.\"\u0E1A\u0E39\"" to "\"\u0E1A\u0E39\"",
            "Foo.\"\u0E1A\u0E39\"()" to "\"\u0E1A\u0E39\"",
        )) {
            assertNoErrors(V1_12, source)
            assertErrors(V1_13, source, name to NOT_A_LIST_OF_CHARACTERS)
            assertErrors(V1_17, source, name to NOT_A_LIST_OF_CHARACTERS)
            assertNoErrors(V1_18, source)
        }

        for (source in listOf(":\"\u0E1A\u0E39\"", "[\"\u0E1A\u0E39\": 1]", ":foo.\"\u0E1A\u0E21\"()")) {
            assertNoErrors(V1_13, source)
        }
    }

    fun testStepOperatorNotAfterARangeFrom1_12() {
        for (source in listOf(
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
        )) {
            assertErrors(V1_12, source, "//" to STEP)
            assertErrors(V1_20, source, "//" to STEP)
        }

        for (source in listOf("foo..(bar//bat)", "foo++bar//bat", "foo//bar", "(foo//bar)", "[foo//bar]", "&foo//2", "def foo(a // b)")) {
            assertNoErrors(V1_11, source)
        }

        for (source in listOf("a..b//c", "(a..b)//c", "x = 1..2//3", "1..2 + 3//4", "foo..bar++baz//bat", "(foo++bar)..baz//bat")) {
            assertNoErrors(V1_20, source)
        }
    }

    fun testInvalidEscapeInAQuotedCallNameFrom1_18() {
        for ((source, expected) in listOf(
            "a.'\\xg'" to ("\\x" to HEX),
            "a.'\\ug'" to ("\\u" to UNICODE),
            "a.\"\\xg\"()" to ("\\x" to HEX),
            "Foo.\"\\xg\"()" to ("\\x" to HEX),
            "a.'\\u{110000}'" to ("\\u{110000}" to "invalid or reserved Unicode code point \\u{110000}. Syntax error after: \\u"),
            "a.'\\u{D800}'" to ("\\u{D800}" to "invalid or reserved Unicode code point \\u{D800}. Syntax error after: \\u"),
        )) {
            assertNoErrors(V1_17, source)
            assertErrors(V1_18, source, expected)
            assertErrors(V1_20, source, expected)
        }

        for (source in listOf("a.\"\\x41\"()", "a.'\\q'")) {
            assertNoErrors(V1_20, source)
        }
    }

    fun testHexadecimalEscapeWithoutTwoDigitsFrom1_20() {
        for ((source, escape) in listOf(
            "\"\\xA\"" to "\\xA",
            "\"\\x{41}\"" to "\\x{41}",
            "'\\xA'" to "\\xA",
            ":\"\\xA\"" to "\\xA",
            "a.\"\\xA\"()" to "\\xA",
            "a.\"\\x{41}\"()" to "\\x{41}",
        )) {
            assertNoErrors(V1_19, source)
            assertErrors(V1_20, source, escape to HEX)
        }

        for (source in listOf("\"\\xFF\"", "~s(\\xA)", "~S(\\xA)")) {
            assertNoErrors(V1_20, source)
        }

        assertErrors(V1_20, "\"\\x{110000}\"", "\\x{110000}" to HEX)
    }

    fun testElixirInDocumentationIsNotChecked() {
        val source = "defmodule Sample do\n  @moduledoc \"\"\"\n      x = ..\n  \"\"\"\nend\n"

        ElixirLanguageLevelResolver.overrideLanguageLevel(project, V1_13)
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

    private companion object {
        const val NFC = "Elixir expects unquoted Unicode atoms, variables, and calls to be in NFC form."
        const val HEX = "invalid hex escape character, expected \\xHH where H is a hexadecimal digit. Syntax error after: \\x"
        const val UNICODE =
            "invalid Unicode escape character, expected \\uHHHH or \\u{H*} where H is a hexadecimal digit. Syntax error after: \\u"
        const val NOT_A_LIST_OF_CHARACTERS = "errors were found at the given arguments: * 1st argument: not a list of characters"
        const val STEP =
            "the range step operator (//) must immediately follow the range definition operator (..), for example: 1..9//2. " +
                "If you wanted to define a default argument, use (\\\\) instead. Syntax error before: '//'"
        val NULLARY_RANGE = before("'..'")

        fun before(token: String) = "syntax error before: $token"

        fun colon(column: Int) = "unexpected token: \":\" (column $column, code point U+003A)"

        fun sigilDelimiter(character: Char, column: Int) =
            "invalid sigil delimiter: \"$character\" (column $column, code point U+${"%04X".format(character.code)}). " +
                "The available delimiters are: //, ||, \"\", '', (), [], {}, <>"

        fun unexpectedToken(token: String, opener: String) =
            "unexpected token: $token. The \"$opener\" at line 1 is missing terminator \"end\""

        fun missingTerminator(opener: String) = "missing terminator: end (for \"$opener\" starting at line 1)"
    }
}
