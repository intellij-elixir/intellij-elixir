package org.elixir_lang

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.junit.logs.UnexpectedLogsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MacroToStringTest {
    @get:Rule
    val unexpectedLogs = UnexpectedLogsRule()

    // Clause heads

    @Test
    fun fnClauseGuardEndingInAnEndSuffixedVariableIsNotParenthesized() {
        val fn = call(
            "fn",
            arrow(listOf(call("when", variable("a"), variable("b"), call("==", variable("b"), variable("line_end")))), variable("a")),
            arrow(listOf(variable("a"), variable("b")), variable("b"))
        )

        assertClauseHead(fn, "(a, b) when b == line_end ->")
    }

    @Test
    fun caseClauseGuardEndingInAnEndSuffixedVariableIsNotParenthesized() {
        val case = caseOf(
            variable("x"),
            arrow(listOf(call("when", variable("y"), call("==", variable("y"), variable("backend")))), variable("y"))
        )

        assertClauseHead(case, "y when y == backend ->")
    }

    @Test
    fun caseClausePatternThatIsAnEndSuffixedVariableIsNotParenthesized() {
        val case = caseOf(variable("x"), arrow(listOf(variable("line_end")), variable("line_end")))

        assertClauseHead(case, "line_end ->")
    }

    @Test
    fun caseClauseGuardEndingInAnAtomWithACombiningMarkBeforeEndIsNotParenthesized() {
        val atom = "q\u0301end"
        val case = caseOf(
            variable("x"),
            arrow(listOf(call("when", variable("y"), call("==", variable("y"), OtpErlangAtom(atom)))), variable("y"))
        )

        assertClauseHead(case, "y when y == :$atom ->")
    }

    @Test
    fun caseClauseConditionEndingInADoBlockIsStillParenthesized() {
        val inner = caseOf(variable("a"), arrow(listOf(variable("b")), variable("b")))
        val outer = call("cond", keywords("do" to OtpErlangList(arrow(listOf(call("=", variable("source"), inner)), variable("source")))))

        val rendered = Macro.toString(outer)

        assertTrue(rendered, rendered.lines().any { it.trimStart().startsWith("(source = case") })
        assertTrue(rendered, rendered.lines().any { it.trim() == "end )->" })
    }

    // Charlists

    @Test
    fun charlistSingleQuoteIsEscaped() {
        assertEquals("'it\\'s'", Macro.toString(charlist("it's")))
    }

    @Test
    fun charlistNonAsciiWithSingleQuoteIsEscaped() {
        assertEquals("'\u1455\\''", Macro.toString(charlist("\u1455'")))
    }

    @Test
    fun charlistBackslashIsEscaped() {
        assertEquals("'a\\\\'", Macro.toString(charlist("a\\")))
    }

    @Test
    fun charlistInterpolationIsEscaped() {
        assertEquals("'\\#{a}'", Macro.toString(charlist("#{a}")))
    }

    @Test
    fun charlistControlCharactersAreEscaped() {
        val escapeByCharacter = mapOf(
            '\b' to "\\b",
            '\t' to "\\t",
            '\n' to "\\n",
            '\u000B' to "\\v",
            '\u000C' to "\\f",
            '\r' to "\\r",
            '\u001B' to "\\e"
        )

        for ((character, escape) in escapeByCharacter) {
            assertEquals("'\u00E9$escape'", Macro.toString(charlist("\u00E9$character")))
        }
    }

    @Test
    fun charlistLineBreakAndBidirectionalCharactersAreEscaped() {
        val codePoints = buildList {
            add(0x2028)
            add(0x2029)
            addAll(0x202A..0x202E)
            addAll(0x2066..0x2069)
        }

        for (codePoint in codePoints) {
            val escape = "\\u" + "%04X".format(codePoint)

            assertEquals("'a$escape'", Macro.toString(charlist("a" + Character.toString(codePoint))))
        }
    }

    @Test
    fun charlistWithoutSpecialCharactersIsUnchanged() {
        assertEquals("'ab\u00E9'", Macro.toString(charlist("ab\u00E9")))
    }

    // Names

    @Test
    fun localCaptureWritesTheNameAsALocalCall() =
        assertEquals("&reverse_order_by/1", Macro.toString(capture(variable("reverse_order_by"), 1)))

    @Test
    fun remoteCaptureQuotesTheNameAsARemoteCall() =
        assertEquals("&Mod.\"foo bar\"/1", Macro.toString(capture(remote("Elixir.Mod", "foo bar"), 1)))

    @Test
    fun erlangRemoteCaptureQuotesTheNameAsARemoteCall() =
        assertEquals("&:erlang.\"foo bar\"/1", Macro.toString(capture(remote("erlang", "foo bar"), 1)))

    @Test
    fun kernelOperatorCaptureIsWrittenBare() =
        assertEquals("&+/2", Macro.toString(capture(remote("erlang", "+"), 2)))

    @Test
    fun kernelComparisonCaptureIsWrittenBare() =
        assertEquals("&>=/2", Macro.toString(capture(remote("erlang", ">="), 2)))

    @Test
    fun kernelReservedWordCaptureIsWrittenBare() =
        assertEquals("&not/1", Macro.toString(capture(remote("erlang", "not"), 1)))

    @Test
    fun moduleAttributeNameIsNotQuotedAsAnErlangAtom() =
        assertEquals("@_private 1", Macro.toString(call("@", call("_private", OtpErlangLong(1)))))

    private fun capture(nameOrCall: OtpErlangObject, arity: Long): OtpErlangTuple =
        call("&", call("/", nameOrCall, OtpErlangLong(arity)))

    private fun remote(module: String, function: String): OtpErlangTuple =
        OtpErlangTuple(
            arrayOf(
                call(".", OtpErlangAtom(module), OtpErlangAtom(function)),
                OtpErlangList(),
                OtpErlangList(),
            )
        )

    private fun assertClauseHead(macro: OtpErlangObject, expected: String) {
        val rendered = Macro.toString(macro)

        assertTrue("no clause head `$expected` in:\n$rendered", rendered.lines().any { it.trim() == expected })
    }

    private fun charlist(text: String): OtpErlangList =
        OtpErlangList(text.codePoints().toArray().map { OtpErlangLong(it.toLong()) }.toTypedArray<OtpErlangObject>())

    private fun caseOf(subject: OtpErlangObject, vararg clauses: OtpErlangObject): OtpErlangTuple =
        call("case", subject, keywords("do" to OtpErlangList(clauses)))

    private fun keywords(vararg pairs: Pair<String, OtpErlangObject>): OtpErlangList =
        OtpErlangList(pairs.map { (key, value) -> OtpErlangTuple(arrayOf(OtpErlangAtom(key), value)) }.toTypedArray<OtpErlangObject>())

    private fun arrow(left: kotlin.collections.List<OtpErlangObject>, right: OtpErlangObject): OtpErlangTuple =
        call("->", OtpErlangList(left.toTypedArray()), right)

    private fun variable(name: String): OtpErlangTuple =
        OtpErlangTuple(arrayOf(OtpErlangAtom(name), OtpErlangList(), OtpErlangAtom("nil")))

    private fun call(name: String, vararg arguments: OtpErlangObject): OtpErlangTuple =
        OtpErlangTuple(arrayOf(OtpErlangAtom(name), OtpErlangList(), OtpErlangList(arguments)))
}
