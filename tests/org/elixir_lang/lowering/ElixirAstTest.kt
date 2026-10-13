package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.TextRange
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.ReadResult
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.V1
import org.elixir_lang.beam.term.inspect
import org.elixir_lang.expander.appBeams
import org.elixir_lang.junit.UnitTestCase
import org.elixir_lang.lowering.ElixirAst.Alias
import org.elixir_lang.lowering.ElixirAst.Block
import org.elixir_lang.lowering.ElixirAst.Call
import org.elixir_lang.lowering.ElixirAst.ListNode
import org.elixir_lang.lowering.ElixirAst.Literal
import org.elixir_lang.lowering.ElixirAst.Placeholder
import org.elixir_lang.lowering.ElixirAst.Tuple
import org.elixir_lang.lowering.Meta.Key.Entry
import org.elixir_lang.lowering.Meta.Key.Location
import org.elixir_lang.lowering.Meta.Position
import org.elixir_lang.lowering.Meta.Value
import org.elixir_lang.psi.ElixirFile
import java.math.BigInteger

/** [ElixirAst.toOtp] against the terms `Code.string_to_quoted` returns, built by hand. */
class ElixirAstTest : UnitTestCase() {
    fun testAtom() = assertOtp(atom("foo"), Literal.Atom(meta(), "foo"))

    /** [ElixirAst.fromOtp] gives back the arguments, guards and body of every debug info clause of the leg's apps. */
    fun testFromOtpRoundTrips() {
        val origin = TextRange(0, 0)
        var compared = 0
        val differing = appBeams.keys.sorted().flatMap { module ->
            val beam = appBeams.getValue(module)
            val read = BeamReader.readResult(beam.readBytes(), beam.path) { reader ->
                (reader.debugInfoResult.valueOrNull as? V1)?.definitions?.definitionList.orEmpty().flatMap { definition ->
                    (definition.clausesTerm as OtpErlangList).elements().flatMap { clause ->
                        val (_, arguments, guards, body) = (clause as OtpErlangTuple).elements()

                        listOf(arguments, guards, body).mapNotNull { term ->
                            compared++

                            "$module ${definition.name}/${definition.arity}: ${inspect(term)}"
                                .takeIf { ElixirAst.fromOtp(term, origin)?.toOtp() != term }
                        }
                    }
                }
            }

            (read as ReadResult.Present).value
        }

        assertTrue("clauses compared: $compared", compared > 10_000)
        assertEquals("${differing.size} of $compared differ", emptyList<String>(), differing.take(20))
    }

    /** A decoded node's `line` and `column` are its location, as a lowered node's are, and still give back the term. */
    fun testFromOtpKeepsTheLocation() {
        val located = { keys: List<OtpErlangObject> ->
            val term = OtpErlangTuple(arrayOf(atom("f"), OtpErlangList(keys.toTypedArray()), OtpErlangList()))
            val decoded = ElixirAst.fromOtp(term, TextRange(0, 0))!!

            assertEquals(term, decoded.toOtp())

            org.elixir_lang.expander.location(decoded.meta)?.let { it.line to it.column }
        }
        val line = OtpErlangTuple(arrayOf(atom("line"), OtpErlangLong(3)))
        val column = OtpErlangTuple(arrayOf(atom("column"), OtpErlangLong(5)))
        val generated = OtpErlangTuple(arrayOf(atom("generated"), atom("true")))

        assertEquals(3 to 5, located(listOf(generated, line, column)))
        assertEquals(3 to 0, located(listOf(line)))
        assertEquals(null, located(listOf(generated)))
    }

    fun testInteger() = assertOtp(OtpErlangLong(7), integer(7))

    fun testIntegerWiderThanALong() {
        val value = BigInteger.TWO.pow(70)

        assertOtp(OtpErlangLong(value), Literal.Integer(meta(), value))
    }

    fun testFloat() = assertOtp(OtpErlangDouble(1.5), Literal.Float(meta(), 1.5))

    fun testBinaryThatIsNotUtf8() =
        assertOtp(OtpErlangBinary(byteArrayOf(-1)), Literal.Binary(meta(), byteArrayOf(-1)))

