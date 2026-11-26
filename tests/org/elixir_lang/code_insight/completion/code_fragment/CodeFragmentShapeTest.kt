package org.elixir_lang.code_insight.completion.code_fragment

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.openapi.util.TextRange
import org.elixir_lang.code_insight.completion.code_fragment.Kind.ATTRIBUTE
import org.elixir_lang.code_insight.completion.code_fragment.Kind.CALL
import org.elixir_lang.code_insight.completion.code_fragment.Kind.FIELD
import org.elixir_lang.code_insight.completion.code_fragment.Kind.MODULE
import org.elixir_lang.code_insight.completion.code_fragment.Kind.OTHER
import org.elixir_lang.code_insight.completion.code_fragment.Kind.VARIABLE
import org.elixir_lang.code_insight.completion.code_fragment.Verdict.AGREE
import org.elixir_lang.code_insight.completion.code_fragment.Verdict.DIFFER
import org.elixir_lang.code_insight.completion.code_fragment.Verdict.EMPTY
import org.elixir_lang.code_insight.completion.code_fragment.Verdict.NO_RULE
import org.elixir_lang.junit.logs.UnexpectedLogsRule
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import java.math.BigInteger

/** The rule each `cursor_context` shape states, checked against observations made by hand. */
class CodeFragmentShapeTest {
    @get:Rule
    val unexpectedLogs = UnexpectedLogsRule()

    @Test
    fun testRuleAlias() {
        val cursor = Alias("Hello.Wo")

        assertEquals(none, failed(cursor, module("Hello.World", "Wo")))
        assertEquals(listOf("prefix"), failed(cursor, module("Hello.World", "Hello.Wo")))
        assertEquals(listOf("kind"), failed(cursor, call("Hello.World", "Wo")))
        assertEquals(listOf("owner"), failed(cursor, module("Other.World", "Wo")))
        assertEquals(none, failed(Alias("World.Fo"), module("Hello.World.Foo", "Fo"), scope = helloWorld))
        assertEquals(none, failed(Alias("Wo"), module("Hello.World", "Wo"), module("Kernel", "Wo"), scope = helloWorld))
        assertEquals(listOf("owner"), failed(Alias("Ke"), module("Kernel.SpecialForms", "Ke")))
        assertEquals(listOf("owner"), failed(Alias("li"), module(":lists", "li")))
    }

    @Test
    fun testRuleInsideAlias() {
        val cursor = InsideAlias(LocalOrVar("x"), "Su")

        assertEquals(none, failed(cursor, module("Anything", "Su"), path = listOf(Step("foo/1"), Step("x/1", true)), heads = listOf(Step("foo/1"))))
        assertEquals(listOf("prefix"), failed(cursor, module("Anything", "x.Su")))
        assertEquals(listOf("kind"), failed(cursor, call("Anything", "Su")))
        assertEquals(listOf("container"), failed(cursor, path = listOf(Step("foo/1"), Step("x/1", true)), heads = listOf(Step("bar/1"))))
        assertEquals(
            none,
            failed(InsideAlias(ModuleAttribute("a"), "Su"), path = listOf(Step("@/1"), Step("a/1", true)), heads = emptyList()),
        )
    }

    @Test
    fun testRuleBlockKeywordOrBinaryOperator() {
        val cursor = BlockKeywordOrBinaryOperator("do")

        assertEquals(none, failed(cursor, call("Kernel", "do")))
        assertEquals(listOf("prefix"), failed(cursor, call("Kernel", "d")))
        assertEquals(listOf("kind"), failed(cursor, variable("do", "do")))
    }

    @Test
    fun testRuleDot() {
        val remote = Dot(Alias("Hello"), "wor")

        assertEquals(none, failed(remote, call("Hello", "wor")))
        assertEquals(listOf("prefix"), failed(remote, call("Hello", "Hello.wor")))
        assertEquals(listOf("kind"), failed(remote, module("Hello.World", "wor")))
        assertEquals(listOf("owner"), failed(remote, call("Kernel", "wor")))
        assertEquals(none, failed(Dot(Alias("World"), "f"), call("Hello.World", "f"), scope = helloWorld))

        val members = Dot(Alias("Hello"), "")

        assertEquals(none, failed(members, module("Hello.World", ""), call("Hello", "")))
        assertEquals(listOf("owner"), failed(members, module("Other.World", "")))
        assertEquals(listOf("kind"), failed(members, call("Hello", ""), path = listOf(Step("alias/1", true)), heads = listOf(Step("alias/1"))))

        val atom = Dot(UnquotedAtom("lists"), "rev")

        assertEquals(none, failed(atom, call(":lists", "rev")))
        assertEquals(listOf("owner"), failed(atom, call("lists", "rev")))
        // `foo :lists.` keeps a real `foo/1`.
        assertEquals(listOf("container"), failed(atom, path = listOf(Step("foo/1", true)), heads = emptyList()))

        val variable = Dot(Var("x"), "")

        assertEquals(none, failed(variable, field("a", ""), call("Map", ""), module("Mod", "")))
        assertEquals(listOf("prefix"), failed(Dot(ModuleAttribute("a"), "ke"), field("key", "")))
        assertEquals(none, failed(Dot(Expr, "ba"), variable("bar", "ba"), module("Mod", "ba")))
        assertEquals(listOf("prefix"), failed(Dot(Expr, "ba"), variable("bar", "")))

        assertEquals(none, failed(variable, path = listOf(Step("foo/1"), Step("x/1", true)), heads = listOf(Step("foo/1"), Step("literal"))))
        assertEquals(listOf("container"), failed(variable, path = listOf(Step("foo/1"), Step("x/1", true)), heads = emptyList()))
        assertEquals(none, failed(Dot(ModuleAttribute("a"), ""), path = listOf(Step("@/1"), Step("a/1", true)), heads = emptyList()))
        assertEquals(none, failed(Dot(Dot(Var("map"), "key"), "ne"), path = listOf(Step(".key/1", true)), heads = emptyList()))
        // `foo @a.Foo.b` gives `foo(@a(__cursor__))`.
        assertEquals(
            none,
            failed(Dot(InsideAlias(ModuleAttribute("a"), "Foo"), "b"), path = listOf(Step("foo/1"), Step("@/1"), Step("a/1", true)), heads = listOf(Step("foo/1"))),
        )
        assertEquals(
            none,
            failed(Dot(InsideAlias(LocalOrVar("x"), "Foo"), "b"), path = listOf(Step("foo/1"), Step("x/1", true)), heads = listOf(Step("foo/1"))),
        )
        assertEquals(listOf("container"), failed(remote, path = listOf(Step("foo/1", true)), heads = emptyList()))
        assertEquals(listOf("container"), failed(Dot(Expr, ""), path = listOf(Step("x/1", true)), heads = emptyList()))
    }

    @Test
    fun testRuleDotArity() {
        val cursor = DotArity(Alias("Enum"), "map")

        assertEquals(none, failed(cursor, call("Enum", "map")))
        assertEquals(listOf("prefix"), failed(cursor, call("Enum", "ma", name = "map")))
        assertEquals(listOf("kind"), failed(cursor, module("Enum.map", "map")))
        assertEquals(listOf("owner"), failed(cursor, call("List", "map")))
        assertEquals(listOf("name"), failed(cursor, call("Enum", "map", name = "map_join")))
        assertEquals(none, failed(DotArity(Var("x"), "map"), call("List", "map")))
    }

    @Test
    fun testRuleDotCall() {
        val cursor = DotCall(Alias("Mod"), "f")

        assertEquals(none, failed(cursor, variable("x", ""), call("Kernel", "")))
        assertEquals(listOf("prefix"), failed(cursor, variable("x", "f")))
        assertEquals(listOf("kind"), failed(cursor, module("Mod", "")))
    }

    @Test
    fun testRuleAnonymousCall() {
        val cursor = AnonymousCall(Var("f"))

        assertEquals(none, failed(cursor, variable("x", ""), call("Kernel", "")))
        assertEquals(listOf("prefix"), failed(cursor, variable("x", "f")))
        assertEquals(listOf("kind"), failed(cursor, attribute("a", "")))
    }

