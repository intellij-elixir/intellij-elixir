package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangBitstr
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangExternalFun
import com.ericsson.otp.erlang.OtpErlangFun
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangPid
import com.ericsson.otp.erlang.OtpErlangRef
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.TextRange
import org.elixir_lang.NameArity
import org.elixir_lang.expander.interpreter.Unknown
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `elixir_quote:escape/3` of a term, on every leg. Each expected term is what that leg's Elixir writes with `~w` for
 * `Macro.escape/2` of the same term, and `raise` names the exception it raises.
 */
class EscapePortTest {
    @Test
    fun literalsAreThemselves() =
        assertEscaped(list(atom("a"), long(1), OtpErlangDouble(1.5), binary("bin")), "[a,1,1.5,<<98,105,110>>]")

    @Test
    fun aTupleOfOtherThanTwoIsBuilt() =
        assertEscaped(list(tuple(long(1), long(2), long(3)), tuple(), tuple(long(1))), "[{'{}',[],[1,2,3]},{'{}',[],[]},{'{}',[],[1]}]")

    @Test
    fun aPairIsItself() = assertEscaped(tuple(long(1), tuple(long(2), long(3), long(4))), "{1,{'{}',[],[2,3,4]}}")

    @Test
    fun aListIsItself() = assertEscaped(list(long(1), list(long(2))), "[1,[2]]")

    @Test
    fun theEmptyListIsItself() = assertEscaped(list(), "[]")

    @Test
    fun anImproperListBuildsItsTail() {
        assertEscaped(improper(long(1), long(2), tail = long(3)), "[1,{'|',[],[2,3]}]")
        assertEscaped(improper(long(1), long(2), tail = long(3)), "[1,{'|',[],[2,3]}]", unquote = true)
    }

    @Test
    fun aMapListsItsEntriesInTermOrder() =
        assertEscaped(
            map(
                atom("b") to long(1), atom("a") to long(2), long(1) to atom("x"), tuple(long(1)) to atom("y"),
                list() to atom("z"), binary("s") to atom("w"), OtpErlangDouble(2.5) to atom("v"),
            ),
            "{'%{}',[],[{1,x},{2.5,v},{a,2},{b,1},{{'{}',[],[1]},y},{[],z},{<<115>>,w}]}",
        )

    /** Before 1.15 the entries are in key order, an integer before every float; from it, sorted as pairs. */
    @Test
    fun aMapsEqualKeysAreSortedByValueFrom115() {
        assertEscaped(
            map(long(1) to atom("b"), OtpErlangDouble(1.0) to atom("a")),
            "{'%{}',[],[{1,b},{1.0,a}]}",
            "1.15.0-rc.1" to "{'%{}',[],[{1.0,a},{1,b}]}",
        )
        assertEscaped(
            map(long(2) to atom("a"), OtpErlangDouble(1.0) to atom("b")),
            "{'%{}',[],[{2,a},{1.0,b}]}",
            "1.15.0-rc.1" to "{'%{}',[],[{1.0,b},{2,a}]}",
        )
    }

    /**
     * Before 1.15 a map of more than 32 keys is in its hashes' order, which no term decides: Elixir escapes it, in an
     * order the port can't give.
     */
    @Test
    fun aMapOfMoreThan32KeysIsUnreadBefore115() {
        val entries = { size: Int -> (1..size).map { long(it.toLong()) to long(it.toLong()) }.toTypedArray() }
        val written = { size: Int -> (1..size).joinToString(",", "{'%{}',[],[", "]}") { "{$it,$it}" } }

        assertEscaped(map(*entries(32)), written(32))
        assertEscaped(map(*entries(33)), "unread", "1.15.0-rc.1" to written(33))
    }

