package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangBitstr
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangExternalFun
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangPid
import com.ericsson.otp.erlang.OtpErlangPort
import com.ericsson.otp.erlang.OtpErlangRef
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger
import java.util.Random

/** Erlang orders atoms by code point, so U+F900 comes before U+20000, though its UTF-16 unit is higher. */
class TermOrderTest {
    private val otp29 = ElixirLanguageLevel.of("1.20.4", "29")
    private val bmp = "豈"
    private val supplementary = String(Character.toChars(0x20000))

    @Test
    fun atomsCompareByCodePoint() =
        assertEquals(listOf(bmp, supplementary), listOf(supplementary, bmp).sortedWith(ATOM_ORDER))

    @Test
    fun variablesCompareNamesThenContextsByCodePoint() =
        assertEquals(
            listOf(Variable("a", atom(bmp)), Variable("a", atom(supplementary)), Variable(bmp, Variable.NIL)),
            listOf(Variable(bmp, Variable.NIL), Variable("a", atom(supplementary)), Variable("a", atom(bmp)))
                .sortedWith(VARIABLE_ORDER),
        )

    @Test
    fun counterModulesCompareByCodePoint() =
        assertEquals(
            listOf(Variable("a", inModule(bmp)), Variable("a", inModule(supplementary))),
            listOf(Variable("a", inModule(supplementary)), Variable("a", inModule(bmp))).sortedWith(VARIABLE_ORDER),
        )

    @Test
    fun nameAritiesCompareNamesByCodePointThenArities() =
        assertEquals(
            listOf(NameArity(bmp, 1), NameArity(supplementary, 0), NameArity(supplementary, 1)),
            listOf(NameArity(supplementary, 1), NameArity(bmp, 1), NameArity(supplementary, 0))
                .sortedWith(NAME_ARITY_ORDER),
        )

    /** Each expected order is `lists:sort/1`'s on OTP 29. */
    @Test
    fun termsCompareByTypeThenWithinIt() =
        assertSorted(
            long(-1), OtpErlangDouble(2.5), long(3), OtpErlangLong(BigInteger.TWO.pow(64)),
            atomTerm("B"), atomTerm("a"), atomTerm("b"), atomTerm("é"),
            OtpErlangRef("n@h", intArrayOf(1, 2, 3), 0),
            OtpErlangExternalFun("lists", "map", 2),
            OtpErlangPort("n@h", 1, 0),
            OtpErlangPid("n@h", 1, 0, 0),
            tuple(long(2)), tuple(long(1), long(2)), tuple(long(1), long(2), long(3)),
            map(atomTerm("a") to long(2)), map(atomTerm("b") to long(1)), map(atomTerm("a") to long(1), atomTerm("b") to long(2)),
            OtpErlangList(), improper(long(1), tail = long(2)), OtpErlangString("ab"),
            OtpErlangBinary(byteArrayOf()), OtpErlangBitstr(byteArrayOf(0x20), 5), binary(97), binary(98),
        )

    /** `-0.0`, `0` and `0.0` are equal, as are `1.0` and `1`, so each keeps its place. */
    @Test
    fun equalNumbersKeepTheirOrder() =
        assertEquals(
            listOf(OtpErlangDouble(-0.0), long(0), OtpErlangDouble(0.0), OtpErlangDouble(0.5), OtpErlangDouble(1.0), long(1)),
            listOf(OtpErlangDouble(1.0), long(1), OtpErlangDouble(0.5), OtpErlangDouble(-0.0), long(0), OtpErlangDouble(0.0))
                .sortedWith(termOrder(otp29)),
        )

    @Test
    fun listsCompareElementByElementThenTheirTails() =
        assertSorted(
            improper(long(1), tail = long(2)), improper(long(1), tail = atomTerm("a")), list(long(1)), list(long(1), long(2)),
            improper(long(97), tail = long(98)), OtpErlangString("ab"),
        )

    @Test
    fun bitstringsCompareBitByBit() =
        assertSorted(
            OtpErlangBitstr(byteArrayOf(0), 7),
            OtpErlangBitstr(byteArrayOf(0x40), 6),
            OtpErlangBitstr(byteArrayOf(0x80.toByte()), 7),
            OtpErlangBitstr(byteArrayOf(0x80.toByte()), 1),
            binary(128),
        )

    @Test
    fun externalFunsCompareModuleThenFunctionThenArity() =
        assertSorted(
            OtpErlangExternalFun("erlang", "abs", 1),
            OtpErlangExternalFun("lists", "foldl", 3),
            OtpErlangExternalFun("lists", "map", 1),
            OtpErlangExternalFun("lists", "map", 2),
        )