    @Test
    fun testRuleExpr() {
        assertEquals(none, failed(Expr, module("Mod", ""), call("Kernel", ""), variable("x", "")))
        assertEquals(listOf("prefix"), failed(Expr, variable("x", "x")))
        assertEquals(listOf("kind"), failed(Expr, field("a", "")))
        // IEx offers fields in a struct and in an update, never in a plain map literal.
        val struct = listOf(Step("%/2"), Step("%{}/1"))
        val update = listOf(Step("%{}/1"), Step("|/2", onVariable = true), Step("[]"))
        val structUpdate = listOf(Step("%/2"), Step("%{}/1"), Step("|/2"), Step("[]"))
        val notVariable = listOf(Step("%{}/1"), Step("|/2"), Step("[]"))
        val map = listOf(Step("%{}/2"))
        assertEquals(none, failed(Expr, field("a", ""), path = struct, heads = struct))
        assertEquals(none, failed(Expr, field("a", ""), path = update, heads = update))
        assertEquals(none, failed(Expr, field("a", ""), path = structUpdate, heads = structUpdate))
        // A plain map's update needs a variable on the left of `|`.
        assertEquals(listOf("kind"), failed(Expr, field("a", ""), path = notVariable, heads = notVariable))
        assertEquals(listOf("kind"), failed(Expr, field("a", ""), path = map, heads = map))
        val binary = listOf(Step("<<>>/1"), Step("::/2"))
        assertEquals(none, failed(Expr, other("binary", ""), path = binary, heads = binary))
        assertEquals(none, failed(Expr, other("size", ""), path = binary + Step("-/2"), heads = binary + Step("-/2")))
        assertEquals(listOf("kind"), failed(Expr, variable("x", ""), path = binary, heads = binary))
    }

    @Test
    fun testRuleLocalOrVar() {
        val cursor = LocalOrVar("fo")

        assertEquals(none, failed(cursor, call("Kernel", "fo"), variable("foo", "fo")))
        assertEquals(listOf("prefix"), failed(cursor, call("Kernel", "f")))
        assertEquals(listOf("kind"), failed(cursor, module("Foo", "fo")))
        assertEquals(listOf("kind"), failed(cursor, field("foo", "fo")))
        assertEquals(none, failed(cursor, field("foo", "fo"), path = listOf(Step("%/2"), Step("%{}/2")), heads = listOf(Step("%/2"), Step("%{}/2"))))
        assertEquals(listOf("kind"), failed(cursor, field("foo", "fo"), path = listOf(Step("%{}/2")), heads = listOf(Step("%{}/2"))))
        // `<<x :: bi`: IEx offers only bitstring modifiers.
        val binary = listOf(Step("<<>>/1"), Step("::/2"))
        val modifier = LocalOrVar("bi")
        assertEquals(none, failed(modifier, other("binary", "bi"), path = binary, heads = binary))
        assertEquals(listOf("prefix"), failed(modifier, other("binary", ""), path = binary, heads = binary))
        assertEquals(listOf("kind"), failed(modifier, call("Kernel", "bi", name = "binary_part"), path = binary, heads = binary))
    }

    @Test
    fun testRuleLocalArity() {
        val cursor = LocalArity("local")

        assertEquals(none, failed(cursor, call("Mod", "local")))
        assertEquals(listOf("prefix"), failed(cursor, call("Mod", "loc", name = "local")))
        assertEquals(listOf("kind"), failed(cursor, variable("local", "local")))
        assertEquals(listOf("name"), failed(cursor, call("Mod", "local", name = "locals")))
    }

    @Test
    fun testRuleLocalCall() {
        assertEquals(none, failed(LocalCall("alias"), module("Hello.World", "")))
        assertEquals(listOf("kind"), failed(LocalCall("require"), call("Kernel", "")))
        assertEquals(listOf("prefix"), failed(LocalCall("import"), module("Hello.World", "import")))

        val cursor = LocalCall("foo")

        assertEquals(none, failed(cursor, variable("x", ""), call("Kernel", "")))
        assertEquals(listOf("prefix"), failed(cursor, variable("x", "foo")))
        assertEquals(listOf("kind"), failed(cursor, module("Hello.World", "")))
    }

    @Test
    fun testRuleCaptureArg() {
        val cursor = CaptureArg("&1")

        assertEquals(none, failed(cursor, other("&1", ""), module("Mod", "anything")))
        assertEquals(listOf("container"), failed(cursor, path = listOf(Step("&/1")), heads = emptyList()))
    }

    @Test
    fun testRuleModuleAttribute() {
        val cursor = ModuleAttribute("do")

        assertEquals(none, failed(cursor, attribute("doc", "do")))
        assertEquals(listOf("prefix"), failed(cursor, attribute("doc", "@do")))
        assertEquals(listOf("kind"), failed(cursor, call("Kernel", "do")))
    }

    @Test
    fun testRuleOperator() {
        val cursor = Operator("<")

        assertEquals(none, failed(cursor, call("Kernel", "<", name = "<="), call("Kernel", "<")))
        assertEquals(listOf("prefix"), failed(cursor, call("Kernel", "", name = "<=")))
        assertEquals(listOf("kind"), failed(cursor, variable("x", "<")))
        assertEquals(listOf("name"), failed(cursor, call("Kernel", "<", name = "and")))

        // `<<x::` and `<<x::size(8)-`: IEx offers only bitstring modifiers, with an empty hint.
        val modifier = Operator("::")
        val binary = listOf(Step("<<>>/1"), Step("::/2"))

        assertEquals(none, failed(modifier, other("binary", ""), path = binary, heads = binary))
        assertEquals(listOf("prefix"), failed(modifier, other("binary", "::"), path = binary, heads = binary))
        assertEquals(listOf("kind"), failed(modifier, call("Kernel", "", name = "::"), path = binary, heads = binary))
        assertEquals(none, failed(Operator("-"), other("size", ""), path = binary + Step("-/2"), heads = binary + Step("-/2")))
        // Elsewhere `::` is an operator like any other.
        assertEquals(listOf("kind"), failed(modifier, other("binary", "::")))
        assertEquals(none, failed(modifier, call("Kernel", "::")))
        val nested = listOf(Step("<<>>/1"), Step("{}/2"), Step("::/2"))
        assertEquals(none, failed(modifier, call("Kernel", "::"), path = nested, heads = nested))
        assertEquals(listOf("kind"), failed(modifier, other("binary", "::"), path = nested, heads = nested))
        assertEquals(listOf("kind"), failed(Operator("+"), other("binary", "+"), path = listOf(Step("<<>>/1")), heads = listOf(Step("<<>>/1"))))
    }

    @Test
    fun testRuleOperatorArity() {
        val cursor = OperatorArity("+")

        assertEquals(none, failed(cursor, call("Kernel", "+")))
        assertEquals(listOf("prefix"), failed(cursor, call("Kernel", "", name = "+")))
        assertEquals(listOf("kind"), failed(cursor, variable("+", "+")))
        assertEquals(listOf("name"), failed(cursor, call("Kernel", "+", name = "++")))
    }

    @Test
    fun testRuleOperatorCall() {
        val pipe = OperatorCall("|")

        assertEquals(none, failed(pipe, module("Mod", ""), call("Kernel", ""), variable("x", "")))
        assertEquals(listOf("prefix"), failed(pipe, variable("x", "|")))
        assertEquals(listOf("kind"), failed(pipe, attribute("a", "")))

        val plus = OperatorCall("+")

        assertEquals(none, failed(plus, call("Kernel", ""), variable("x", "")))
        assertEquals(listOf("prefix"), failed(plus, call("Kernel", "+")))
        assertEquals(listOf("kind"), failed(plus, module("Mod", "")))
    }