    @Test
    fun aBitstringOfPartBytesIsBuiltFromItsLeadingBits() {
        assertEscaped(
            list(OtpErlangBitstr(byteArrayOf(0x20), 5), OtpErlangBitstr(byteArrayOf(0xFF.toByte(), 0xA0.toByte()), 5)),
            "[{'<<>>',[],[{'::',[],[1,{size,[],[3]}]},{'::',[],[<<>>,{binary,[],[]}]}]},{'<<>>',[],[{'::',[],[7,{size,[],[3]}]},{'::',[],[<<253>>,{binary,[],[]}]}]}]",
            "1.15.0-rc.0" to "[{'<<>>',[],[{'::',[],[1,{size,[],[3]}]},{'::',[],[<<>>,{binary,[],nil}]}]},{'<<>>',[],[{'::',[],[7,{size,[],[3]}]},{'::',[],[<<253>>,{binary,[],nil}]}]}]",
        )
        assertEscaped(
            OtpErlangBitstr(byteArrayOf(0x80.toByte()), 7),
            "{'<<>>',[],[{'::',[],[1,{size,[],[1]}]},{'::',[],[<<>>,{binary,[],[]}]}]}",
            "1.15.0-rc.0" to "{'<<>>',[],[{'::',[],[1,{size,[],[1]}]},{'::',[],[<<>>,{binary,[],nil}]}]}",
        )
    }

    @Test
    fun anExternalFunIsItsCapture() =
        assertEscaped(
            OtpErlangExternalFun("Elixir.Enum", "map", 2),
            "{'&',[],[{'/',[],[{{'.',[],['Elixir.Enum',map]},[{no_parens,true}],[]},2]}]}",
        )

    @Test
    fun aClosureAReferenceAndAMapHoldingOneRaise() {
        assertEscaped(OtpErlangFun(PID, "Elixir.M", 0, 0, emptyArray()), "raise ArgumentError")
        assertEscaped(REF, "raise ArgumentError")
        assertEscaped(map(atom("a") to REF), "raise ArgumentError")
    }

    /** Keys that have no order raise all the same, whichever the escape reaches first. */
    @Test
    fun aMapKeyedByReferencesOrAClosureRaises() {
        assertEscaped(map(REF to long(1), OtpErlangRef("nonode@nohost", intArrayOf(4, 5, 6), 0) to long(2)), "raise ArgumentError")
        assertEscaped(map(REF to long(1), OtpErlangFun(PID, "Elixir.M", 0, 0, emptyArray()) to long(2)), "raise ArgumentError")
    }

    /** `do_escape/2` escapes every key and value, so one that can't be escaped raises at any depth, in any order. */
    @Test
    fun aMapWithNoOrderHoldingWhatCantBeEscapedRaises() {
        assertEscaped(map(tuple(REF) to long(1), tuple(OtpErlangRef("nonode@nohost", intArrayOf(4, 5, 6), 0)) to long(2)), "raise ArgumentError")
        assertEscaped(map(PID to long(1), OtpErlangPid("nonode@nohost", 2, 0, 0) to tuple(REF)), "raise ArgumentError")
    }

    /** Keys with no order are escaped in an order the port can't give, where nothing raises. */
    @Test
    fun aMapKeyedByPidsIsUnread() =
        assertEscaped(map(PID to long(1), OtpErlangPid("nonode@nohost", 2, 0, 0) to long(2)), "unread")

    @Test
    fun aPidIsItself() =
        LEGS.forEach { leg ->
            assertEquals(leg, EscapeResult.Built(PID), terms(leg).escape(PID))
        }

    /** Elixir's output holds the pid itself; code can't, so the escape into code reads what it can't write. */
    @Test
    fun aPidIsUnreadWhereTheOutputIsCode() = assertEquals("unread", code(PID))

    /**
     * The escape walks past a term it can't read to what comes later in Elixir's order, where Elixir raises or the port
     * declines; it is unread only where nothing else happens.
     */
    @Test
    fun anUnreadTermDoesNotHideALaterRaiseOrDecline() {
        assertEquals("raise ArgumentError", code(tuple(Unknown, long(1), REF)))
        assertEquals("declined", code(tuple(splicing(atom("xs")), long(1), Unknown), unquote = true))
        assertEquals("unread", code(tuple(long(1), long(2), Unknown)))
    }

