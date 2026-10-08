package org.elixir_lang.intellij_elixir

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.junit.logs.UnexpectedLogsRule
import org.elixir_lang.language_level.ElixirLanguageFeature.ASSOC_ON_MAP_KEY
import org.elixir_lang.language_level.ElixirLanguageFeature.LAST_ON_ALIAS
import org.elixir_lang.language_level.ElixirLanguageFeature.MAP_COLUMN_AT_PERCENT
import org.elixir_lang.language_level.ElixirLanguageFeature.REMOTE_CALL_ON_NAME_LINE
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ParserOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** The quoter's calls for parser options and traced compiles, on the Elixir of the leg running them. */
class QuoterProtocol3Test {
    @get:Rule
    val unexpectedLogs = UnexpectedLogsRule()

    /** Expected terms come from `Code.string_to_quoted!/2` on every CI leg's Elixir. */
    @Test
    fun `quoting with columns and token metadata returns them as this Elixir emits them`() {
        val reply = Quoter.quote("Foo.Bar.baz(%{a => 1})\nx", ParserOptions(columns = true, tokenMetadata = true))

        assertEquals("quoter did not answer ok: $reply", atom("ok"), reply.elementAt(0))
        Quoter.assertQuotedCorrectly(expectedColumnsAndTokenMetadata(), reply.elementAt(1))
    }

    @Test
    fun `compiling returns the probe's message and the tracer's remote call`() {
        val compiled = Quoter.compile(
            """
            defmodule $CALLED do
              def called, do: :ok
            end

            defmodule QuoterProtocol3Test.Caller do
              ${probe("$CALLED.called()")}
            end
            """.trimIndent(),
            COMPILE_TIMEOUT
        )

        assertEquals("compile did not succeed: ${compiled.diagnostics}", atom("ok"), compiled.status)
        assertEquals(listOf(atom("ok")), compiled.messages)
        assertTrue(
            "no {:remote_function, _, $CALLED, :called, 0} among ${compiled.events.size} events",
            compiled.events.any { isRemoteFunction(it, "Elixir.$CALLED", "called", 0) }
        )
    }

    @Test
    fun `compiling leaves no module loaded`() {
        val module = "QuoterProtocol3Test.Unloaded"
        val defined = Quoter.compile("defmodule $module do\nend", COMPILE_TIMEOUT)
        assertEquals("compile did not succeed: ${defined.diagnostics}", atom("ok"), defined.status)

        val probed = Quoter.compile(probe(":code.is_loaded($module)"), COMPILE_TIMEOUT)

        assertEquals("compile did not succeed: ${probed.diagnostics}", atom("ok"), probed.status)
        assertEquals(listOf(atom("false")), probed.messages)
    }

    /** The quoter answers a missed deadline with status `:timeout`; the client must treat that as silence. */
    @Test
    fun `a compile that outlives its timeout fails the test`() {
        val elapsed = measureTime {
            val error = assertThrows(AssertionError::class.java) {
                Quoter.compile("Process.sleep(5_000)", 200.milliseconds)
            }

            assertTrue("failure does not name the timeout: ${error.message}", error.message.orEmpty().contains("timeout"))
        }

        assertTrue("the failure took $elapsed", elapsed < 10.seconds)

        val answered = Quoter.compile(":ok", COMPILE_TIMEOUT)

        assertEquals("compile did not succeed: ${answered.diagnostics}", atom("ok"), answered.status)
    }

    private fun expectedColumnsAndTokenMetadata(): OtpErlangObject {
        val level = ElixirLanguageLevel.parse(Quoter.elixirVersion())!!

        val aliasMeta = if (LAST_ON_ALIAS.isSufficient(level)) {
            list(keyword("last", position(1, 5)), *at(1, 1))
        } else {
            list(*at(1, 1))
        }
        val keyMeta = if (ASSOC_ON_MAP_KEY.isSufficient(level)) {
            list(keyword("assoc", position(1, 17)), *at(1, 15))
        } else {
            list(*at(1, 15))
        }
        val mapColumn = if (MAP_COLUMN_AT_PERCENT.isSufficient(level)) 13L else 14L
        val callColumn = if (REMOTE_CALL_ON_NAME_LINE.isSufficient(level)) 9L else 8L
        val map = tuple(
            atom("%{}"),
            list(keyword("closing", position(1, 21)), *at(1, mapColumn)),
            list(tuple(tuple(atom("a"), keyMeta, atom("nil")), long(1)))
        )
        val call = tuple(
            tuple(
                atom("."),
                list(*at(1, 8)),
                list(tuple(atom("__aliases__"), aliasMeta, list(atom("Foo"), atom("Bar"))), atom("baz"))
            ),
            list(
                keyword("end_of_expression", list(keyword("newlines", long(1)), *at(1, 23))),
                keyword("closing", position(1, 22)),
                *at(1, callColumn)
            ),
            list(map)
        )

        return tuple(atom("__block__"), list(), list(call, tuple(atom("x"), list(*at(2, 1)), atom("nil"))))
    }

    private fun isRemoteFunction(event: OtpErlangObject, module: String, name: String, arity: Long): Boolean {
        val trace = ((event as? OtpErlangTuple)?.elementAt(0) as? OtpErlangTuple) ?: return false

        return trace.arity() == 5 &&
            trace.elementAt(0) == atom("remote_function") &&
            trace.elementAt(2) == atom(module) &&
            trace.elementAt(3) == atom(name) &&
            trace.elementAt(4) == long(arity)
    }

    private companion object {
        const val CALLED = "QuoterProtocol3Test.Called"
        val COMPILE_TIMEOUT = 10.seconds

        /** Source that sends the value of [expression] to the compile's collector. */
        fun probe(expression: String) = "IntellijElixir.Quoter.Probe.send(__ENV__, $expression)"

        fun at(line: Long, column: Long) = arrayOf(keyword("line", long(line)), keyword("column", long(column)))

        fun position(line: Long, column: Long) = list(*at(line, column))

        fun keyword(key: String, value: OtpErlangObject) = tuple(atom(key), value)

        fun atom(name: String) = OtpErlangAtom(name)

        fun long(value: Long) = OtpErlangLong(value)

        fun list(vararg elements: OtpErlangObject) = OtpErlangList(elements)

        fun tuple(vararg elements: OtpErlangObject) = OtpErlangTuple(elements)
    }
}