    @Test
    fun testRuleNone() {
        // An explicit request completes from an empty prefix, and Elixir names no kind there.
        assertEquals(checks(AGREE, NO_RULE, NO_RULE, AGREE), judged(None, variable("x", "")).checks)
        assertEquals(listOf("prefix"), failed(None, variable("x", "x")))
        assertEquals(checks(NO_RULE, NO_RULE, NO_RULE, AGREE), judged(None).checks)
        assertEquals(none, failed(None, path = listOf(Step("foo/1", true)), heads = listOf(Step("foo/1"))))
        assertEquals(listOf("container"), failed(None, path = listOf(Step("x/1", true)), heads = emptyList()))
        // `import Enum, only: [map: 2]|`: a character typed there would join the `]`, so nothing is completed. Heads
        // that agree or differ read the same.
        val glued = listOf(Step("import/2", slot = 1))

        assertEquals(checks(NO_RULE, NO_RULE, NO_RULE, NO_RULE), judged(None, path = glued, heads = emptyList(), afterWhitespace = false).checks)
        assertEquals(checks(NO_RULE, NO_RULE, NO_RULE, NO_RULE), judged(None, path = glued, heads = glued, afterWhitespace = false).checks)
        assertEquals(none, failed(None, path = glued, heads = emptyList(), afterWhitespace = false))
        assertEquals(listOf("container"), failed(None, path = glued, heads = emptyList(), afterWhitespace = true))
        // Where Elixir reads a context, the character before the caret does not matter.
        assertEquals(listOf("container"), failed(LocalOrVar("x"), path = glued, heads = emptyList(), afterWhitespace = false))
        assertEquals(listOf("container"), failed(Expr, path = glued, heads = emptyList(), afterWhitespace = false))
    }

    @Test
    fun testRuleInsideALiteral() {
        // `"ab|`: completion offers nothing Elixir's answers describe.
        val literal = { literal: Boolean ->
            judged(LocalOrVar("ab"), module("Mod", "x"), span = Span(5, 8), path = listOf(Step("foo/1")), heads = emptyList(), literal = literal)
        }

        assertEquals(checks(DIFFER, DIFFER, DIFFER, DIFFER), literal(false).checks)
        assertEquals(checks(NO_RULE, NO_RULE, NO_RULE, NO_RULE), literal(true).checks)
        assertEquals(none, literal(true).failed)
        assertEquals(checks(NO_RULE, NO_RULE, NO_RULE, NO_RULE), judged(LocalOrVar("ab"), literal = true).checks)
    }

    @Test
    fun testRuleKeywordColon() {
        // `key:|` and `t :|:`: no atom starts at the colon.
        assertEquals(checks(NO_RULE, NO_RULE, NO_RULE, AGREE), judged(UnquotedAtom(""), variable("key", "key")).checks)
        assertEquals(checks(NO_RULE, NO_RULE, NO_RULE, AGREE), judged(UnquotedAtom("")).checks)
        // `:|`, where an atom starts.
        assertEquals(listOf("prefix", "kind"), failed(UnquotedAtom(""), variable("key", "key"), atomStart = true))
        assertEquals(none, failed(UnquotedAtom(""), module(":lists", ""), atomStart = true))
    }

    @Test
    fun testRuleVariableDot() {
        // IEx offers what the variable's value holds at runtime.
        assertEquals(checks(AGREE, NO_RULE, NO_RULE, AGREE), judged(Dot(Var("x"), ""), variable("y", "")).checks)
        assertEquals(checks(AGREE, NO_RULE, NO_RULE, AGREE), judged(Dot(Var("x"), "ke"), module("Mod", "ke")).checks)
        assertEquals(checks(AGREE, NO_RULE, NO_RULE, AGREE), judged(Dot(Dot(Var("map"), "key"), "ne"), module("Mod", "ne")).checks)
        assertEquals(checks(AGREE, NO_RULE, NO_RULE, AGREE), judged(Dot(Var("__MODULE__"), ""), variable("y", "")).checks)
        // Prefix, range and container keep their rules.
        assertEquals(listOf("prefix"), failed(Dot(Var("x"), "ke"), field("key", "")))
        assertEquals(listOf("range end"), failed(Dot(Var("x"), "ke"), field("key", "ke"), span = Span(8, 10), identifierEnd = 11))
        assertEquals(listOf("container"), failed(Dot(Var("x"), ""), path = listOf(Step("foo/1"), Step("x/1", true)), heads = emptyList()))
        // An attribute's dot keeps its kinds rule.
        assertEquals(listOf("kind"), failed(Dot(ModuleAttribute("a"), ""), variable("y", "")))
        assertEquals(listOf("kind"), failed(Dot(Dot(ModuleAttribute("a"), "b"), ""), variable("y", "")))
    }

    @Test
    fun testRuleEndKeyword() {
        val path = listOf(Step("foo/1"))
        val heads = listOf(Step("def/2"))

        // `end|`: the plugin still reads inside the block that `end` closes.
        assertEquals(none, failed(BlockKeywordOrBinaryOperator("end"), path = path, heads = heads, surround = Keyword("end"), span = Span(7, 10)))
        // `en|d`
        assertEquals(
            listOf("container"),
            failed(BlockKeywordOrBinaryOperator("en"), path = path, heads = heads, surround = Keyword("end"), span = Span(8, 11), identifierEnd = 11),
        )
        assertEquals(
            listOf("container"),
            failed(BlockKeywordOrBinaryOperator("do"), path = path, heads = heads, surround = Keyword("do"), span = Span(8, 10)),
        )
    }

    @Test
    fun testRuleSigil() {
        assertEquals(none, failed(Sigil(""), call("Kernel", "", name = "sigil_s")))
        assertEquals(listOf("prefix"), failed(Sigil(""), call("Kernel", "~", name = "sigil_s")))
        assertEquals(none, failed(Sigil(""), call("Arrows", "~", name = "~>")))
        assertEquals(listOf("prefix"), failed(Sigil(""), call("Arrows", "", name = "~>")))
        assertEquals(listOf("kind"), failed(Sigil(""), other("(", "")))
        assertEquals(none, failed(Sigil("s"), other("(", "")))
        assertEquals(listOf("prefix"), failed(Sigil("s"), other("(", "s")))
        assertEquals(listOf("kind"), failed(Sigil("s"), call("Kernel", "", name = "sigil_s")))
    }

    @Test
    fun testRuleStruct() {
        val name = Struct(StructName("Hello.Wor"))

        assertEquals(none, failed(name, module("Hello.World", "Wor")))
        assertEquals(listOf("prefix"), failed(name, module("Hello.World", "Hello.Wor")))
        assertEquals(listOf("kind"), failed(name, call("Hello", "Wor")))

        val members = Struct(Dot(Alias("Hello"), ""))

        assertEquals(none, failed(members, module("Hello.World", "")))
        assertEquals(listOf("owner"), failed(members, module("Other.World", "")))
        assertEquals(listOf("kind"), failed(members, call("Hello", "")))

        assertEquals(listOf("prefix"), failed(members, module("Hello.World", "W")))

        assertEquals(none, failed(Struct(InsideAlias(ModuleAttribute("s"), "Wo")), module("Anything", "Wo")))
        assertEquals(listOf("prefix"), failed(Struct(InsideAlias(ModuleAttribute("s"), "Wo")), module("Anything", "s.Wo")))
        assertEquals(listOf("kind"), failed(Struct(InsideAlias(ModuleAttribute("s"), "Wo")), attribute("s", "Wo")))
        assertEquals(none, failed(Struct(InsideAlias(Dot(LocalOrVar("foo"), "bar"), "Baz")), module("Anything", "Baz")))
        assertEquals(none, failed(Struct(LocalOrVar("s")), variable("s", "s"), call("Kernel", "s")))
        assertEquals(listOf("prefix"), failed(Struct(LocalOrVar("s")), variable("s", "")))
        assertEquals(listOf("kind"), failed(Struct(LocalOrVar("s")), attribute("s", "s")))
        assertEquals(none, failed(Struct(ModuleAttribute("s")), attribute("s", "s")))
        assertEquals(listOf("prefix"), failed(Struct(ModuleAttribute("s")), attribute("s", "@s")))
        assertEquals(listOf("kind"), failed(Struct(ModuleAttribute("s")), variable("s", "s")))
        assertEquals(none, failed(Struct(Dot(LocalOrVar("x"), "y")), field("y", "y"), call("Kernel", "y")))
        assertEquals(listOf("kind"), failed(Struct(Dot(LocalOrVar("x"), "y")), module("Mod", "y")))
        assertEquals(listOf("owner"), failed(Struct(Dot(Alias("Hello"), "y")), call("Other", "y")))
    }