    fun testLiteralEmitsNoMetadata() =
        assertOtp(atom("foo"), Literal.Atom(meta(Location(Position(1, 1))), "foo"))

    fun testVariable() = assertOtp(
        tuple(atom("x"), list(line(1)), atom("nil")),
        Call(meta(Location(Position(1, 1))), Literal.Atom(meta(), "x"), null)
    )

    fun testLocalCall() = assertOtp(
        tuple(atom("foo"), list(line(1)), list(atom("a"))),
        Call(meta(Location(Position(1, 1))), Literal.Atom(meta(), "foo"), listOf(Literal.Atom(meta(), "a")))
    )

    fun testRemoteCall() {
        val dot = Call(
            meta(Location(Position(1, 4))),
            Literal.Atom(meta(), "."),
            listOf(Alias(meta(Location(Position(1, 1))), listOf(Literal.Atom(meta(), "Foo"))), Literal.Atom(meta(), "bar"))
        )

        assertOtp(
            tuple(
                tuple(atom("."), list(line(1)), list(tuple(atom("__aliases__"), list(line(1)), list(atom("Foo"))), atom("bar"))),
                list(line(1)),
                OtpErlangList()
            ),
            Call(meta(Location(Position(1, 4))), dot, emptyList())
        )
    }

    fun testArgumentsOfBytesAreAString() = assertOtp(
        tuple(atom("foo"), OtpErlangList(), OtpErlangString("\u0000ÿ")),
        Call(meta(), Literal.Atom(meta(), "foo"), listOf(integer(0), integer(255)))
    )

    fun testArgumentsBeyondAByteAreAList() = assertOtp(
        tuple(atom("foo"), OtpErlangList(), list(OtpErlangLong(1), OtpErlangLong(256))),
        Call(meta(), Literal.Atom(meta(), "foo"), listOf(integer(1), integer(256)))
    )

    fun testEmptyList() = assertOtp(OtpErlangList(), ListNode(meta(), emptyList()))

    fun testCharlist() =
        assertOtp(OtpErlangString("abc"), ListNode(meta(), listOf(integer(97), integer(98), integer(99))))

    fun testListOfIntegersAndAtoms() = assertOtp(
        list(OtpErlangLong(1), atom("a")),
        ListNode(meta(), listOf(integer(1), Literal.Atom(meta(), "a")))
    )

    fun testPair() = assertOtp(
        tuple(atom("a"), atom("b")),
        Tuple(meta(Location(Position(1, 1))), listOf(Literal.Atom(meta(), "a"), Literal.Atom(meta(), "b")))
    )

    fun testTriple() = assertOtp(
        tuple(atom("{}"), list(line(1)), list(atom("a"), atom("b"), atom("c"))),
        Tuple(
            meta(Location(Position(1, 1))),
            listOf(Literal.Atom(meta(), "a"), Literal.Atom(meta(), "b"), Literal.Atom(meta(), "c"))
        )
    )

    fun testEmptyTuple() = assertOtp(
        tuple(atom("{}"), list(line(1)), OtpErlangList()),
        Tuple(meta(Location(Position(1, 1))), emptyList())
    )

    fun testTripleOfBytesIsAString() = assertOtp(
        tuple(atom("{}"), OtpErlangList(), OtpErlangString("\u0001\u0002\u0003")),
        Tuple(meta(), listOf(integer(1), integer(2), integer(3)))
    )

    fun testBlock() = assertOtp(
        tuple(atom("__block__"), OtpErlangList(), list(atom("a"), atom("b"))),
        Block(meta(), listOf(Literal.Atom(meta(), "a"), Literal.Atom(meta(), "b")))
    )

    fun testAlias() = assertOtp(
        tuple(atom("__aliases__"), list(line(2)), list(atom("Foo"), atom("Bar"))),
        Alias(meta(Location(Position(2, 1))), listOf(Literal.Atom(meta(), "Foo"), Literal.Atom(meta(), "Bar")))
    )

