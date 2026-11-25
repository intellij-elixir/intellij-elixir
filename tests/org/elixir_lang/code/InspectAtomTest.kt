package org.elixir_lang.code

import org.elixir_lang.code.InspectAtom.Class.*
import org.elixir_lang.code.InspectAtom.Form
import org.elixir_lang.junit.UnitTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * [ROWS] are what Elixir 1.20.4's `Macro.inspect_atom/3` writes; `inspect_atom.exs` beside this file prints them, and
 * its header gives the command. Each test compares the whole table, so one run shows every row that disagrees.
 */
class InspectAtomTest : UnitTestCase() {
    fun testClassify() = assertTable(Row::classification) { InspectAtom.classify(it).name }

    fun testLiteral() = assertTable(Row::literal, InspectAtom::literal)

    fun testKey() = assertTable(Row::key, InspectAtom::key)

    fun testRemoteCall() = assertTable(Row::remoteCall, InspectAtom::remoteCall)

    /** Before 1.14 the undefined-function hint wrote names as `Code.Identifier.inspect_as_function/1` did. */
    fun testRemoteCallAtALevel() {
        val names = listOf("**", "//", "foo")

        assertEquals(
            mapOf(
                "1.11.4" to listOf("\"**\"", "\"//\"", "foo"),
                "1.12.3" to listOf("\"**\"", "//", "foo"),
                "1.13.4" to listOf("**", "//", "foo"),
                "1.14.0" to listOf("**", "\"//\"", "foo"),
                "1.18.0" to listOf("**", "\"//\"", "foo"),
            ),
            atEachRelease { level -> names.map { InspectAtom.remoteCall(it, level) } },
        )
    }

    fun testLiteralAtALevel() {
        val atoms = listOf("**", "..//", "foo")

        assertEquals(
            mapOf(
                "1.11.4" to listOf(":\"**\"", ":\"..//\"", ":foo"),
                "1.12.3" to listOf(":\"**\"", ":..//", ":foo"),
                "1.13.4" to listOf(":**", ":..//", ":foo"),
                "1.14.0" to listOf(":**", ":..//", ":foo"),
                "1.18.0" to listOf(":**", ":..//", ":foo"),
            ),
            atEachRelease { level -> atoms.map { InspectAtom.literal(it, level) } },
        )
    }

    fun testSpellingAtALevel() {
        val atoms = listOf("**", "..//", "hello_привет", ".")

        assertEquals(
            mapOf(
                "1.11.4" to listOf(
                    ":\"**\"", "\"**\"", ":\"..//\"", "\"..//\"", ":hello_привет", "hello_привет", ":.", "\".\"",
                ),
                "1.12.3" to listOf(":\"**\"", "\"**\"", ":..//", "..//", ":hello_привет", "hello_привет", ":.", "\".\""),
                "1.13.4" to listOf(":**", "**", ":..//", "..//", ":hello_привет", "hello_привет", ":.", "."),
                "1.14.0" to listOf(":**", "**", ":..//", "..//", ":\"hello_привет\"", "\"hello_привет\"", ":.", "."),
                "1.18.0" to listOf(":**", "**", ":..//", "..//", ":hello_привет", "hello_привет", ":.", "."),
            ),
            atEachRelease { level ->
                atoms.flatMap { atom -> listOf(Form.LITERAL, Form.KEY).map { InspectAtom.spelling(atom, it, level) } }
            },
        )
    }

    /**
     * Before the first release that checks an identifier's scripts, a name of identifier characters is one, though UTS
     * #39 restricts `ƀ` and 1.14 normalizes `µ`.
     */
    fun testClassifyBeforeScriptsAreChecked() {
        val atoms = listOf("привет", "hello_привет", "ƀar", "µ")

        assertEquals(
            mapOf(
                "1.11.4" to listOf("IDENTIFIER", "IDENTIFIER", "IDENTIFIER", "IDENTIFIER"),
                "1.12.3" to listOf("IDENTIFIER", "IDENTIFIER", "IDENTIFIER", "IDENTIFIER"),
                "1.13.4" to listOf("IDENTIFIER", "IDENTIFIER", "IDENTIFIER", "IDENTIFIER"),
                "1.14.0" to listOf("IDENTIFIER", "OTHER", "OTHER", "OTHER"),
                "1.18.0" to listOf("IDENTIFIER", "IDENTIFIER", "OTHER", "OTHER"),
            ),
            atEachRelease { level -> atoms.map { InspectAtom.classify(it, level).name } },
        )
    }