    @Test
    fun testRuleUnquotedAtom() {
        val cursor = UnquotedAtom("li")

        assertEquals(none, failed(cursor, module(":lists", "li")))
        assertEquals(listOf("prefix"), failed(cursor, module(":lists", ":li")))
        assertEquals(listOf("kind"), failed(cursor, call(":lists", "li")))
    }

    @Test
    fun testRange() {
        val span = Span(5, 8)

        assertEquals(none, failed(LocalOrVar("ab"), variable("abc", "ab"), span = span, caret = 7, identifierEnd = 8))
        assertEquals(listOf("range begin"), failed(CaptureArg("&1"), other("ab", "ab"), other("b", "b"), span = span, caret = 7, identifierEnd = 8))
        assertEquals(listOf("range end"), failed(LocalOrVar("ab"), variable("abc", "ab"), span = span, caret = 7, identifierEnd = 7))
        assertEquals(none, failed(LocalOrVar("ab"), span = Span(3, 8), caret = 7, identifierEnd = 8))
        assertEquals(listOf("range end"), failed(LocalOrVar("ab"), span = Span(3, 8), caret = 7, identifierEnd = 9))
        // After a sigil's letter the span is the letter, which IEx's delimiters do not replace.
        assertEquals(none, failed(Sigil("s"), other("(", ""), span = Span(2, 3), caret = 3, identifierEnd = 3))
        assertEquals(listOf("prefix", "range begin"), failed(Sigil("s"), other("(", "s"), span = Span(2, 3), caret = 3, identifierEnd = 3))
        assertEquals(listOf("range end"), failed(Sigil("s"), other("(", ""), span = Span(2, 3), caret = 3, identifierEnd = 4))
        // At `a ~|> b` the span is the operator `~>`: IEx inserts a letter at the caret and a `~` operator over the `~`.
        assertEquals(none, failed(Sigil(""), call("Kernel", "", "x"), call("Kernel", "~", "~>>"), span = Span(232, 234), caret = 233, identifierEnd = 234))
        assertEquals(listOf("prefix", "range begin"), failed(Sigil(""), call("Kernel", "~", "x"), span = Span(232, 234), caret = 233, identifierEnd = 234))
    }

    @Test
    fun testSpan() {
        val column = { position: Position -> position.column - 1 }

        fun span(context: SurroundContext, text: String, caret: Int) =
            span(Surround(context, Position(1, 1), Position(1, text.length + 1)), column, caret, text)

        // `alias Hello|.World` compares `Hello`.
        assertEquals(Span(0, 5), span(Alias("Hello.World"), "Hello.World", 3))
        assertEquals(Span(0, 5), span(Alias("Hello.World"), "Hello.World", 5))
        assertEquals(Span(6, 11), span(Alias("Hello.World"), "Hello.World", 6))
        assertEquals(Span(6, 11), span(Alias("Hello.World"), "Hello.World", 11))
        assertEquals(Span(0, 3), span(Dot(Var("map"), "key"), "map.key", 2))
        assertEquals(Span(4, 7), span(Dot(Var("map"), "key"), "map.key", 7))
        assertEquals(Span(1, 2), span(Dot(ModuleAttribute("a"), "b"), "@a.b", 2))
        assertEquals(Span(1, 6), span(Dot(UnquotedAtom("lists"), "rev"), ":lists.rev", 3))
        assertEquals(Span(2, 5), span(InsideAlias(LocalOrVar("x"), "Foo.Bar"), "x.Foo.Bar", 4))
        assertEquals(Span(5, 8), span(Struct(InsideAlias(Dot(LocalOrVar("foo"), "bar"), "Baz")), "%foo.bar.Baz", 6))
        assertEquals(Span(0, 2), span(Operator(".."), "..", 1))
        assertEquals(Span(6, 9), span(Alias("Foo.Bar"), "Foo . Bar", 7))
        // After an `:expr` receiver Elixir's `begin` is its `end`.
        assertEquals(Span(9, 14), span(Surround(Dot(Expr, "field"), Position(1, 15), Position(1, 15)), column, 10, "local(1).field"))
        // Joined lines `...` and `..`: Elixir's `...` ends at the second line's `..`, which is not its text.
        assertNull(
            span(
                Surround(Operator("..."), Position(1, 3), Position(2, 3)),
                { position -> listOf(0, 4)[position.line - 1] + position.column - 1 },
                5,
                "...\n.. ..",
            ),
        )
        // `%|@s`: the caret is before the attribute's name.
        assertNull(span(Struct(ModuleAttribute("s")), "%@s", 1))
        // `h = &|+/2`: Elixir answers the `/`, after the caret.
        assertNull(span(Surround(Operator("/"), Position(1, 7), Position(1, 8)), column, 5, "h = &+/2"))

        // Joined lines: Elixir's `begin` and `end` are on the lines the segments are on.
        val joined = "Hello.\n  World.\n  wor"
        val lines = { position: Position -> if (position.line == 1) position.column - 1 else 7 + position.column - 1 }

        assertEquals(Span(0, 5), span(Surround(Alias("Hello.World"), Position(1, 1), Position(2, 8)), lines, 1, joined))
        assertEquals(Span(9, 14), span(Surround(Alias("Hello.World"), Position(1, 1), Position(2, 8)), lines, 10, joined))
        // `Hello.World.|`: Elixir's member is on the next line.
        assertNull(
            span(
                Surround(Dot(Alias("Hello.World"), "wor"), Position(1, 1), Position(3, 6)),
                { position -> listOf(0, 7, 16)[position.line - 1] + position.column - 1 },
                15,
                joined,
            ),
        )
    }

    @Test
    fun testChecks() {
        val span = Span(9, 10)

        assertEquals(checks(AGREE, AGREE, NO_RULE, AGREE), judged(LocalOrVar("x"), variable("x", "x")).checks)
        // With no item the prefix compares nothing, and the kinds check is empty.
        assertEquals(checks(NO_RULE, EMPTY, NO_RULE, AGREE), judged(LocalOrVar("x")).checks)
        assertEquals(checks(NO_RULE, NO_RULE, NO_RULE, NO_RULE), judged(CaptureArg("&1"), other("x", "x"), path = null).checks)
        // Each check decides alone.
        assertEquals(
            checks(DIFFER, AGREE, AGREE, DIFFER),
            judged(LocalOrVar("x"), variable("x", "y"), span = span, path = listOf(Step("foo/1")), heads = emptyList()).checks,
        )
        assertEquals(checks(AGREE, DIFFER, AGREE, AGREE), judged(LocalOrVar("x"), module("X", "x"), span = span).checks)
        assertEquals(checks(AGREE, AGREE, DIFFER, AGREE), judged(LocalOrVar("x"), variable("x", "x"), span = span, identifierEnd = 11).checks)
        assertEquals(checks(NO_RULE, EMPTY, DIFFER, AGREE), judged(LocalOrVar("x"), span = Span(9, 11)).checks)
        // The kinds check holds the owner and the name.
        assertEquals(checks(AGREE, DIFFER, NO_RULE, AGREE), judged(Dot(Alias("Hello"), "x"), call("Kernel", "x")).checks)
        assertEquals(checks(AGREE, DIFFER, NO_RULE, AGREE), judged(LocalArity("x"), call("Kernel", "x", name = "xy")).checks)
        assertEquals(checks(NO_RULE, EMPTY, NO_RULE, DIFFER), judged(LocalOrVar("x"), heads = null).checks)
        assertEquals(listOf("container (lowering logged)"), judged(LocalOrVar("x"), heads = null).failed)
        assertEquals(none, judged(LocalOrVar("x"), path = null, heads = null).failed)
        assertEquals(
            listOf("prefix(~c\"\",~c\"a\",~c\"b\",+1)", "kind(module,variable)", "owner(Kernel)"),
            judged(
                Dot(Alias("Hello"), "x"),
                call("Kernel", "x"),
                call("Hello", ""),
                module("Hello.A", "a"),
                call("Hello", "b"),
                call("Hello", "c"),
                variable("x", "x"),
            ).failed,
        )
    }