    /** An unread term before a map with no order doesn't hide what the map's own entries raise. */
    @Test
    fun anUnreadTermBeforeAMapWithNoOrderDoesNotHideItsRaise() =
        assertEscaped(tuple(Unknown, long(1), map(PID to long(1), OtpErlangPid("nonode@nohost", 2, 0, 0) to tuple(REF))), "raise ArgumentError")

    /**
     * A map with no order whose entries hold a term the port can't read and one that raises declines: the unread term
     * may raise too, first. The raise is the map's own, so a later term's raise isn't what Elixir raises.
     */
    @Test
    fun aMapWithNoOrderHoldingAnUnreadTermAndARaiseDeclines() {
        val unordered = map(long(1) to Unknown, OtpErlangDouble(1.0) to FUN)

        assertEquals("declined", code(unordered))
        assertEquals("declined", code(tuple(improper(splicing(list(long(1))), tail = atom("t")), long(0), unordered), unquote = true))
    }

    /**
     * From 1.19 a map whose `__struct__` isn't known escapes as `__escape__/1` or as a plain map, so where an entry
     * raises, which happens isn't known.
     */
    @Test
    fun aMapWhoseStructIsUnknownDeclinesWhereAnEntryRaises() {
        assertEquals("declined", code(map(atom("__struct__") to Unknown, atom("a") to FUN)))
        assertEquals("unread", code(map(atom("__struct__") to Unknown, atom("a") to long(1))))
    }

    @Test
    fun pruningKeepsLineAndNoParensAndFrom118Delimiter() =
        assertEscaped(
            tuple(atom("foo"), keywords("line" to long(1), "column" to long(2), "delimiter" to binary("\""), "no_parens" to atom("true")), list(long(1))),
            "{'{}',[],[foo,[{line,1},{no_parens,true}],[1]]}",
            "1.18.0-rc.0" to "{'{}',[],[foo,[{line,1},{delimiter,<<34>>},{no_parens,true}],[1]]}",
            op = EscapeOp.EscapeAndPrune,
        )

    /** From 1.19 a list's first element is escaped as a term, so pruning reaches a `quote` there and no other. */
    @Test
    fun pruningAQuoteAtTheHeadOfAListFrom119() {
        val quote = tuple(atom("quote"), keywords("line" to long(1), "column" to long(2)), list(atom("x")))

        assertEscaped(
            list(quote, quote),
            "[{'{}',[],[quote,[{line,1},{column,2}],[x]]},{'{}',[],[quote,[{line,1},{column,2}],[x]]}]",
            "1.16.0-rc.0" to "[{'{}',[],[quote,[{line,1}],[x]]},{'{}',[],[quote,[{line,1}],[x]]}]",
            "1.19.0" to "[{'{}',[],[quote,[{line,1}],[x]]},{'{}',[],[quote,[{line,1},{column,2}],[x]]}]",
            op = EscapeOp.EscapeAndPrune,
        )
    }

    @Test
    fun pruningSkipsWhatIsNotAKeptPair() =
        assertEscaped(
            tuple(atom("foo"), list(atom("a"), tuple(atom("line"), long(1)), tuple(atom("x"), long(1), long(2)), tuple(atom("line"), long(2))), list()),
            "{'{}',[],[foo,[{line,1},{line,2}],[]]}",
            op = EscapeOp.EscapeAndPrune,
        )

    @Test
    fun pruningMetadataThatIsNotAListRaisesBefore117() =
        assertEscaped(
            tuple(atom("foo"), atom("bar"), list(long(1))),
            "raise ErlangError",
            "1.17.0-rc.0" to "{'{}',[],[foo,bar,[1]]}",
            op = EscapeOp.EscapeAndPrune,
        )