    fun testAliasAfterAnExpression() {
        val module = Call(meta(Location(Position(1, 1))), Literal.Atom(meta(), "__MODULE__"), null)

        assertOtp(
            tuple(atom("__aliases__"), list(line(1)), list(tuple(atom("__MODULE__"), list(line(1)), atom("nil")), atom("A"))),
            Alias(meta(Location(Position(1, 11))), listOf(module, Literal.Atom(meta(), "A")))
        )
    }

    fun testPlaceholderIsACursor() = assertOtp(
        tuple(atom("__cursor__"), list(line(3)), OtpErlangList()),
        Placeholder(meta(Location(Position(3, 1))), Placeholder.Reason.Unlowered(ElixirFile::class.java))
    )

    fun testLineWithoutColumnByDefault() =
        assertMeta(list(line(3)), ParserOptions.DEFAULT, Location(Position(3, 5)))

    fun testColumnFollowsLine() = assertMeta(
        list(line(3), keyword("column", OtpErlangLong(5))),
        ParserOptions(columns = true),
        Location(Position(3, 5))
    )

    fun testKeysKeepTheirOrder() {
        assertMeta(
            list(keyword("from_brackets", atom("true")), line(3)),
            ParserOptions.DEFAULT,
            Entry("from_brackets", Value.Atom("true")),
            Location(Position(3, 5))
        )
        assertMeta(
            list(line(3), keyword("from_brackets", atom("true"))),
            ParserOptions.DEFAULT,
            Location(Position(3, 5)),
            Entry("from_brackets", Value.Atom("true"))
        )
    }

    fun testTokenMetadataOnlyWhenAskedFor() {
        val keys = arrayOf(
            Entry("closing", Value.Keywords(listOf(Location(Position(1, 5)))), tokenMetadata = true),
            Location(Position(1, 1))
        )

        assertMeta(list(line(1)), ParserOptions.DEFAULT, *keys)
        assertMeta(list(line(1), keyword("column", OtpErlangLong(1))), ParserOptions(columns = true), *keys)
        assertMeta(
            list(keyword("closing", list(line(1))), line(1)),
            ParserOptions(tokenMetadata = true),
            *keys
        )
        assertMeta(
            list(
                keyword("closing", list(line(1), keyword("column", OtpErlangLong(5)))),
                line(1),
                keyword("column", OtpErlangLong(1))
            ),
            ParserOptions(columns = true, tokenMetadata = true),
            *keys
        )
    }

    fun testValues() = assertMeta(
        list(
            keyword("ambiguous_op", atom("nil")),
            keyword("indentation", OtpErlangLong(2)),
            keyword("delimiter", OtpErlangBinary("\"\"\"".toByteArray())),
            keyword("end_of_expression", list(keyword("newlines", OtpErlangLong(1)), line(1)))
        ),
        ParserOptions(tokenMetadata = true),
        Entry("ambiguous_op", Value.Atom("nil")),
        Entry("indentation", Value.Integer(2)),
        Entry("delimiter", Value.Binary("\"\"\"")),
        Entry(
            "end_of_expression",
            Value.Keywords(listOf(Entry("newlines", Value.Integer(1)), Location(Position(1, 7)))),
            tokenMetadata = true
        )
    )

    private fun assertOtp(expected: OtpErlangObject, ast: ElixirAst) = assertEquals(expected, ast.toOtp())

    private fun assertMeta(expected: OtpErlangList, options: ParserOptions, vararg keys: Meta.Key) =
        assertEquals(expected, meta(*keys).toOtp(options))

    private fun meta(vararg keys: Meta.Key) = Meta(TextRange(0, 0), Position(1, 1), Position(1, 1), keys.toList())

    private fun integer(value: Long) = Literal.Integer(meta(), BigInteger.valueOf(value))

    private fun atom(name: String) = OtpErlangAtom(name)

    private fun line(line: Long) = keyword("line", OtpErlangLong(line))

    private fun keyword(key: String, value: OtpErlangObject) = tuple(atom(key), value)

    private fun list(vararg elements: OtpErlangObject) = OtpErlangList(elements)

    private fun tuple(vararg elements: OtpErlangObject) = OtpErlangTuple(elements)
}