    @Test
    fun testSegments() {
        assertEquals(listOf("Hello", "World"), segments(Alias("Hello.World")))
        assertEquals(listOf("x", "Su"), segments(InsideAlias(LocalOrVar("x"), "Su")))
        assertEquals(listOf("&1"), segments(CaptureArg("&1")))
        assertEquals(listOf("Hello", "valid?"), segments(Dot(Alias("Hello"), "valid?")))
        assertEquals(listOf("map", "a", "b"), segments(Dot(Dot(Var("map"), "a"), "b")))
        assertEquals(listOf("a"), segments(Key("a")))
        assertEquals(listOf("do"), segments(Keyword("do")))
        assertEquals(listOf("local"), segments(LocalArity("local")))
        assertEquals(listOf("foo"), segments(LocalCall("foo")))
        assertEquals(listOf("foo"), segments(LocalOrVar("foo")))
        assertEquals(listOf("attr"), segments(ModuleAttribute("attr")))
        assertEquals(listOf("..."), segments(Operator("...")))
        assertEquals(listOf("s"), segments(Sigil("s")))
        assertEquals(listOf("URI"), segments(Struct(StructName("URI"))))
        assertEquals(listOf("Hello", "World"), segments(Struct(StructName("Hello.World"))))
        assertEquals(listOf("s", "Wo"), segments(Struct(InsideAlias(ModuleAttribute("s"), "Wo"))))
        assertEquals(listOf("foo", "bar", "Baz"), segments(Struct(InsideAlias(Dot(LocalOrVar("foo"), "bar"), "Baz"))))
        assertEquals(listOf("x", "y"), segments(Struct(Dot(LocalOrVar("x"), "y"))))
        assertEquals(listOf("s"), segments(Struct(LocalOrVar("s"))))
        assertEquals(listOf("s"), segments(Struct(ModuleAttribute("s"))))
        assertEquals(listOf("lists"), segments(UnquotedAtom("lists")))
    }

    /** The plugin sees the arguments after the caret, which Elixir's prefix has not reached. */
    @Test
    fun testContainerSlot() {
        // `def f|`, with the body after the caret.
        val def = listOf(Step("def/1", slot = 0))

        assertEquals(none, failed(LocalOrVar("f"), path = def, heads = listOf(Step("def/2", slot = 0))))
        assertEquals(none, failed(LocalOrVar("f"), path = def, heads = listOf(Step("def/2", slot = 0), Step("f/1", slot = 0))))
        assertEquals(listOf("container"), failed(LocalOrVar("f"), path = def, heads = listOf(Step("def/2", slot = 1))))
        assertEquals(listOf("container"), failed(LocalOrVar("f"), path = def, heads = listOf(Step("defp/2", slot = 0))))
        assertEquals(listOf("container"), failed(LocalOrVar("f"), path = def, heads = listOf(Step("def/1"))))
        assertEquals(listOf("container"), failed(LocalOrVar("f"), path = def, heads = emptyList()))
        // `[a, |`, with elements after the caret.
        val list = listOf(Step("[]", slot = 1))

        assertEquals(none, failed(LocalOrVar("x"), path = list, heads = listOf(Step("[]", slot = 1))))
        assertEquals(listOf("container"), failed(LocalOrVar("x"), path = list, heads = listOf(Step("[]", slot = 0))))
    }

    @Test
    fun testPath() {
        val cursor = node("__cursor__")

        assertEquals(listOf(Step("foo/2", slot = 1)), path(quoted(node("foo", node("bar", OtpErlangLong(1)), cursor))))
        assertEquals(listOf(Step("x/1", true, slot = 0)), path(quoted(node("x", cursor))))
        assertEquals(listOf(Step("@/1", slot = 0), Step("a/1", true, slot = 0)), path(quoted(node("@", node("a", cursor)))))
        assertEquals(listOf(Step(".key/1", true, slot = 0)), path(quoted(tuple(node(".", name("map"), atom("key")), meta, list(cursor)))))
        assertEquals(
            listOf(Step("%{}/2", slot = 1), Step("{}/2", slot = 1)),
            path(quoted(node("%{}", tuple(atom("a"), OtpErlangLong(1)), tuple(atom("b"), cursor)))),
        )
        assertEquals(listOf(Step("[]", slot = 0), Step("{}/3", slot = 2)), path(quoted(list(tuple(atom("{}"), meta, list(atom("a"), atom("b"), cursor))))))
        assertEquals(
            listOf(Step("%{}/1", slot = 0), Step("|/2", onVariable = true, slot = 1), Step("[]", slot = 1)),
            path(quoted(node("%{}", node("|", name("m"), list(tuple(atom("a"), OtpErlangLong(1)), cursor))))),
        )
        assertEquals(
            listOf(Step("%{}/1", slot = 0), Step("|/2", slot = 1), Step("[]", slot = 0)),
            path(quoted(node("%{}", node("|", node("m"), list(cursor))))),
        )
        assertEquals(emptyList<Step>(), path(quoted(tuple(atom("__block__"), meta, list(cursor)))))
        assertThrows(IllegalArgumentException::class.java) { path(quoted(node("x", atom("y")))) }
    }