    @Test
    fun aQuoteHeadsMetadataDropsItsColumnFrom116Until119() =
        assertEscaped(
            tuple(atom("quote"), keywords("line" to long(1), "column" to long(2)), list(keywords("do" to tuple(atom("unquote"), list(), list(atom("x")))))),
            "{'{}',[],[quote,[{line,1},{column,2}],[[{do,{'{}',[],[unquote,[],[x]]}}]]]}",
            "1.16.0-rc.0" to "{'{}',[],[quote,[{line,1}],[[{do,{'{}',[],[unquote,[],[x]]}}]]]}",
            "1.19.0" to "{'{}',[],[quote,[{line,1},{column,2}],[[{do,{'{}',[],[unquote,[],[x]]}}]]]}",
            unquote = true,
        )

    @Test
    fun aQuoteHeadsMetadataIsQuotedFrom119() =
        assertEscaped(
            tuple(atom("quote"), keywords("foo" to tuple(long(1), long(2), long(3))), list(atom("x"))),
            "{'{}',[],[quote,[{foo,{1,2,3}}],[x]]}",
            "1.19.0" to "{'{}',[],[quote,[{foo,{'{}',[],[1,2,3]}}],[x]]}",
        )

    @Test
    fun aQuoteHeadWhoseMetadataIsNotAListRaisesOnlyIn116() =
        assertEscaped(
            tuple(atom("quote"), atom("foo"), list(atom("bar"))),
            "{'{}',[],[quote,foo,[bar]]}",
            "1.16.0-rc.0" to "raise FunctionClauseError",
            "1.17.0-rc.0" to "{'{}',[],[quote,foo,[bar]]}",
        )

    @Test
    fun aQuoteHeadWithImproperMetadata() =
        assertEscaped(
            tuple(atom("quote"), improper(tuple(atom("line"), long(1)), tail = atom("t")), list(atom("x"))),
            "{'{}',[],[quote,[{line,1}|t],[x]]}",
            "1.16.0-rc.0" to "raise FunctionClauseError",
            "1.19.0" to "{'{}',[],[quote,[{'|',[],[{line,1},t]}],[x]]}",
        )

    @Test
    fun aQuoteOfOptionsQuotesItsBodyWithoutUnquote() =
        assertEscaped(
            tuple(atom("quote"), list(), list(keywords("unquote" to atom("false")), tuple(atom("unquote"), list(), list(atom("x"))))),
            "{'{}',[],[quote,[],[[{unquote,false}],{'{}',[],[unquote,[],[x]]}]]}",
            unquote = true,
        )

    @Test
    fun unquoteIsItsExpression() {
        assertEscaped(
            tuple(atom("foo"), list(), list(tuple(atom("unquote"), list(), list(tuple(atom("x"), list(), atom("nil")))))),
            "{'{}',[],[foo,[],[{x,[],nil}]]}",
            unquote = true,
        )
        assertEscaped(tuple(atom("unquote"), list(), list(atom("x"))), "{'{}',[],[unquote,[],[x]]}")
    }

    @Test
    fun unquoteWhoseMetadataIsNotAListIsItsExpressionBefore117() =
        assertEscaped(
            tuple(atom("unquote"), atom("foo"), list(atom("x"))),
            "x",
            "1.17.0-rc.0" to "{'{}',[],[unquote,foo,[x]]}",
            unquote = true,
        )

    @Test
    fun unquoteSplicingAmongElementsIsAConcatenation() {
        assertEscaped(
            list(long(1), splicing(atom("x")), long(2)),
            "{{'.',[],[erlang,'++']},[],[[1],{{'.',[],[elixir_quote,list]},[],[x,[2]]}]}",
            unquote = true,
        )
        assertEscaped(list(splicing(atom("x"))), "{{'.',[],[elixir_quote,list]},[],[x,[]]}", unquote = true)
        assertEscaped(list(long(1), splicing(atom("x"))), "[1,{'{}',[],[unquote_splicing,[],[x]]}]")
    }

