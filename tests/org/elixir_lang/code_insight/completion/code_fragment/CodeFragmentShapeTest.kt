package org.elixir_lang.code_insight.completion.code_fragment

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.junit.logs.UnexpectedLogsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

/** The rule each `cursor_context` shape states, checked against observations made by hand. */
class CodeFragmentShapeTest {
    @get:Rule
    val unexpectedLogs = UnexpectedLogsRule()

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
                    arrayOf<OtpErlangObject>(atom("expr"), position(1, 1), position(1, 2)),
                )
            )
        }
    }

    private fun atom(name: String) = OtpErlangAtom(name)

    private fun charlist(text: String) = OtpErlangString(text)

    private fun codePoints(vararg codePoints: Long) = OtpErlangList(codePoints.map(::OtpErlangLong).toTypedArray<OtpErlangObject>())

    private fun tagged(tag: String, text: String) = tuple(atom(tag), charlist(text))

    private fun surround(context: OtpErlangObject, begin: OtpErlangObject, end: OtpErlangObject) =
        OtpErlangMap(arrayOf<OtpErlangObject>(atom("context"), atom("begin"), atom("end")), arrayOf(context, begin, end))

    private fun position(line: Long, column: Long) = tuple(OtpErlangLong(line), OtpErlangLong(column))

    private fun tuple(vararg elements: OtpErlangObject) = OtpErlangTuple(arrayOf(*elements))
}