    /** A map's keys compare with every integer before every float, at any depth; its values compare as numbers. */
    @Test
    fun mapsCompareSizeThenKeysThenValues() {
        assertSorted(
            map(long(1) to atomTerm("x")),
            map(long(1) to atomTerm("y")),
            map(long(2) to atomTerm("a")),
            map(OtpErlangDouble(1.0) to atomTerm("x")),
        )
        assertSorted(map(tuple(long(2)) to atomTerm("a")), map(tuple(OtpErlangDouble(1.0)) to atomTerm("x")))
        assertSorted(map(atomTerm("a") to tuple(OtpErlangDouble(1.0))), map(atomTerm("a") to tuple(long(2))))
    }

    @Test
    fun mapKeysPutEveryIntegerBeforeEveryFloat() =
        assertEquals(
            listOf(long(1), long(2), OtpErlangDouble(1.0), OtpErlangDouble(1.5)),
            listOf(OtpErlangDouble(1.5), long(2), OtpErlangDouble(1.0), long(1)).sortedWith(mapKeyOrder(otp29)),
        )

    @Test
    fun mapValuesEqualAsNumbersKeepTheirOrder() {
        val terms = listOf(map(atomTerm("a") to OtpErlangDouble(1.0)), map(atomTerm("a") to long(1)))

        assertEquals(terms, terms.sortedWith(termOrder(otp29)))
    }

    /** `-0.0` and `0.0` are two map keys from OTP 27, `-0.0` first, so they order the maps that hold them. */
    @Test
    fun negativeZeroIsItsOwnMapKeyFromOtp27() {
        val otp27 = ElixirLanguageLevel.of("1.18.4", "27")

        assertEquals(-1, mapKeyOrder(otp27).compare(OtpErlangDouble(-0.0), OtpErlangDouble(0.0)))
        assertEquals(0, termOrder(otp27).compare(OtpErlangDouble(-0.0), OtpErlangDouble(0.0)))
        assertEquals(-1, termOrder(otp27).compare(map(OtpErlangDouble(-0.0) to long(1)), map(OtpErlangDouble(0.0) to long(1))))
    }

    @Test
    fun negativeZeroIsZeroBeforeOtp27() {
        val otp26 = ElixirLanguageLevel.of("1.16.3", "26")

        assertEquals(0, mapKeyOrder(otp26).compare(OtpErlangDouble(-0.0), OtpErlangDouble(0.0)))
        assertEquals(0, termOrder(otp26).compare(map(OtpErlangDouble(-0.0) to long(1)), map(OtpErlangDouble(0.0) to long(1))))
    }

    /** Where two pids, ports, references or closures go is the VM's own choice, which no term here decides. */
    @Test
    fun differentPidsHaveNoOrder() {
        assertEquals(0, termOrder(otp29).compare(OtpErlangPid("n@h", 1, 0, 0), OtpErlangPid("n@h", 1, 0, 0)))
        assertThrows(IllegalArgumentException::class.java) {
            termOrder(otp29).compare(OtpErlangPid("n@h", 1, 0, 0), OtpErlangPid("n@h", 2, 0, 0))
        }
    }

    private fun assertSorted(vararg expected: OtpErlangObject) {
        val sorted = expected.toList()

        assertEquals(sorted, sorted.reversed().sortedWith(termOrder(otp29)))
        assertEquals(sorted, sorted.shuffled(Random(0)).sortedWith(termOrder(otp29)))
    }

    private fun long(value: Long) = OtpErlangLong(value)

    private fun atomTerm(name: String) = OtpErlangAtom(name)

    private fun binary(byte: Int) = OtpErlangBinary(byteArrayOf(byte.toByte()))

    private fun tuple(vararg elements: OtpErlangObject) = OtpErlangTuple(elements)

    private fun list(vararg elements: OtpErlangObject) = OtpErlangList(elements)

    private fun improper(vararg elements: OtpErlangObject, tail: OtpErlangObject) = OtpErlangList(elements, tail)

    private fun map(vararg entries: Pair<OtpErlangObject, OtpErlangObject>) =
        OtpErlangMap(entries.map { it.first }.toTypedArray(), entries.map { it.second }.toTypedArray())

    private fun atom(text: String) = Variable.Context.Atom(text)

    private fun inModule(module: String) = Variable.Context.Counter(Env.Counter.InModule(module, 1))
}