    @Test
    fun unquoteSplicingBeforeATailIsATailList() {
        assertEscaped(
            list(long(1), tuple(atom("|"), list(), list(splicing(atom("x")), atom("y")))),
            "{{'.',[],[elixir_quote,tail_list]},[],[x,y,[1]]}",
            unquote = true,
        )
        assertEscaped(
            list(tuple(atom("|"), keywords("line" to long(1)), list(splicing(atom("x")), atom("y")))),
            "{{'.',[{line,1}],[elixir_quote,tail_list]},[{line,1}],[x,y,[]]}",
            unquote = true,
        )
    }

    @Test
    fun unquoteSplicingInAPairOrATupleIsBuiltAtRunTime() {
        assertEscaped(
            tuple(splicing(atom("x")), long(1)),
            "{'{}',[],['{}',[],{{'.',[],[elixir_quote,list]},[],[x,[1]]}]}",
            unquote = true,
        )
        assertEscaped(
            tuple(long(1), splicing(atom("x")), long(2)),
            "{'{}',[],{{'.',[],[erlang,'++']},[],[[1],{{'.',[],[elixir_quote,list]},[],[x,[2]]}]}}",
            unquote = true,
        )
    }

    @Test
    fun unquoteSplicingBeforeAnImproperTailRaises() =
        assertEscaped(improper(splicing(atom("x")), tail = long(2)), "raise FunctionClauseError", unquote = true)

    /** Before 1.18.4 the entries are quoted as one list, so an entry holding `unquote_splicing` is a splicing pair. */
    @Test
    fun unquoteSplicingInAMapEntry() {
        assertEscaped(
            map(splicing(atom("x")) to long(1)),
            "{'%{}',[],[{'{}',[],['{}',[],{{'.',[],[elixir_quote,list]},[],[x,[1]]}]}]}",
            "1.18.4" to "{'%{}',[],[{{'{}',[],[unquote_splicing,[],[x]]},1}]}",
            unquote = true,
        )
        assertEscaped(map(splicing(atom("x")) to long(1)), "{'%{}',[],[{{'{}',[],[unquote_splicing,[],[x]]},1}]}")
    }

    @Test
    fun anUnquotedNameIsADotCall() {
        assertEscaped(
            tuple(tuple(atom("."), keywords("line" to long(3), "column" to long(4)), list(atom("m"), atom("unquote"))), list(), list(atom("f"))),
            "{{'.',[{line,3},{column,4}],[elixir_quote,dot]},[{line,3},{column,4}],[[{line,3},{column,4}],m,f,nil,nil]}",
            "1.16.0-rc.0" to "{{'.',[{line,3},{column,4}],[elixir_quote,dot]},[{line,3},{column,4}],[[{line,3}],m,f,nil,nil]}",
            "1.19.0" to "{{'.',[{line,3},{column,4}],[elixir_quote,dot]},[{line,3},{column,4}],[[{line,3},{column,4}],m,f,nil,nil]}",
            unquote = true,
        )
        assertEscaped(
            tuple(tuple(tuple(atom("."), keywords("line" to long(3)), list(atom("m"), atom("unquote"))), list(), list(atom("f"))), list(), list(long(1))),
            "{{'.',[{line,3}],[elixir_quote,dot]},[{line,3}],[[{line,3}],m,f,[1],nil]}",
            unquote = true,
        )
    }