    /**
     * Before 1.14 the tokenizer rejects a name not in NFC, whatever its kind; from 1.14 it reads the name normalized.
     * `\u2126` is OHM SIGN, which normalizes to OMEGA; the others are `Ä` and `ä` decomposed.
     */
    fun testANameNotInNfc() {
        val atoms = listOf("\u2126", "A\u0308x", "a\u0308x", "a\u0308@b")
        val before = listOf(
            "OTHER", ":\"\u2126\"", "\"\u2126\"",
            "OTHER", ":\"A\u0308x\"", "\"A\u0308x\"",
            "OTHER", ":\"a\u0308x\"", "\"a\u0308x\"",
            "OTHER", ":\"a\u0308@b\"", "\"a\u0308@b\"",
        )
        val from = listOf(
            "OTHER", ":\"\u2126\"", "\"\u2126\"",
            "NOT_CALLABLE", ":A\u0308x", "A\u0308x",
            "OTHER", ":\"a\u0308x\"", "\"a\u0308x\"",
            "NOT_CALLABLE", ":a\u0308@b", "a\u0308@b",
        )

        assertEquals(
            mapOf("1.11.4" to before, "1.12.3" to before, "1.13.4" to before, "1.14.0" to from, "1.18.0" to from),
            atEachRelease { level ->
                atoms.flatMap { atom ->
                    listOf(
                        InspectAtom.classify(atom, level).name,
                        InspectAtom.spelling(atom, Form.LITERAL, level),
                        InspectAtom.spelling(atom, Form.KEY, level),
                    )
                }
            },
        )
    }

    fun testLocalCall() {
        val expected = LOCAL_CALLS.joinToString("\n") { (atom, written) -> "$atom -> $written" }
        val actual = LOCAL_CALLS.joinToString("\n") { (atom, _) -> "$atom -> ${InspectAtom.localCall(atom)}" }

        assertEquals(expected, actual)
    }

    private class Row(
        val atom: String,
        classification: InspectAtom.Class,
        val literal: String,
        val key: String,
        val remoteCall: String,
    ) {
        val classification = classification.name
    }

    private fun assertTable(expected: (Row) -> String, actual: (String) -> String) =
        assertEquals(
            ROWS.joinToString("\n") { "${it.atom} -> ${expected(it)}" },
            ROWS.joinToString("\n") { "${it.atom} -> ${actual(it.atom)}" },
        )

    /** [answer] at each of [RELEASES], by release. */
    private fun <T> atEachRelease(answer: (ElixirLanguageLevel) -> T): Map<String, T> =
        RELEASES.associateWith { answer(ElixirLanguageLevel.of(it)) }