    @Test
    fun testHeads() {
        fun shown(root: ElixirAst, caret: Int, text: String = "") = heads(root, caret, CompletionUtilCore.DUMMY_IDENTIFIER, text).map(Step::shown)
        val held = CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED
        // `foo(x(1, <argument>))`, the argument at 9 and the dummy at [caret].
        fun heads(argument: ElixirAst, caret: Int = 10) =
            shown(
                ElixirAst.Call(at(0, 99), atom(0, "foo"), listOf(ElixirAst.Call(at(4, 98), atom(4, "x"), listOf(integer(6), argument)))),
                caret,
            )
        fun remote(begin: Int, receiver: ElixirAst, member: ElixirAst.Literal.Atom, vararg arguments: ElixirAst) =
            ElixirAst.Call(
                at(begin, arguments.lastOrNull()?.meta?.origin?.endOffset?.plus(1) ?: member.meta.origin.endOffset),
                ElixirAst.Call(at(begin, member.meta.origin.endOffset), atom(member.meta.origin.startOffset - 1, "."), listOf(receiver, member)),
                arguments.toList(),
            )
        val hello = ElixirAst.Alias(at(9, 14), listOf(atom(9, "Hello")))

        // The node holding the dummy gives no head, wherever its own name holds it.
        assertEquals(listOf("foo/1@0", "x/2@1"), heads(variable(9, 29, "x$held")))
        assertEquals(listOf("foo/1@0", "x/2@1"), heads(ElixirAst.Call(at(9, 37), atom(9, "lo$held"), listOf(ElixirAst.Call(at(30, 37), atom(30, "cal"), listOf(integer(34))))), 11))
        assertEquals(listOf("foo/1@0", "x/2@1"), heads(remote(9, hello, atom(15, "wo$held"), integer(37)), 17))
        assertEquals(listOf("foo/1@0", "x/2@1"), heads(ElixirAst.Alias(at(9, 36), listOf(atom(9, "Hello"), atom(15, "Wo$held"))), 17))
        assertEquals(listOf("foo/1@0", "x/2@1"), heads(ElixirAst.Literal.Atom(at(9, 31), "fo$held"), 12))
        // `String.to_integer("2|", 10)`: the dummy is in the binary, at 29.
        val string = ElixirAst.Alias(at(9, 15), listOf(atom(9, "String")))

        assertEquals(
            listOf("foo/1@0", "x/2@1", ".to_integer/2@0"),
            heads(remote(9, string, atom(16, "to_integer"), ElixirAst.Literal.Binary(at(27, 49), "2$held".toByteArray()), integer(51)), 29),
        )

        // A step whose head is written after the caret is dropped, as no prefix holds it: `a - | - b`, whose dummy is
        // at 4 and whose outer `-` is at 24.
        val dummy = { begin: Int -> variable(begin, begin + 19, held) }
        val minus = { begin: Int, end: Int, operator: Int, left: ElixirAst, right: ElixirAst ->
            ElixirAst.Call(at(begin, end), atom(operator, "-"), listOf(left, right))
        }

        assertEquals(listOf("-/2@1"), shown(minus(0, 27, 24, minus(0, 23, 2, variable(0, 1, "a"), dummy(4)), variable(26, 27, "b")), 4))
        // `foo(a, |, b)`: a head before the caret is kept, whatever follows it.
        assertEquals(
            listOf("foo/3@1"),
            shown(ElixirAst.Call(at(0, 30), atom(0, "foo"), listOf(variable(4, 5, "a"), dummy(7), variable(28, 29, "b"))), 7),
        )
        // `foo(x|.bar(1))`: a remote call's head is its `.`, not its receiver.
        val receiver = variable(4, 24, "x$held")
        val bar = remote(4, receiver, atom(25, "bar"), integer(29))

        assertEquals(listOf("foo/1@0"), shown(ElixirAst.Call(at(0, 32), atom(0, "foo"), listOf(bar)), 5))

        // `[{x}|]`: recovery puts the dummy in an error element after the `}`.
        val tuple = ElixirAst.Tuple(at(1, 4), listOf(variable(2, 3, "x")))

        assertEquals(listOf("[]@1"), shown(ElixirAst.ListNode(at(0, 25), listOf(tuple, error(4, 24))), 4))
        assertEquals(listOf("[]@0"), shown(ElixirAst.ListNode(at(0, 25), listOf(error(3, 24))), 4))
        // Neither a node nor a placeholder that ends at the caret holds it.
        assertEquals(emptyList<String>(), shown(ElixirAst.ListNode(at(0, 5), listOf(tuple)), 4))
        assertEquals(emptyList<String>(), shown(ElixirAst.ListNode(at(0, 5), listOf(error(1, 4))), 4))

        // A step that a token written after the caret defines is dropped as well. Each rule is pinned on both sides.
        val call = { begin: Int, end: Int, name: String, nameBegin: Int, arguments: List<ElixirAst>? ->
            ElixirAst.Call(at(begin, end), atom(nameBegin, name), arguments)
        }

        // `case :ok do _| -> 1 end`: a stab clause's `->` defines its argument list, and the first one its clause list.
        fun case(vararg clauses: ElixirAst): ElixirAst {
            val `do` = ElixirAst.Tuple(at(8, 65), listOf(atom(8, "do"), ElixirAst.ListNode(at(11, 60), clauses.toList())))

            return call(0, 68, "case", 0, listOf(atom(5, "ok"), ElixirAst.ListNode(at(8, 65), listOf(`do`))))
        }
        fun arrow(operator: Int, signature: ElixirAst, body: ElixirAst) =
            call(11, 60, "->", operator, listOf(ElixirAst.ListNode(at(11, 32), listOf(signature)), body))

        val underscore = call(11, 32, "_", 11, listOf(dummy(13)))

        assertEquals(listOf("case/2@1", "[]@0", "{}/2@1", "_/1@0"), shown(case(arrow(33, underscore, integer(36))), 13))
        // Where an earlier clause is written, its `->` is in the prefix, and so is the clause list.
        val earlier = call(11, 11, "->", 11, listOf(ElixirAst.ListNode(at(11, 11), listOf(call(11, 11, "a", 11, null))), integer(11)))

        assertEquals(listOf("case/2@1", "[]@0", "{}/2@1", "[]@1", "_/1@0"), shown(case(earlier, arrow(33, underscore, integer(36))), 13))
        // Once the `->` is written before the caret, the clause is in the prefix.
        assertEquals(listOf("case/2@1", "[]@0", "{}/2@1", "[]@0", "->/2@1"), shown(case(arrow(33, call(11, 12, "_", 11, null), dummy(36))), 36))
        // A caret in the argument list, with the `->` before it, keeps both lists.
        assertEquals(
            listOf("case/2@1", "[]@0", "{}/2@1", "[]@0", "->/2@0", "[]@0", "_/1@0"),
            shown(case(arrow(3, underscore, integer(36))), 13),
        )
        // `fn _| -> 1 end`: the clauses are `fn`'s arguments.
        assertEquals(
            listOf("fn/1@0", "_/1@0"),
            shown(
                call(
                    0,
                    31,
                    "fn",
                    0,
                    listOf(call(3, 28, "->", 24, listOf(ElixirAst.ListNode(at(3, 23), listOf(call(3, 23, "_", 3, listOf(dummy(4))))), integer(27)))),
                ),
                4,
            ),
        )

        // `%{b| => 6}`: a `=>` defines its pair. The key can be a literal or parenthesised.
        val pair = { key: ElixirAst, value: Int -> ElixirAst.Tuple(at(2, 28), listOf(key, integer(value))) }
        val map = { pair: ElixirAst -> call(0, 29, "%{}", 0, listOf(pair)) }

        assertEquals(listOf("%{}/1@0"), shown(map(pair(variable(2, 22, "b$held"), 26)), 3, "%{b$held => 6}"))
        assertEquals(listOf("%{}/1@0"), shown(map(pair(atom(3, "a$held"), 27)), 4, "%{:a$held => 6}"))
        assertEquals(listOf("%{}/1@0"), shown(map(pair(variable(3, 23, "b$held"), 28)), 4, "%{(b$held) => 6}"))
        // A keyword pair and a 2-tuple have no `=>` after their key.
        assertEquals(listOf("%{}/1@0", "{}/2@0"), shown(map(pair(atom(2, "b$held"), 24)), 3, "%{b$held: 6}"))
        assertEquals(
            listOf("{}/2@0"),
            shown(ElixirAst.Tuple(at(0, 26), listOf(variable(1, 21, "b$held"), integer(23))), 2, "{b$held, 6}"),
        )
        // A `=>` in a comment is not a `=>`.
        assertEquals(
            listOf("%{}/1@0", "{}/2@0"),
            shown(map(ElixirAst.Tuple(at(2, 31), listOf(atom(2, "b$held"), integer(29)))), 3, "%{b$held: # =>\n6}"),
        )
        assertEquals(
            listOf("{}/2@0"),
            shown(
                ElixirAst.Tuple(at(0, 30), listOf(variable(1, 21, "b$held"), integer(28))),
                2,
                "{b$held, # =>\n6}",
            ),
        )
        // `%{b => x|}`: the `=>` is before the caret.
        assertEquals(
            listOf("%{}/1@0", "{}/2@1"),
            shown(map(ElixirAst.Tuple(at(2, 28), listOf(variable(2, 3, "b"), dummy(8)))), 8, "%{b => $held }"),
        )

        // `@mod|.Sub`: Elixir places the alias at its `.`, which the prefix has not reached.
        val attribute = call(0, 23, "@", 0, listOf(variable(1, 23, "mod$held")))
        val alias = ElixirAst.Alias(at(0, 27), listOf(attribute, atom(24, "Sub")))

        assertEquals(listOf("@/1@0"), shown(alias, 4, "@mod$held.Sub"))
        assertEquals(listOf("@/1@0"), shown(alias, 4, "@mod$held  .Sub"))
    }