    @Test
    fun anUnquotedNamesMetadataIsQuotedBefore113AndFrom119() =
        assertEscaped(
            tuple(tuple(atom("."), keywords("line" to long(3), "foo" to tuple(long(1), long(2), long(3))), list(atom("m"), atom("unquote"))), list(), list(atom("f"))),
            "{{'.',[{line,3},{foo,{1,2,3}}],[elixir_quote,dot]},[{line,3},{foo,{1,2,3}}],[[{line,3},{foo,{'{}',[],[1,2,3]}}],m,f,nil,nil]}",
            "1.13.0-rc.0" to "{{'.',[{line,3},{foo,{1,2,3}}],[elixir_quote,dot]},[{line,3},{foo,{1,2,3}}],[[{line,3},{foo,{1,2,3}}],m,f,nil,nil]}",
            "1.19.0" to "{{'.',[{line,3},{foo,{1,2,3}}],[elixir_quote,dot]},[{line,3},{foo,{1,2,3}}],[[{line,3},{foo,{'{}',[],[1,2,3]}}],m,f,nil,nil]}",
            unquote = true,
        )

    @Test
    fun aQuoteInAListWithoutUnquote() =
        assertEscaped(list(tuple(atom("quote"), list(), list(atom("x"))), long(2)), "[{'{}',[],[quote,[],[x]]},2]")

    @Test
    fun aStructOfAModuleThatIsNotThereIsAMap() =
        assertEscaped(
            map(atom("__struct__") to atom("Elixir.NoSuchModule"), atom("a") to long(1)),
            "{'%{}',[],[{'__struct__','Elixir.NoSuchModule'},{a,1}]}",
        )

    /** From 1.19 Elixir escapes it with the module's `__escape__/1`, which runs code the escape doesn't. */
    @Test
    fun aStructWithItsOwnEscapeDeclinesFrom119() =
        assertEscaped(REGEX, "{'%{}',[],[{'__struct__','Elixir.Regex'},{source,<<97>>}]}", "1.19.0-rc.1" to "declined")

    @Test
    fun aStructOfAModuleWhoseExportsAreUnreadableDeclinesFrom119() =
        assertEscaped(
            map(atom("__struct__") to atom("Elixir.Unreadable"), atom("a") to long(1)),
            "{'%{}',[],[{'__struct__','Elixir.Unreadable'},{a,1}]}",
            "1.19.0-rc.1" to "declined",
        )

    /** A struct's default `Import` reads, built as code. */
    @Test
    fun aDefaultsTermIsBuiltAsCode() {
        val term = Term.List(listOf(Term.Pair(Term.Atom("a"), Term.Integer(1.toBigInteger())), Term.Tuple(listOf(Term.Atom("x"))), Term.Binary("s".toByteArray())))

        assertEquals("[{a,1},{'{}',[],[x]},<<115>>]", code(term))
    }

    @Test
    fun aDefaultsImproperListIsKnown() =
        assertEquals("[{'|',[],[1,2]}]", code(Term.List(listOf(Term.Integer(1.toBigInteger())), tail = Term.Integer(2.toBigInteger()))))

    @Test
    fun aDefaultsUnknownPartIsUnread() {
        assertEquals("unread", code(Term.List(listOf(Term.Node(Term.Node.Kind.VARIABLE)))))
        assertEquals("unread", code(Term.Binary(null)))
        assertEquals("unread", code(Term.NonTuple))
        assertEquals("unread", code(Term.Unexpanded))
    }

    @Test
    fun anAtomKeyedMapListsItsKeysByCodePoint() {
        val map = QuoteEscape.map(AstEscapeBuilder(S), ElixirLanguageLevel.of("1.20.4"), listOf("z", "é", "a", "__struct__", "B").map { it to S.atom(it) })

        assertEquals("{'%{}',[],[{'B','B'},{'__struct__','__struct__'},{a,a},{z,z},{é,é}]}", written(map!!.toOtp()))
    }

    @Test
    fun anAtomKeyedMapKeepsEachKeysValue() {
        val map = QuoteEscape.map(AstEscapeBuilder(S), ElixirLanguageLevel.of("1.20.4"), listOf("b" to S.atom("one"), "a" to S.atom("two")))

        assertEquals("{'%{}',[],[{a,two},{b,one}]}", written(map!!.toOtp()))
    }