    private companion object {
        /**
         * The releases either side of where atoms and identifiers changed: `:..//`, `:**`, the scripts check, and the
         * mixed-script check by underscore chunk.
         */
        val RELEASES = listOf("1.11.4", "1.12.3", "1.13.4", "1.14.0", "1.18.0")

        /** Elixir has no mode for a definition head or local call, so these are the plugin's own rule. */
        val LOCAL_CALLS = listOf(
            "foo" to "foo",
            "foo?" to "foo?",
            "_foo" to "_foo",
            "\u00E9" to "\u00E9",
            "after" to "unquote(:after)",
            "else" to "unquote(:else)",
            "catch" to "unquote(:catch)",
            "rescue" to "unquote(:rescue)",
            "do" to "unquote(:do)",
            "end" to "unquote(:end)",
            "fn" to "unquote(:fn)",
            "true" to "unquote(:true)",
            "false" to "unquote(:false)",
            "nil" to "unquote(:nil)",
            "unquote" to "unquote(:unquote)",
            "unquote_splicing" to "unquote(:unquote_splicing)",
            "__aliases__" to "unquote(:__aliases__)",
            "__block__" to "unquote(:__block__)",
            "in" to "unquote(:in)",
            "when" to "unquote(:when)",
            "not" to "unquote(:not)",
            "^" to "unquote(:^)",
            "+" to "unquote(:+)",
            "::" to "unquote(:\"::\")",
            "%{}" to "unquote(:%{})",
            "=:=" to "unquote(:\"=:=\")",
            "Foo" to "unquote(:Foo)",
            "foo@bar" to "unquote(:foo@bar)",
            "foo bar" to "unquote(:\"foo bar\")",
            "has#{x" to "unquote(:\"has\\#{x\")",
            "Elixir.Foo" to "unquote(Foo)",
        )

        val ROWS = listOf(
            Row("foo", IDENTIFIER, ":foo", "foo:", "foo"),
            Row("foo?", IDENTIFIER, ":foo?", "foo?:", "foo?"),
            Row("foo!", IDENTIFIER, ":foo!", "foo!:", "foo!"),
            Row("_", IDENTIFIER, ":_", "_:", "_"),
            Row("_foo", IDENTIFIER, ":_foo", "_foo:", "_foo"),
            Row("__MODULE__", IDENTIFIER, ":__MODULE__", "__MODULE__:", "__MODULE__"),
            Row("__aliases__", IDENTIFIER, ":__aliases__", "__aliases__:", "__aliases__"),
            Row("__block__", IDENTIFIER, ":__block__", "__block__:", "__block__"),
            Row("nil", IDENTIFIER, "nil", "nil:", "nil"),
            Row("true", IDENTIFIER, "true", "true:", "true"),
            Row("false", IDENTIFIER, "false", "false:", "false"),
            Row("fn", IDENTIFIER, ":fn", "fn:", "fn"),
            Row("do", IDENTIFIER, ":do", "do:", "do"),
            Row("end", IDENTIFIER, ":end", "end:", "end"),
            Row("after", IDENTIFIER, ":after", "after:", "after"),
            Row("else", IDENTIFIER, ":else", "else:", "else"),
            Row("catch", IDENTIFIER, ":catch", "catch:", "catch"),
            Row("rescue", IDENTIFIER, ":rescue", "rescue:", "rescue"),
            Row("unquote", IDENTIFIER, ":unquote", "unquote:", "unquote"),
            Row("unquote_splicing", IDENTIFIER, ":unquote_splicing", "unquote_splicing:", "unquote_splicing"),
            Row("bnot", IDENTIFIER, ":bnot", "bnot:", "bnot"),
            Row("Foo", NOT_CALLABLE, ":Foo", "Foo:", "\"Foo\""),
            Row("DOWN", NOT_CALLABLE, ":DOWN", "DOWN:", "\"DOWN\""),
            Row("EXIT", NOT_CALLABLE, ":EXIT", "EXIT:", "\"EXIT\""),
            Row("Elixir", ALIAS, "Elixir", "\"Elixir\":", "\"Elixir\""),
            Row("Elixir.Foo", ALIAS, "Foo", "\"Elixir.Foo\":", "\"Elixir.Foo\""),
            Row("Elixir.Foo.Bar9_x", ALIAS, "Foo.Bar9_x", "\"Elixir.Foo.Bar9_x\":", "\"Elixir.Foo.Bar9_x\""),
            Row("Elixir.Elixir", ALIAS, "Elixir.Elixir", "\"Elixir.Elixir\":", "\"Elixir.Elixir\""),
            Row("Elixir.Elixir.Foo", ALIAS, "Elixir.Elixir.Foo", "\"Elixir.Elixir.Foo\":", "\"Elixir.Elixir.Foo\""),
            Row("Elixir.foo", OTHER, ":\"Elixir.foo\"", "\"Elixir.foo\":", "\"Elixir.foo\""),
            Row("Elixir.foo bar", OTHER, ":\"Elixir.foo bar\"", "\"Elixir.foo bar\":", "\"Elixir.foo bar\""),
            Row("Elixir.Foo.bar", OTHER, ":\"Elixir.Foo.bar\"", "\"Elixir.Foo.bar\":", "\"Elixir.Foo.bar\""),
            Row("Elixir.Foo-Bar", OTHER, ":\"Elixir.Foo-Bar\"", "\"Elixir.Foo-Bar\":", "\"Elixir.Foo-Bar\""),
            Row("Elixir.my-mod", OTHER, ":\"Elixir.my-mod\"", "\"Elixir.my-mod\":", "\"Elixir.my-mod\""),
            Row("Elixir.Benchfella:tests", OTHER, ":\"Elixir.Benchfella:tests\"", "\"Elixir.Benchfella:tests\":", "\"Elixir.Benchfella:tests\""),
            Row("Elixir.\u00D1", OTHER, ":\"Elixir.\u00D1\"", "\"Elixir.\u00D1\":", "\"Elixir.\u00D1\""),
            Row("Elixir.", OTHER, ":\"Elixir.\"", "\"Elixir.\":", "\"Elixir.\""),
            Row("ElixirFoo", NOT_CALLABLE, ":ElixirFoo", "ElixirFoo:", "\"ElixirFoo\""),
            Row("Foo.Bar", OTHER, ":\"Foo.Bar\"", "\"Foo.Bar\":", "\"Foo.Bar\""),
            Row("foo.bar", OTHER, ":\"foo.bar\"", "\"foo.bar\":", "\"foo.bar\""),
            Row("a.b", OTHER, ":\"a.b\"", "\"a.b\":", "\"a.b\""),
            Row("%", NOT_CALLABLE, ":%", "%:", "\"%\""),
            Row("%{}", NOT_CALLABLE, ":%{}", "%{}:", "\"%{}\""),
            Row("{}", NOT_CALLABLE, ":{}", "{}:", "\"{}\""),
            Row("<<>>", NOT_CALLABLE, ":<<>>", "<<>>:", "\"<<>>\""),
            Row("...", NOT_CALLABLE, ":...", "...:", "\"...\""),
            Row("..", NOT_CALLABLE, ":..", "..:", "\"..\""),
            Row(".", NOT_CALLABLE, ":.", ".:", "\".\""),
            Row("..//", NOT_CALLABLE, ":..//", "..//:", "\"..//\""),
            Row("->", NOT_CALLABLE, ":->", "->:", "\"->\""),
            Row("::", QUOTED_OPERATOR, ":\"::\"", "\"::\":", "::"),
            Row("^^^", QUOTED_OPERATOR, ":\"^^^\"", "\"^^^\":", "^^^"),
            Row("~~~", QUOTED_OPERATOR, ":\"~~~\"", "\"~~~\":", "~~~"),
            Row("<|>", QUOTED_OPERATOR, ":\"<|>\"", "\"<|>\":", "<|>"),
            Row("+", UNQUOTED_OPERATOR, ":+", "+:", "+"),
            Row("-", UNQUOTED_OPERATOR, ":-", "-:", "-"),
            Row("*", UNQUOTED_OPERATOR, ":*", "*:", "*"),
            Row("/", UNQUOTED_OPERATOR, ":/", "/:", "/"),
            Row("**", UNQUOTED_OPERATOR, ":**", "**:", "**"),
            Row("++", UNQUOTED_OPERATOR, ":++", "++:", "++"),
            Row("--", UNQUOTED_OPERATOR, ":--", "--:", "--"),
            Row("+++", UNQUOTED_OPERATOR, ":+++", "+++:", "+++"),
            Row("---", UNQUOTED_OPERATOR, ":---", "---:", "---"),
            Row("<>", UNQUOTED_OPERATOR, ":<>", "<>:", "<>"),
            Row("&&", UNQUOTED_OPERATOR, ":&&", "&&:", "&&"),
            Row("&&&", UNQUOTED_OPERATOR, ":&&&", "&&&:", "&&&"),
            Row("||", UNQUOTED_OPERATOR, ":||", "||:", "||"),
            Row("|||", UNQUOTED_OPERATOR, ":|||", "|||:", "|||"),
            Row("|>", UNQUOTED_OPERATOR, ":|>", "|>:", "|>"),
            Row("\\\\", UNQUOTED_OPERATOR, ":\\\\", "\\\\:", "\\\\"),
            Row("<-", UNQUOTED_OPERATOR, ":<-", "<-:", "<-"),
            Row("=", UNQUOTED_OPERATOR, ":=", "=:", "="),
            Row("==", UNQUOTED_OPERATOR, ":==", "==:", "=="),
            Row("!=", UNQUOTED_OPERATOR, ":!=", "!=:", "!="),
            Row("===", UNQUOTED_OPERATOR, ":===", "===:", "==="),
            Row("!==", UNQUOTED_OPERATOR, ":!==", "!==:", "!=="),
            Row("=~", UNQUOTED_OPERATOR, ":=~", "=~:", "=~"),
            Row("<", UNQUOTED_OPERATOR, ":<", "<:", "<"),
            Row("<=", UNQUOTED_OPERATOR, ":<=", "<=:", "<="),
            Row(">=", UNQUOTED_OPERATOR, ":>=", ">=:", ">="),
            Row(">", UNQUOTED_OPERATOR, ":>", ">:", ">"),
            Row("<<<", UNQUOTED_OPERATOR, ":<<<", "<<<:", "<<<"),
            Row(">>>", UNQUOTED_OPERATOR, ":>>>", ">>>:", ">>>"),
            Row("<~", UNQUOTED_OPERATOR, ":<~", "<~:", "<~"),
            Row("~>", UNQUOTED_OPERATOR, ":~>", "~>:", "~>"),
            Row("<<~", UNQUOTED_OPERATOR, ":<<~", "<<~:", "<<~"),
            Row("~>>", UNQUOTED_OPERATOR, ":~>>", "~>>:", "~>>"),
            Row("<~>", UNQUOTED_OPERATOR, ":<~>", "<~>:", "<~>"),
            Row("|", UNQUOTED_OPERATOR, ":|", "|:", "|"),
            Row("&", UNQUOTED_OPERATOR, ":&", "&:", "&"),
            Row("!", UNQUOTED_OPERATOR, ":!", "!:", "!"),
            Row("^", UNQUOTED_OPERATOR, ":^", "^:", "^"),
            Row("@", UNQUOTED_OPERATOR, ":@", "@:", "@"),
            Row("in", UNQUOTED_OPERATOR, ":in", "in:", "in"),
            Row("when", UNQUOTED_OPERATOR, ":when", "when:", "when"),
            Row("and", UNQUOTED_OPERATOR, ":and", "and:", "and"),
            Row("or", UNQUOTED_OPERATOR, ":or", "or:", "or"),
            Row("not", UNQUOTED_OPERATOR, ":not", "not:", "not"),
            Row("//", OTHER, ":\"//\"", "\"//\":", "\"//\""),
            Row("=:=", OTHER, ":\"=:=\"", "\"=:=\":", "\"=:=\""),
            Row("=/=", OTHER, ":\"=/=\"", "\"=/=\":", "\"=/=\""),
            Row("/=", OTHER, ":\"/=\"", "\"/=\":", "\"/=\""),
            Row("=<", OTHER, ":\"=<\"", "\"=<\":", "\"=<\""),
            Row("=>", OTHER, ":\"=>\"", "\"=>\":", "\"=>\""),
            Row("~=", OTHER, ":\"~=\"", "\"~=\":", "\"~=\""),
            Row("[]", OTHER, ":\"[]\"", "\"[]\":", "\"[]\""),
            Row("&1", OTHER, ":\"&1\"", "\"&1\":", "\"&1\""),
            Row("not in", OTHER, ":\"not in\"", "\"not in\":", "\"not in\""),
            Row("-button_area/1-fun-0-", OTHER, ":\"-button_area/1-fun-0-\"", "\"-button_area/1-fun-0-\":", "\"-button_area/1-fun-0-\""),
            Row("a?b", OTHER, ":\"a?b\"", "\"a?b\":", "\"a?b\""),
            Row("foo!?", OTHER, ":\"foo!?\"", "\"foo!?\":", "\"foo!?\""),
            Row("foo@bar", NOT_CALLABLE, ":foo@bar", "foo@bar:", "\"foo@bar\""),
            Row("foo@", NOT_CALLABLE, ":foo@", "foo@:", "\"foo@\""),
            Row("Foo@bar", NOT_CALLABLE, ":Foo@bar", "Foo@bar:", "\"Foo@bar\""),
            Row("nonode@nohost", NOT_CALLABLE, ":nonode@nohost", "nonode@nohost:", "\"nonode@nohost\""),
            Row("foo bar", OTHER, ":\"foo bar\"", "\"foo bar\":", "\"foo bar\""),
            Row("1a", OTHER, ":\"1a\"", "\"1a\":", "\"1a\""),
            Row("", OTHER, ":\"\"", "\"\":", "\"\""),
            Row("3des-cbc", OTHER, ":\"3des-cbc\"", "\"3des-cbc\":", "\"3des-cbc\""),
            Row("foo-bar", OTHER, ":\"foo-bar\"", "\"foo-bar\":", "\"foo-bar\""),
            Row("with\"quote", OTHER, ":\"with\\\"quote\"", "\"with\\\"quote\":", "\"with\\\"quote\""),
            Row("back\\slash", OTHER, ":\"back\\\\slash\"", "\"back\\\\slash\":", "\"back\\\\slash\""),
            Row("has#{x", OTHER, ":\"has\\#{x\"", "\"has\\#{x\":", "\"has\\#{x\""),
            Row("lone#hash", OTHER, ":\"lone#hash\"", "\"lone#hash\":", "\"lone#hash\""),
            Row("a\u000Ab", OTHER, ":\"a\\nb\"", "\"a\\nb\":", "\"a\\nb\""),
            Row("nul\u0000x", OTHER, ":\"nul\\0x\"", "\"nul\\0x\":", "\"nul\\0x\""),
            Row("del\u007Fx", OTHER, ":\"del\\dx\"", "\"del\\dx\":", "\"del\\dx\""),
            Row("bel\u0007x", OTHER, ":\"bel\\ax\"", "\"bel\\ax\":", "\"bel\\ax\""),
            Row("esc\u001Bx", OTHER, ":\"esc\\ex\"", "\"esc\\ex\":", "\"esc\\ex\""),
            Row("tab\u0009x", OTHER, ":\"tab\\tx\"", "\"tab\\tx\":", "\"tab\\tx\""),
            Row("vt\u000Bx", OTHER, ":\"vt\\vx\"", "\"vt\\vx\":", "\"vt\\vx\""),
            Row("ff\u000Cx", OTHER, ":\"ff\\fx\"", "\"ff\\fx\":", "\"ff\\fx\""),
            Row("bs\u0008x", OTHER, ":\"bs\\bx\"", "\"bs\\bx\":", "\"bs\\bx\""),
            Row("cr\u000Dx", OTHER, ":\"cr\\rx\"", "\"cr\\rx\":", "\"cr\\rx\""),
            Row("soh\u0001x", OTHER, ":\"soh\\x01x\"", "\"soh\\x01x\":", "\"soh\\x01x\""),
            Row("zwsp\u200Bx", OTHER, ":\"zwsp\\u200Bx\"", "\"zwsp\\u200Bx\":", "\"zwsp\\u200Bx\""),
            Row("rlo\u202Ex", OTHER, ":\"rlo\\u202Ex\"", "\"rlo\\u202Ex\":", "\"rlo\\u202Ex\""),
            Row("bom\uFEFFx", OTHER, ":\"bom\\uFEFFx\"", "\"bom\\uFEFFx\":", "\"bom\\uFEFFx\""),
            Row("nbsp\u00A0x", OTHER, ":\"nbsp\\u00A0x\"", "\"nbsp\\u00A0x\":", "\"nbsp\\u00A0x\""),
            Row("ls\u2028x", OTHER, ":\"ls\\u2028x\"", "\"ls\\u2028x\":", "\"ls\\u2028x\""),
            Row("c1\u0085x", OTHER, ":\"c1\\x85x\"", "\"c1\\x85x\":", "\"c1\\x85x\""),
            Row("ffff\uFFFFx", OTHER, ":\"ffff\\x{FFFF}x\"", "\"ffff\\x{FFFF}x\":", "\"ffff\\x{FFFF}x\""),
            Row("\u00E9", IDENTIFIER, ":\u00E9", "\u00E9:", "\u00E9"),
            Row("e\u0301", OTHER, ":\"e\u0301\"", "\"e\u0301\":", "\"e\u0301\""),
            Row("\u00B5", OTHER, ":\"\u00B5\"", "\"\u00B5\":", "\"\u00B5\""),
            Row("\u03BC", IDENTIFIER, ":\u03BC", "\u03BC:", "\u03BC"),
            Row("\u00F1", IDENTIFIER, ":\u00F1", "\u00F1:", "\u00F1"),
            Row("ol\u00E1", IDENTIFIER, ":ol\u00E1", "ol\u00E1:", "ol\u00E1"),
            Row("\u00F3l\u00E1?", IDENTIFIER, ":\u00F3l\u00E1?", "\u00F3l\u00E1?:", "\u00F3l\u00E1?"),
            Row("\u043F\u0440\u0438\u0432\u0435\u0442", IDENTIFIER, ":\u043F\u0440\u0438\u0432\u0435\u0442", "\u043F\u0440\u0438\u0432\u0435\u0442:", "\u043F\u0440\u0438\u0432\u0435\u0442"),
            Row("\u65E5\u672C", IDENTIFIER, ":\u65E5\u672C", "\u65E5\u672C:", "\u65E5\u672C"),
            Row("\u3053\u3093\u306B\u3061\u306F\u4E16\u754C", IDENTIFIER, ":\u3053\u3093\u306B\u3061\u306F\u4E16\u754C", "\u3053\u3093\u306B\u3061\u306F\u4E16\u754C:", "\u3053\u3093\u306B\u3061\u306F\u4E16\u754C"),
            Row("hello_\u043F\u0440\u0438\u0432\u0435\u0442", IDENTIFIER, ":hello_\u043F\u0440\u0438\u0432\u0435\u0442", "hello_\u043F\u0440\u0438\u0432\u0435\u0442:", "hello_\u043F\u0440\u0438\u0432\u0435\u0442"),
            Row("hello\u043F\u0440\u0438\u0432\u0435\u0442", OTHER, ":\"hello\u043F\u0440\u0438\u0432\u0435\u0442\"", "\"hello\u043F\u0440\u0438\u0432\u0435\u0442\":", "\"hello\u043F\u0440\u0438\u0432\u0435\u0442\""),
            Row("\u0430b", OTHER, ":\"\u0430b\"", "\"\u0430b\":", "\"\u0430b\""),
            Row("\uFB01x", OTHER, ":\"\uFB01x\"", "\"\uFB01x\":", "\"\uFB01x\""),
            Row("\u210Cx", OTHER, ":\"\u210Cx\"", "\"\u210Cx\":", "\"\u210Cx\""),
            Row("\u00D1", NOT_CALLABLE, ":\u00D1", "\u00D1:", "\"\u00D1\""),
            Row("\u00D1andu", NOT_CALLABLE, ":\u00D1andu", "\u00D1andu:", "\"\u00D1andu\""),
            Row("\u041F\u0440\u0438\u0432\u0435\u0442", NOT_CALLABLE, ":\u041F\u0440\u0438\u0432\u0435\u0442", "\u041F\u0440\u0438\u0432\u0435\u0442:", "\"\u041F\u0440\u0438\u0432\u0435\u0442\""),
            Row("\uD83D\uDCA5", OTHER, ":\"\uD83D\uDCA5\"", "\"\uD83D\uDCA5\":", "\"\uD83D\uDCA5\""),
            Row("foo_\uD83D\uDCA5", OTHER, ":\"foo_\uD83D\uDCA5\"", "\"foo_\uD83D\uDCA5\":", "\"foo_\uD83D\uDCA5\""),
            Row("\u2160", OTHER, ":\"\u2160\"", "\"\u2160\":", "\"\u2160\""),
            Row("x\u2071", OTHER, ":\"x\u2071\"", "\"x\u2071\":", "\"x\u2071\""),
        )
    }
}