    /**
     * The caret is located by offset, with one rule for every dummy: a node holds the caret when its range holds the
     * inserted dummy's; with nothing inserted, one that starts at the caret doesn't, and one that ends at it does unless
     * it ends in a closing bracket or quote other than a sigil's. The deepest node holding it gives no head where the caret is in its own
     * name, and otherwise gives one at the slot after the children that end before the caret.
     */
    @Test
    fun testHeadsByOffset() {
        fun shown(root: ElixirAst, caret: Int, text: String, dummy: String = "") = heads(root, caret, dummy, text).map(Step::shown)
        fun call(begin: Int, end: Int, name: String, nameBegin: Int, vararg arguments: ElixirAst) =
            ElixirAst.Call(at(begin, end), atom(nameBegin, name), arguments.toList())
        val name = CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED.replaceFirstChar(Char::lowercaseChar)

        // `foo(|bar)`: `bar` starts at the caret, so `foo`'s argument list holds it.
        assertEquals(listOf("foo/1@0"), shown(call(0, 8, "foo", 0, variable(4, 7, "bar")), 4, "foo(bar)"))
        assertEquals(listOf("foo/1@0"), shown(call(0, 27, "foo", 0, variable(4, 26, name + "bar")), 4, "foo(${name}bar)", name))
        // `foo(bar|)`: `bar` ends at the caret and is the name being typed.
        assertEquals(listOf("foo/1@0"), shown(call(0, 8, "foo", 0, variable(4, 7, "bar")), 7, "foo(bar)"))
        // `[a,| b]`: between two elements, at the second's slot.
        assertEquals(
            listOf("[]@1"),
            shown(ElixirAst.ListNode(at(0, 6), listOf(variable(1, 2, "a"), variable(4, 5, "b"))), 3, "[a, b]"),
        )
        // `defmodule A |do`: the `do` block starts at the caret, so `defmodule` holds it after its alias.
        val module = ElixirAst.Alias(at(10, 11), listOf(atom(10, "A")))
        val block = ElixirAst.ListNode(at(12, 18), listOf(ElixirAst.Tuple(at(12, 18), listOf(atom(12, "do")))))

        assertEquals(listOf("defmodule/2@1"), shown(call(0, 18, "defmodule", 0, module, block), 12, "defmodule A do\nend"))
        // `if map do 1 |else 2 end`: the `do` key is lowered at the whole block, so the caret is in its body.
        val doBody = ElixirAst.Tuple(at(7, 22), listOf(ElixirAst.Literal.Atom(at(7, 22), "do"), integer(10)))
        val elseBody = ElixirAst.Tuple(at(12, 18), listOf(ElixirAst.Literal.Atom(at(12, 18), "else"), integer(17, BigInteger.TWO)))
        val `if` = call(0, 22, "if", 0, variable(3, 6, "map"), ElixirAst.ListNode(at(7, 22), listOf(doBody, elseBody)))

        assertEquals(listOf("if/2@1", "[]@0", "{}/2@1"), shown(`if`, 12, "if map do 1 else 2 end"))
        // `if map do 1 else |2 end`: the `do` clause's range holds the `else` clause, and the narrower is taken.
        assertEquals(listOf("if/2@1", "[]@1", "{}/2@1"), shown(`if`, 17, "if map do 1 else 2 end"))
        // `a +| 1`: an operator is not a name, so its call holds the caret at its right operand.
        val plus = call(0, 5, "+", 2, variable(0, 1, "a"), integer(4))

        assertEquals(listOf("+/2@1"), shown(plus, 3, "a + 1"))
        // At the end of the file: a name ending there is being typed, and a list closed there holds nothing.
        assertEquals(emptyList<String>(), shown(ElixirAst.Block(at(0, 3), listOf(variable(0, 3, "foo"))), 3, "foo"))
        assertEquals(
            emptyList<String>(),
            shown(ElixirAst.Block(at(0, 3), listOf(ElixirAst.ListNode(at(0, 3), listOf(variable(1, 2, "a"))))), 3, "[a]"),
        )
        assertEquals(listOf("[]@1"), shown(ElixirAst.ListNode(at(0, 3), listOf(variable(1, 2, "a"))), 3, "[a,"))
        // `%{[a]| => 6}`: the key is closed and the pair's `=>` is after the caret, so the map holds it and no pair does.
        val pair = ElixirAst.Tuple(at(2, 10), listOf(ElixirAst.ListNode(at(2, 5), listOf(variable(3, 4, "a"))), integer(9, 6.toBigInteger())))

        assertEquals(listOf("%{}/1@0"), shown(call(0, 11, "%{}", 0, pair), 5, "%{[a] => 6}"))
        // `~s(x).foo()|`: the call on a sigil is closed, as a sigil's own closer isn't, so nothing holds the caret.
        val sigil = call(0, 5, "sigil_s", 0, ElixirAst.Literal.Binary(at(3, 4), "x".toByteArray()), ElixirAst.ListNode(at(0, 5), emptyList()))
        val foo = ElixirAst.Call(
            at(0, 11),
            ElixirAst.Call(at(0, 9), atom(5, "."), listOf(sigil, atom(6, "foo"))),
            emptyList(),
        )

        assertEquals(emptyList<String>(), shown(foo, 11, "~s(x).foo()"))
    }