    private fun assertEscaped(
        input: OtpErlangObject,
        before: String,
        vararg from: Pair<String, String>,
        op: EscapeOp = EscapeOp.Escape,
        unquote: Boolean = false,
        legs: List<String> = LEGS,
    ) =
        assertEquals(
            legs.joinToString("\n") { leg -> "$leg: " + (from.lastOrNull { !isBefore(leg, it.first) }?.second ?: before) },
            legs.joinToString("\n") { leg -> "$leg: " + rendered(terms(leg, op, unquote).escape(input)) },
        )

    private fun terms(leg: String, op: EscapeOp = EscapeOp.Escape, unquote: Boolean = false) =
        QuoteEscape(TermEscapeView, TermEscapeBuilder, { it }, ElixirLanguageLevel.of(leg), EXPORTS, op, unquote)

    private fun code(term: Term): String =
        rendered(QuoteEscape(ImportTermEscapeView, AstEscapeBuilder(S), { null }, ElixirLanguageLevel.of("1.20.4"), EXPORTS).escape(term)) {
            written(it.toOtp())
        }

    /** [term], an interpreter value, escaped into code at 1.20.4. */
    private fun code(term: OtpErlangObject, unquote: Boolean = false): String =
        rendered(QuoteEscape(TermEscapeView, AstEscapeBuilder(S), { null }, ElixirLanguageLevel.of("1.20.4"), EXPORTS, unquote = unquote).escape(term)) {
            written(it.toOtp())
        }

    private fun <O> rendered(result: EscapeResult<O>, write: (O) -> String = { written(it as OtpErlangObject) }): String =
        when (result) {
            is EscapeResult.Built -> write(result.value)
            is EscapeResult.Raised -> "raise " + result.exception.removePrefix("Elixir.")
            is EscapeResult.Unread -> "unread"
            EscapeResult.Declined -> "declined"
        }

    private fun splicing(expr: OtpErlangObject) = tuple(atom("unquote_splicing"), list(), list(expr))

    private fun keywords(vararg pairs: Pair<String, OtpErlangObject>) = list(*pairs.map { tuple(atom(it.first), it.second) }.toTypedArray())

    private companion object {
        val LEGS = listOf("1.11.4", "1.13.4", "1.14.5", "1.15.8", "1.16.3", "1.17.3", "1.18.4", "1.19.5", "1.20.4")

        val S = Synthetic(Meta(TextRange(0, 0), Meta.Position(1, 1), Meta.Position(1, 1), emptyList()))

        val PID = OtpErlangPid("nonode@nohost", 1, 0, 0)

        val REF = OtpErlangRef("nonode@nohost", intArrayOf(1, 2, 3), 0)

        val FUN = OtpErlangFun(PID, "Elixir.M", 0, 0, emptyArray())

        val REGEX = map(atom("__struct__") to atom("Elixir.Regex"), atom("source") to binary("a"))

        val EXPORTS = Exports { module ->
            when (module) {
                "Elixir.Regex" -> ModuleExports.Present(listOf(NameArity("__escape__", 1)), emptyList(), hasInfo = true)
                "Elixir.Unreadable" -> ModuleExports.Unreadable
                else -> ModuleExports.Absent
            }
        }

        fun atom(name: String) = OtpErlangAtom(name)

        fun long(value: Long) = OtpErlangLong(value)

        fun binary(text: String) = OtpErlangBinary(text.toByteArray())

        fun tuple(vararg elements: OtpErlangObject) = OtpErlangTuple(elements)

        fun list(vararg elements: OtpErlangObject) = OtpErlangList(elements)

        fun improper(vararg elements: OtpErlangObject, tail: OtpErlangObject) = OtpErlangList(elements, tail)

        fun map(vararg entries: Pair<OtpErlangObject, OtpErlangObject>) =
            OtpErlangMap(entries.map { it.first }.toTypedArray(), entries.map { it.second }.toTypedArray())
    }
}