    @Test
    fun testScope() {
        fun def(function: String, body: OtpErlangObject) = node("def", name(function), list(tuple(atom("do"), body)))

        // `container_cursor_to_quoted` of a module whose aliases come before a cursor in `def f`, and inside a closed
        // `def g`.
        val container = quoted(
            node(
                "defmodule",
                aliases("M"),
                list(
                    tuple(
                        atom("do"),
                        tuple(
                            atom("__block__"),
                            meta,
                            list(
                                node("alias", aliases("Hello", "World")),
                                node("alias", aliases("Hello", "Other"), list(tuple(atom("as"), aliases("O")))),
                                node("alias", node("__MODULE__")),
                                def("g", node("alias", aliases("Other", "Enum"))),
                                def(
                                    "f",
                                    tuple(atom("__block__"), meta, list(node("alias", aliases("Inner", "Map")), node("__cursor__"))),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        )
        val scope = scope(container)

        assertEquals(mapOf("World" to "Hello.World", "O" to "Hello.Other", "Map" to "Inner.Map"), scope.aliases)
        assertEquals("Hello.World.Fo", scope.expand("World.Fo"))
        assertEquals("Enum", scope.expand("Enum"))
        assertEquals("Kernel", scope.expand("Kernel"))
    }

    @Test
    fun testDecode() {
        val fooBarBaz = tuple(atom("struct"), tuple(atom("alias"), tuple(atom("dot"), tagged("local_or_var", "foo"), charlist("bar")), charlist("Baz")))

        assertEquals(Dot(Alias("Hello"), "wor"), decodeCursor(tuple(atom("dot"), tagged("alias", "Hello"), charlist("wor"))))
        assertEquals(LocalOrVar("olá"), decodeCursor(tuple(atom("local_or_var"), codePoints(111, 108, 225))))
        assertEquals(LocalOrVar("éα"), decodeCursor(tuple(atom("local_or_var"), codePoints(233, 945))))
        assertEquals(Dot(Var("x"), ""), decodeCursor(tuple(atom("dot"), tagged("var", "x"), OtpErlangList())))
        assertEquals(Expr, decodeCursor(atom("expr")))
        assertEquals(None, decodeCursor(atom("none")))
        assertEquals(AnonymousCall(Var("f")), decodeCursor(tuple(atom("anonymous_call"), tagged("var", "f"))))
        assertEquals(InsideAlias(LocalOrVar("x"), "Foo"), decodeCursor(tuple(atom("alias"), tagged("local_or_var", "x"), charlist("Foo"))))
        assertEquals(
            Dot(InsideAlias(ModuleAttribute("a"), "Foo"), "b"),
            decodeCursor(tuple(atom("dot"), tuple(atom("alias"), tagged("module_attribute", "a"), charlist("Foo")), charlist("b"))),
        )
        assertEquals(Struct(StructName("URI")), decodeCursor(tagged("struct", "URI")))
        assertEquals(Struct(Dot(LocalOrVar("x"), "")), decodeCursor(tuple(atom("struct"), tuple(atom("dot"), tagged("local_or_var", "x"), OtpErlangList()))))
        assertEquals(
            Struct(Dot(Dot(LocalOrVar("x"), "y"), "z")),
            decodeCursor(tuple(atom("struct"), tuple(atom("dot"), tuple(atom("dot"), tagged("local_or_var", "x"), charlist("y")), charlist("z")))),
        )
        assertEquals(Struct(InsideAlias(Dot(LocalOrVar("foo"), "bar"), "Baz")), decodeCursor(fooBarBaz))
        assertEquals(
            SurroundAnswer(Surround(Struct(InsideAlias(Dot(LocalOrVar("foo"), "bar"), "Baz")), Position(1, 1), Position(1, 13))),
            decodeSurround(surround(fooBarBaz, position(1, 1), position(1, 13))),
        )
        assertEquals(
            SurroundAnswer(Surround(Alias("Hello.World"), Position(2, 3), Position(2, 14))),
            decodeSurround(surround(tagged("alias", "Hello.World"), position(2, 3), position(2, 14))),
        )
        assertEquals(SurroundAnswer(null), decodeSurround(atom("none")))

        val flags = point(atom("true"), atom("false")).decode()

        assertEquals(true to false, flags.literal to flags.atomStart)
        assertEquals(false to true, point(atom("false"), atom("true")).decode().let { it.literal to it.atomStart })
    }

    @Test
    fun testDecodeRefusesAnUnknownShape() {
        // `surround_context` adds `:keyword`; `cursor_context` never answers it.
        assertThrows(IllegalArgumentException::class.java) { decodeCursor(tuple(atom("keyword"), charlist("do"))) }
        // A dot names a variable `{:var, _}`, except inside a struct, where it is `{:local_or_var, _}`.
        assertThrows(IllegalArgumentException::class.java) { decodeCursor(tuple(atom("dot"), tagged("local_or_var", "x"), charlist("y"))) }
        assertThrows(IllegalArgumentException::class.java) { decodeCursor(tuple(atom("struct"), tuple(atom("dot"), tagged("var", "x"), charlist("y")))) }
        // Only a struct's alias continues a dot.
        assertThrows(IllegalArgumentException::class.java) {
            decodeCursor(tuple(atom("alias"), tuple(atom("dot"), tagged("var", "x"), charlist("y")), charlist("Z")))
        }
        // `surround_context` never answers an empty dot, struct or sigil.
        assertThrows(IllegalArgumentException::class.java) { decodeSurround(surround(tuple(atom("dot"), tagged("var", "x"), OtpErlangList()), position(1, 1), position(1, 3))) }
        assertThrows(IllegalArgumentException::class.java) { decodeSurround(surround(tuple(atom("struct"), OtpErlangList()), position(1, 1), position(1, 2))) }
        assertThrows(IllegalArgumentException::class.java) { decodeSurround(surround(tuple(atom("sigil"), OtpErlangList()), position(1, 1), position(1, 2))) }
        assertThrows(IllegalArgumentException::class.java) { decodeCursor(tuple(atom("mystery"), charlist("x"))) }
        assertThrows(IllegalArgumentException::class.java) { decodeCursor(tuple(atom("dot"), tuple(atom("tuple"), charlist("x")), charlist("y"))) }
        assertThrows(IllegalArgumentException::class.java) { decodeCursor(tuple(atom("local_or_var"), atom("x"))) }
        assertThrows(IllegalArgumentException::class.java) { decodeSurround(atom("expr")) }
        assertThrows(IllegalArgumentException::class.java) {
            decodeSurround(
                OtpErlangMap(
                    arrayOf<OtpErlangObject>(atom("context"), atom("begin"), atom("end")),
                    arrayOf(atom("expr"), position(1, 1), position(1, 2)),
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) { point(atom("nil"), atom("false")).decode() }
        assertThrows(IllegalArgumentException::class.java) { point(atom("false"), OtpErlangLong(1)).decode() }
    }

    private fun point(literal: OtpErlangObject, atomStart: OtpErlangObject) =
        Asked.Point(0, 0, atom("none"), tuple(atom("error"), atom("x")), null, literal, atomStart)

    private val none = emptyList<String>()

    private val helloWorld = Scope(mapOf("World" to "Hello.World"))

    private fun judged(
        cursor: CursorContext,
        vararg items: Item,
        path: List<Step>? = emptyList(),
        heads: List<Step>? = emptyList(),
        span: Span? = null,
        caret: Int = 10,
        identifierEnd: Int = caret,
        scope: Scope = Scope(),
        surround: SurroundContext? = null,
        literal: Boolean = false,
        atomStart: Boolean = false,
        afterWhitespace: Boolean = true,
    ): Judgement =
        judge(
            Elixir(cursor, span, path, surround, literal, atomStart),
            Plugin(caret, items.toList(), identifierEnd, heads),
            scope,
            afterWhitespace,
        )

    private fun failed(
        cursor: CursorContext,
        vararg items: Item,
        path: List<Step>? = emptyList(),
        heads: List<Step>? = emptyList(),
        span: Span? = null,
        caret: Int = 10,
        identifierEnd: Int = caret,
        scope: Scope = Scope(),
        surround: SurroundContext? = null,
        atomStart: Boolean = false,
        afterWhitespace: Boolean = true,
    ): List<String> =
        judged(
            cursor,
            *items,
            path = path,
            heads = heads,
            span = span,
            caret = caret,
            identifierEnd = identifierEnd,
            scope = scope,
            surround = surround,
            atomStart = atomStart,
            afterWhitespace = afterWhitespace,
        )
            .failed
            .map { it.substringBefore('(') }

    private fun checks(prefix: Verdict, kinds: Verdict, range: Verdict, container: Verdict) =
        mapOf(Check.PREFIX to prefix, Check.KINDS to kinds, Check.RANGE to range, Check.CONTAINER to container)

    private fun attribute(name: String, pattern: String) = Item(name, pattern, ATTRIBUTE)

    private fun call(owner: String, pattern: String, name: String = pattern) = Item(name, pattern, CALL, owner)

    private fun field(name: String, pattern: String) = Item(name, pattern, FIELD)

    private fun module(module: String, pattern: String) = Item(module.substringAfterLast('.'), pattern, MODULE, module)

    private fun other(name: String, pattern: String) = Item(name, pattern, OTHER)

    private fun variable(name: String, pattern: String) = Item(name, pattern, VARIABLE)

    private fun quoted(term: OtpErlangObject): ElixirAst = ElixirAst.fromOtp(term, TextRange(0, 1))!!

    private fun at(begin: Int, end: Int) = Meta(TextRange(begin, end), Meta.Position(1, begin + 1), Meta.Position(1, end + 1))

    private fun variable(begin: Int, end: Int, name: String) =
        ElixirAst.Call(at(begin, end), ElixirAst.Literal.Atom(at(begin, end), name), null)

    private fun error(begin: Int, end: Int) = ElixirAst.Placeholder(at(begin, end), ElixirAst.Placeholder.Reason.Error)

    private fun atom(begin: Int, name: String) = ElixirAst.Literal.Atom(at(begin, begin + name.length), name)

    private fun integer(begin: Int, value: BigInteger = BigInteger.ONE) =
        ElixirAst.Literal.Integer(at(begin, begin + value.toString().length), value)

    private val meta = OtpErlangList()

    private fun aliases(vararg segments: String) = tuple(atom("__aliases__"), meta, list(*segments.map(::atom).toTypedArray()))

    private fun node(name: String, vararg arguments: OtpErlangObject) = tuple(atom(name), meta, list(*arguments))

    private fun name(name: String) = tuple(atom(name), meta, atom("nil"))

    private fun list(vararg elements: OtpErlangObject) = OtpErlangList(arrayOf(*elements))

    private fun atom(name: String) = OtpErlangAtom(name)

    private fun charlist(text: String) = OtpErlangString(text)

    private fun codePoints(vararg codePoints: Long) = OtpErlangList(codePoints.map(::OtpErlangLong).toTypedArray<OtpErlangObject>())

    private fun tagged(tag: String, text: String) = tuple(atom(tag), charlist(text))

    private fun surround(context: OtpErlangObject, begin: OtpErlangObject, end: OtpErlangObject) =
        OtpErlangMap(arrayOf<OtpErlangObject>(atom("context"), atom("begin"), atom("end")), arrayOf(context, begin, end))

    private fun position(line: Long, column: Long) = tuple(OtpErlangLong(line), OtpErlangLong(column))

    private fun tuple(vararg elements: OtpErlangObject) = OtpErlangTuple(arrayOf(*elements))
}
